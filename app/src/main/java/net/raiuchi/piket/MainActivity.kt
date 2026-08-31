package net.raiuchi.piket

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    companion object {
        @JvmField val EXTRA_LIFECYCLE_TEST = "net.raiuchi.piket.extra.LIFECYCLE_TEST"
    }

    private var pendingStart: (() -> Unit)? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) pendingStart?.invoke()
        pendingStart = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_LIFECYCLE_TEST, false)) {
            setContent { PiketTheme { } }
            return
        }
        setContent {
            PiketTheme {
                PiketApp(
                    onKeepScreen = ::setKeepScreen,
                    onStart = ::startNativeTrip,
                    onStop = { stopService(Intent(this, TrackingService::class.java)) },
                    onRecalibrate = { config -> sendConfig(config, TrackingService.ACTION_RECALIBRATE) }
                )
            }
        }
    }

    private fun setKeepScreen(enabled: Boolean) {
        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun startNativeTrip(config: NativeUiConfig) {
        val action = {
            val intent = Intent(this, TrackingService::class.java).apply {
                action = TrackingService.ACTION_CONFIGURE_NATIVE
                putExtra(TrackingService.EXTRA_NATIVE_CONFIG, config.toJson().toString())
            }
            ContextCompat.startForegroundService(this, intent)
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) action()
        else {
            pendingStart = action
            val required = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) required += Manifest.permission.POST_NOTIFICATIONS
            permissions.launch(required.toTypedArray())
        }
    }

    private fun sendConfig(config: NativeUiConfig, requestedAction: String) {
        startService(Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_CONFIGURE_NATIVE
            putExtra(TrackingService.EXTRA_NATIVE_CONFIG, config.toJson().toString())
            putExtra("afterAction", requestedAction)
        })
    }
}

data class NativeUiConfig(
    val route: String,
    val direction: String,
    val manualOfficialM: Double,
    val restrictions: List<RestrictionRecord>,
    val leadM: Int,
    val active: Boolean = true
) {
    fun toJson() = JSONObject().apply {
        put("route", route); put("direction", direction); put("manualOfficialM", manualOfficialM)
        put("lead", leadM); put("active", active)
        put("restrictions", JSONArray().apply {
            restrictions.forEach { item -> put(JSONObject().apply {
                put("id", item.id); put("peregon", item.route); put("dir", item.direction)
                put("km", item.km); put("pk", item.pk); put("m", item.meter)
                put("lead", item.leadM)
            }) }
        })
    }
}

