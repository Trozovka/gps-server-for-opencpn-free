package com.trozovka.gpsserver.core.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.trozovka.gpsserver.core.entitlement.EntitlementHost
import com.trozovka.gpsserver.core.location.LocationSource
import com.trozovka.gpsserver.core.location.SatelliteStatus
import com.trozovka.gpsserver.core.nmea.GpsFix
import com.trozovka.gpsserver.core.nmea.NmeaFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

data class ConnectedClient(val address: String, val port: Int)
data class SentStats(val sentenceCount: Long, val byteCount: Long)

/**
 * Foreground service owning the TCP server socket, the wake lock that keeps it running with
 * the screen off, and the location callback that supplies each second's NMEA fix. Live state is
 * exposed via companion StateFlows rather than a bound-service API, since MainActivity only ever
 * starts this service and doesn't need a two-way binding for a single-consumer telemetry panel.
 */
class GpsServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var locationSource: LocationSource
    private var started = false

    override fun onCreate() {
        super.onCreate()
        try {
            startForegroundWithNotification()
        } catch (e: RuntimeException) {
            // A location service may not be (re)started from the background without background
            // location permission. Stop quietly; the user starts it again from the app.
            Log.w(TAG, "Cannot run as a foreground service now", e)
            stopSelf()
            return
        }
        started = true
        acquireWakeLock()
        locationSource = LocationSource(applicationContext)
        locationSource.start()
        _isRunning.value = true
        _capExpired.value = false
        scope.launch {
            locationSource.fixes.collect { _latestFix.value = it }
        }
        scope.launch {
            locationSource.satellites.collect { _satellites.value = it }
        }
        scope.launch {
            locationSource.gpsEnabled.collect { _gpsEnabled.value = it }
        }
        scope.launch {
            // Session time counts from the first position: finding GPS can take long at sea.
            locationSource.fixes.first { it != null }
            _startTimeMillis.value = System.currentTimeMillis()
            val maxRuntime = EntitlementHost.current().maxRuntimeMillis()
            if (maxRuntime != null) {
                delay(maxRuntime)
                // Ask again: Pro may have been bought during this session.
                if (EntitlementHost.current().maxRuntimeMillis() != null) {
                    _capExpired.value = true
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) return START_NOT_STICKY
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val port = intent?.getIntExtra(EXTRA_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
        // Always all networks (0.0.0.0); a specific saved IP goes stale when Wi-Fi/hotspot changes.
        val bindAddress: String? = null
        if (serverJob == null) {
            startServer(port, bindAddress)
        }
        // Automatic restart happens in the background, which needs background location.
        return if (hasBackgroundLocation()) START_STICKY else START_NOT_STICKY
    }

    /** One connected OpenCPN (or other) client: its socket, its own send queue, and when a write last succeeded. */
    private class ClientConn(val socket: Socket, val info: ConnectedClient) {
        val queue = Channel<ByteArray>(Channel.CONFLATED)
        /** When the current write started (0 = not writing). A write stuck this long means the client is gone. */
        @Volatile var writingSince = 0L
    }

    private val clients = ConcurrentHashMap<Socket, ClientConn>()

    private fun publishClients() {
        val list = clients.values.map { it.info }
        _clients.value = list
        _connectedClient.value = list.lastOrNull()
    }

    private fun startServer(port: Int, bindAddress: String?) {
        serverJob = scope.launch {
            // Broadcaster: build each second's sentences ONCE and hand them to every client's own queue,
            // so a slow or dead client can never hold up the others (v1.0.3, multi-client).
            launch {
                while (isActive) {
                    val fix = locationSource.fixes.value
                    if (fix != null && clients.isNotEmpty()) {
                        val bytes = buildFix(fix)
                        clients.values.forEach { it.queue.trySend(bytes) }
                    }
                    delay(1000)
                }
            }
            // Watchdog: a client whose current write has been stuck for DEAD_CLIENT_MILLIS (laptop asleep, Wi-Fi gone,
            // firewall dropping packets so no TCP reset) is closed. Idle clients (no GPS fix yet) are never dropped.
            launch {
                while (isActive) {
                    delay(2000)
                    val now = System.currentTimeMillis()
                    clients.values.filter { it.writingSince != 0L && now - it.writingSince > DEAD_CLIENT_MILLIS }.forEach {
                        Log.i(TAG, "Dropping unresponsive client ${it.info.address}:${it.info.port}")
                        runCatching { it.socket.close() }
                    }
                }
            }
            try {
                // Explicit IPv4 wildcard: an address-less InetSocketAddress(port) binds the
                // IPv6 wildcard on this hardware, which didn't accept plain IPv4 connections
                // from OpenCPN/nc in testing.
                val socketAddress = InetSocketAddress(bindAddress ?: "0.0.0.0", port)
                ServerSocket().apply {
                    reuseAddress = true
                    bind(socketAddress)
                }.use { server ->
                    while (isActive) {
                        val socket = server.accept()
                        if (clients.size >= MAX_CLIENTS) {
                            Log.w(TAG, "Too many clients, refusing ${socket.inetAddress.hostAddress}")
                            runCatching { socket.close() }
                            continue
                        }
                        val conn = ClientConn(
                            socket,
                            ConnectedClient(socket.inetAddress.hostAddress ?: "?", socket.port),
                        )
                        clients[socket] = conn
                        publishClients()
                        launch { handleClient(conn) }
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Server loop ended", e)
            } finally {
                clients.keys.forEach { runCatching { it.close() } }
                clients.clear()
                publishClients()
            }
        }
    }

    private suspend fun handleClient(conn: ClientConn) {
        conn.socket.use { client ->
            client.tcpNoDelay = true
            client.keepAlive = true
            // Small send buffer: a dead client fills it within seconds, so its write blocks and the watchdog sees it.
            runCatching { client.sendBufferSize = SEND_BUFFER_BYTES }
            // Notice a client that hangs up even while there is no position to send it; otherwise
            // closed connections would fill all MAX_CLIENTS places.
            scope.launch {
                runCatching {
                    val input = client.getInputStream()
                    val ignored = ByteArray(256)
                    while (input.read(ignored) >= 0) Unit
                }
                conn.queue.close()
            }
            val out = client.getOutputStream()
            try {
                for (bytes in conn.queue) {
                    conn.writingSince = System.currentTimeMillis()
                    out.write(bytes)
                    out.flush()
                    conn.writingSince = 0L
                }
            } catch (e: IOException) {
                Log.i(TAG, "Client disconnected: ${e.message}")
            } finally {
                clients.remove(client)
                conn.queue.close()
                publishClients()
            }
        }
    }

    private fun buildFix(fix: GpsFix): ByteArray {
        // An old position must not look current: OpenCPN ignores sentences marked invalid.
        val ageMillis = SystemClock.elapsedRealtime() - fix.measuredAtElapsedMillis
        val valid = ageMillis <= STALE_FIX_MILLIS
        val sentences = NmeaFormatter.gga(fix, valid) +
            NmeaFormatter.rmc(fix, valid) +
            NmeaFormatter.vtg(fix, valid) +
            NmeaFormatter.gsa(fix, valid)
        val bytes = sentences.toByteArray()

        val stats = _sentStats.value
        _sentStats.value = SentStats(stats.sentenceCount + 4, stats.byteCount + bytes.size)

        val newLines = sentences.split("\r\n").filter { it.isNotBlank() }
        _debugLog.value = (_debugLog.value + newLines).takeLast(MAX_DEBUG_LOG_LINES)

        val latencyMillis = ageMillis
        _latencyMillis.value = latencyMillis
        if (latencyMillis > LATENCY_WARNING_THRESHOLD_MILLIS) {
            Log.w(TAG, "Fix-to-wire latency high: ${latencyMillis}ms")
        }
        return bytes
    }

    private fun hasBackgroundLocation(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:GpsServerWakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GPS Server",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenCPN GPS Server running")
            .setContentText("Streaming NMEA to connected clients")
            .setSmallIcon(com.trozovka.gpsserver.core.R.drawable.ic_notification)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        serverJob?.cancel()
        scope.cancel()
        if (::locationSource.isInitialized) locationSource.stop()
        releaseWakeLock()
        _isRunning.value = false
        _startTimeMillis.value = null
        _latestFix.value = null
        _satellites.value = null
        _gpsEnabled.value = true
        _latencyMillis.value = null
        _connectedClient.value = null
        _clients.value = emptyList()
        _sentStats.value = SentStats(0, 0)
        _debugLog.value = emptyList()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "GpsServerService"
        private const val CHANNEL_ID = "gps_server_channel"
        private const val NOTIFICATION_ID = 1
        private const val LATENCY_WARNING_THRESHOLD_MILLIS = 500L
        private const val MAX_DEBUG_LOG_LINES = 200
        private const val STALE_FIX_MILLIS = 20_000L
        private const val DEAD_CLIENT_MILLIS = 15_000L
        private const val MAX_CLIENTS = 8
        private const val SEND_BUFFER_BYTES = 4096
        const val DEFAULT_PORT = 10110
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_BIND_ADDRESS = "extra_bind_address"
        const val ACTION_STOP = "com.trozovka.gpsserver.core.action.STOP"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _capExpired = MutableStateFlow(false)

        /** True once a runtime-capped tier's session hits its limit and auto-stops. */
        val capExpired: StateFlow<Boolean> = _capExpired.asStateFlow()

        fun acknowledgeCapExpired() {
            _capExpired.value = false
        }

        private val _satellites = MutableStateFlow<SatelliteStatus?>(null)
        val satellites: StateFlow<SatelliteStatus?> = _satellites.asStateFlow()

        private val _gpsEnabled = MutableStateFlow(true)
        val gpsEnabled: StateFlow<Boolean> = _gpsEnabled.asStateFlow()

        /** When the first position of this session arrived. */
        private val _startTimeMillis = MutableStateFlow<Long?>(null)
        val startTimeMillis: StateFlow<Long?> = _startTimeMillis.asStateFlow()

        private val _debugLog = MutableStateFlow<List<String>>(emptyList())
        val debugLog: StateFlow<List<String>> = _debugLog.asStateFlow()

        private val _latestFix = MutableStateFlow<GpsFix?>(null)
        val latestFix: StateFlow<GpsFix?> = _latestFix.asStateFlow()

        private val _latencyMillis = MutableStateFlow<Long?>(null)
        val latencyMillis: StateFlow<Long?> = _latencyMillis.asStateFlow()

        private val _connectedClient = MutableStateFlow<ConnectedClient?>(null)
        /** Most recently connected client (kept for the single-client UI fields). */
        val connectedClient: StateFlow<ConnectedClient?> = _connectedClient.asStateFlow()
        private val _clients = MutableStateFlow<List<ConnectedClient>>(emptyList())
        /** Every connected client (v1.0.3: several laptops can use one phone). */
        val clients: StateFlow<List<ConnectedClient>> = _clients.asStateFlow()

        private val _sentStats = MutableStateFlow(SentStats(0, 0))
        val sentStats: StateFlow<SentStats> = _sentStats.asStateFlow()
    }
}
