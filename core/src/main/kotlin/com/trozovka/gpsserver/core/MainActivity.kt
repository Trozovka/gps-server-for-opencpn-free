package com.trozovka.gpsserver.core

import com.trozovka.gpsserver.core.ui.TrozovkaTheme
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.trozovka.gpsserver.core.service.GpsServerService
import com.trozovka.gpsserver.core.settings.AppPreferences
import com.trozovka.gpsserver.core.ui.MainScreen
import com.trozovka.gpsserver.core.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    private var startAfterPermissions = false
    private lateinit var preferences: AppPreferences

    private val requestForegroundPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            requestBackgroundLocationIfNeeded()
        } else {
            startAfterPermissions = false
        }
    }

    private val requestBackgroundLocation = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        requestBatteryOptimizationExemptionIfNeeded()
    }

    private val batteryOptimizationSettings = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (startAfterPermissions) {
            startAfterPermissions = false
            startServer()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The UI is always light, so keep dark status/navigation bar icons even in system dark mode.
        val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        super.onCreate(savedInstanceState)
        preferences = AppPreferences(applicationContext)
        setContent {
            TrozovkaTheme {
                var showSettings by remember { mutableStateOf(false) }
                BackHandler(enabled = showSettings) { showSettings = false }
                if (showSettings) {
                    SettingsScreen(
                        preferences = preferences,
                        onBack = { showSettings = false },
                        onAllowBackground = if (canAskForBackgroundRunning()) ::openBatteryScreen else null,
                    )
                } else {
                    MainScreen(
                        preferences = preferences,
                        onStartRequested = ::beginStartFlow,
                        onStopRequested = ::stopServer,
                        onOpenSettings = { showSettings = true },
                    )
                }
            }
        }
    }


    private fun beginStartFlow() {
        startAfterPermissions = true
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }

        if (needed.isNotEmpty()) {
            requestForegroundPermissions.launch(needed.toTypedArray())
        } else {
            requestBackgroundLocationIfNeeded()
        }
    }

    private fun requestBackgroundLocationIfNeeded() {
        val backgroundGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        if (!backgroundGranted && declaresPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            requestBackgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            requestBatteryOptimizationExemptionIfNeeded()
        }
    }

    private fun requestBatteryOptimizationExemptionIfNeeded() {
        if (canAskForBackgroundRunning() && !preferences.batteryScreenShown) {
            preferences.batteryScreenShown = true
            openBatteryScreen()
        } else {
            startAfterPermissions = false
            startServer()
        }
    }

    private fun canAskForBackgroundRunning(): Boolean {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        return !powerManager.isIgnoringBatteryOptimizations(packageName) &&
            declaresPermission(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
    }

    private fun openBatteryScreen() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        batteryOptimizationSettings.launch(intent)
    }

    /** Store builds may leave a permission out of their manifest; then it must not be requested. */
    private fun declaresPermission(permission: String): Boolean =
        packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(permission) == true

    private fun startServer() {
        val intent = Intent(this, GpsServerService::class.java).apply {
            putExtra(GpsServerService.EXTRA_PORT, preferences.port)
            // No bind address on purpose: the server listens on every network of the phone (0.0.0.0).
            // Binding the saved IP broke the server whenever the phone got a new address (v1.0.2 fix).
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopServer() {
        val intent = Intent(this, GpsServerService::class.java).apply {
            action = GpsServerService.ACTION_STOP
        }
        startService(intent)
    }
}
