package net.raiuchi.piket

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.*
import android.speech.tts.TextToSpeech
import com.google.android.gms.location.*
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Полностью нативная фоновая служба. Получение координат, фильтрация движения,
 * переходы маршрутных осей, счисление при потере сигнала и ограничения не зависят от UI.
 */
@Suppress("DEPRECATION")
class TrackingService : Service() {
    companion object {
        private const val CHANNEL_ID = "piket_tracking"
        @Volatile internal var latestSnapshotJson: String? = null
        const val ACTION_RECALIBRATE = "net.raiuchi.piket.ACTION_RECALIBRATE"
        const val ACTION_CONFIGURE_NATIVE = "net.raiuchi.piket.ACTION_CONFIGURE_NATIVE"
        const val EXTRA_NATIVE_CONFIG = "net.raiuchi.piket.extra.NATIVE_CONFIG"

        fun updateNotificationText(context: Context, text: String) {
            val pending = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setContentTitle("ПИКЕТ · $text").setContentText("Контроль ограничений активен")
                .setSmallIcon(R.drawable.ic_launcher_foreground).setContentIntent(pending)
                .setOngoing(true).build()
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.notify(1, notification)
        }
    }

    private var fusedClient: FusedLocationProviderClient? = null
    private var mainLocationCallback: LocationCallback? = null
    private var networkCallback: LocationCallback? = null
    private var networkBackupActive = false
    private var lastFixReceivedAt = 0L
    private var lastFusedRestartAt = 0L
    private var signalUnavailableMarked = false
    private var lastLocationAvailable: Boolean? = null
    private var lastUsableFixAt = 0L
    private var unusableFixMarked = false
    private var watchdog: Runnable? = null
    private var lastPowerSampleAt = 0L
    private var lastProcessCpuMs = 0L

    private var locationManager: LocationManager? = null
    private var gnssCallback: GnssStatus.Callback? = null
    private var directGpsListener: LocationListener? = null
    private var directGpsActive = false
    private var directGpsStartedAt = 0L
    private var lastDirectGpsStopAt = 0L
    private var fusedRecoveryFixes = 0
    private var directGpsExtendedLogged = false
    private var lastProcessedFixNanos = 0L
    @Volatile private var satellitesUsed = 0
    @Volatile private var averageCn0 = 0f
    @Volatile private var gnssTelemetrySeen = false

    private val motionFilter = NativeMotionFilter()
    private var routeEngine: NativeRouteEngine? = null
    private var tripEngine: NativeTripEngine? = null
    private var journeyRouter: NativeJourneyRouter? = null
    @Volatile private var routeLabel = "Все участки"
    @Volatile private var journeyId: String? = null
    @Volatile private var trainNumber: String? = null

    private fun updateSpeedCeiling() {
        motionFilter.setSpeedCeilingsKmh(
            RouteSpeedCeilings.maxKmh(routeLabel, trainNumber),
            RouteSpeedCeilings.trustedKmh(routeLabel, trainNumber))
    }

    private lateinit var mainHandler: Handler
    private var tripTicker: Runnable? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var vibrator: Vibrator? = null
    private var soundEnabled = true
    private var vibrationEnabled = true
    private val alertSpeech = mutableMapOf<String, String>()
    private val alertSpeed = mutableMapOf<String, Int>()
    private val alertReason = mutableMapOf<String, String>()
    private var lastAlertId: String? = null
    private var lastAlertInZone = false
    private val completedAlertIds = mutableSetOf<String>()
    private val warnedAlertIds = mutableSetOf<String>()
    private var lastZoneSampleAt = 0L
    private var frequentInterference = false
    private var lastSnapshotPersistAt = 0L
    private var lastSnapshotDiskAt = 0L
    private var lastTripPersistAt = 0L
    private var lastNotificationText = ""
    private var lastNotificationAt = 0L
    private lateinit var diagnostics: DiagnosticsLogger
    private var lastDiagnosticSampleAt = 0L
    private var lastDiagnosticQuality = ""
    private var lastRecoveryState: Boolean? = null
    private var lastTransitionProbeAt = 0L
    private var lastTransitionProbeKey = ""
    private var tripSessionId: String? = null
    private var tripStartedAt = 0L
    private var acceptedGpsSamples = 0L
    private var rejectedGpsSamples = 0L
    private var gpsOutageStartedAt = 0L
    private var gpsOutageCount = 0
    private var gpsOutageTotalMs = 0L
    private var longestGpsOutageMs = 0L
    private var directGpsStartCount = 0
    private var positionReconciliationCount = 0
    private var maxPositionCorrectionM = 0.0
    private var routeTransitionCount = 0
    private var maxBatteryTemperatureC: Double? = null
    private var lastEngineOutput: NativeTripEngine.Output? = null
    private var replayTrace = org.json.JSONArray()
    private var lastReplayTraceFlushAt = 0L

    private fun diagnosticContext() = mapOf(
        "trip_session_id" to tripSessionId, "route" to routeLabel, "journey" to journeyId,
        "direction" to tripEngine?.save()?.direction, "train" to trainNumber)

    private fun startTripSession() {
        finishTripSession("reconfigured")
        tripSessionId = "${System.currentTimeMillis()}-${SystemClock.elapsedRealtime()}"
        tripStartedAt = System.currentTimeMillis()
        acceptedGpsSamples = 0; rejectedGpsSamples = 0
        gpsOutageStartedAt = 0; gpsOutageCount = 0; gpsOutageTotalMs = 0; longestGpsOutageMs = 0
        directGpsStartCount = 0; positionReconciliationCount = 0; maxPositionCorrectionM = 0.0
        routeTransitionCount = 0; maxBatteryTemperatureC = null
        diagnostics.event("trip_session_started", diagnosticContext() + mapOf(
            "manual_official_m" to tripEngine?.save()?.manualOfficialM,
            "physical_m" to tripEngine?.save()?.physicalM))
    }

    private fun finishTripSession(reason: String) {
        val session = tripSessionId ?: return
        flushReplayTrace(true)
        val now = System.currentTimeMillis()
        if (gpsOutageStartedAt > 0L) {
            val duration = (now - gpsOutageStartedAt).coerceAtLeast(0)
            gpsOutageTotalMs += duration; longestGpsOutageMs = maxOf(longestGpsOutageMs, duration)
            gpsOutageStartedAt = 0
        }
        diagnostics.event("trip_session_summary", diagnosticContext() + mapOf(
            "trip_session_id" to session, "reason" to reason,
            "duration_ms" to (now - tripStartedAt).coerceAtLeast(0),
            "accepted_gps_samples" to acceptedGpsSamples, "rejected_gps_samples" to rejectedGpsSamples,
            "gps_outages" to gpsOutageCount, "gps_outage_total_ms" to gpsOutageTotalMs,
            "longest_gps_outage_ms" to longestGpsOutageMs, "direct_gps_starts" to directGpsStartCount,
            "position_reconciliations" to positionReconciliationCount,
            "max_position_correction_m" to maxPositionCorrectionM,
            "route_transitions" to routeTransitionCount,
            "max_battery_temperature_c" to maxBatteryTemperatureC,
            "final_physical_m" to tripEngine?.save()?.physicalM,
            "final_official_m" to lastEngineOutput?.officialM))
        tripSessionId = null
    }

    private fun markGpsOutage(cause: String, fields: Map<String, Any?> = emptyMap()) {
        if (tripSessionId == null || gpsOutageStartedAt > 0L) return
        gpsOutageStartedAt = System.currentTimeMillis(); gpsOutageCount++
        diagnostics.event("gps_outage_started", diagnosticContext() + fields + mapOf(
            "cause" to cause, "physical_m" to tripEngine?.save()?.physicalM,
            "official_m" to lastEngineOutput?.officialM))
    }

    private fun recoverGpsOutage(source: String) {
        if (gpsOutageStartedAt <= 0L) return
        val duration = (System.currentTimeMillis() - gpsOutageStartedAt).coerceAtLeast(0)
        gpsOutageTotalMs += duration; longestGpsOutageMs = maxOf(longestGpsOutageMs, duration)
        diagnostics.event("gps_outage_recovered", diagnosticContext() + mapOf(
            "duration_ms" to duration, "source" to source,
            "physical_m" to tripEngine?.save()?.physicalM,
            "official_m" to lastEngineOutput?.officialM))
        gpsOutageStartedAt = 0
    }

    private fun recordReplayGps(location: Location, result: NativeMotionFilter.Result,
        snap: NativeRouteEngine.Snap?, output: NativeTripEngine.Output?, fromDirectGps: Boolean) {
        if (tripSessionId == null) return
        replayTrace.put(org.json.JSONArray()
            .put("g").put(location.elapsedRealtimeNanos / 1_000_000)
            .put(location.latitude).put(location.longitude)
            .put(((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000).coerceAtLeast(0))
            .put(if (location.hasAccuracy()) location.accuracy else 999f)
            .put(if (location.hasSpeed()) location.speed else JSONObject.NULL)
            .put(if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else JSONObject.NULL)
            .put(location.isMock).put(satellitesUsed).put(averageCn0).put(gnssTelemetrySeen)
            .put(fromDirectGps).put(result.accepted)
            .put(result.filteredSpeedMps ?: JSONObject.NULL).put(result.stationary)
            .put(result.quality).put(result.reason)
            .put(snap?.physicalM ?: JSONObject.NULL).put(snap?.distanceM ?: JSONObject.NULL)
            .put(output?.physicalM ?: JSONObject.NULL).put(output?.officialM ?: JSONObject.NULL)
            .put(output?.source ?: JSONObject.NULL).put(output?.recovering ?: JSONObject.NULL)
            .put(routeLabel).put(tripEngine?.save()?.direction ?: JSONObject.NULL))
        flushReplayTrace(false)
    }

    private fun recordReplayTick(elapsedMs: Long, output: NativeTripEngine.Output) {
        if (tripSessionId == null) return
        replayTrace.put(org.json.JSONArray().put("t").put(elapsedMs)
            .put(output.physicalM ?: JSONObject.NULL).put(output.officialM ?: JSONObject.NULL)
            .put(output.speedMps).put(output.source).put(output.recovering)
            .put(output.alertId ?: JSONObject.NULL).put(output.alertDistanceM ?: JSONObject.NULL)
            .put(output.alertInZone).put(routeLabel).put(tripEngine?.save()?.direction ?: JSONObject.NULL))
        flushReplayTrace(false)
    }

    private fun flushReplayTrace(force: Boolean) {
        if (replayTrace.length() == 0) return
        val now = SystemClock.elapsedRealtime()
        if (!force && replayTrace.length() < 120 && now - lastReplayTraceFlushAt < 30_000L) return
        val batch = replayTrace
        replayTrace = org.json.JSONArray(); lastReplayTraceFlushAt = now
        diagnostics.event("replay_trace_batch", diagnosticContext() + mapOf(
            "schema" to 1, "records" to batch))
    }

    override fun onCreate() {
        super.onCreate()
        mainHandler = Handler(Looper.getMainLooper())
        DiagnosticsLogger.installCrashHandler(this)
        diagnostics = DiagnosticsLogger(this)
        diagnostics.event("service_started", mapOf("version" to packageManager.getPackageInfo(packageName, 0).versionName))
        createChannel()
        startAsForeground()

        runCatching {
            routeEngine = NativeRouteEngine.fromJson(readAsset("data/routes.json"))
            journeyRouter = NativeJourneyRouter.fromTimingJson(
                readAsset("data/timing.json"), readAsset("data/journeys.json"))
            tripEngine = NativeTripEngine(requireNotNull(routeEngine))
            restoreTripState()
            if (tripEngine?.save()?.active == true) startTripSession()
            startTripTicker()
        }.onFailure { routeEngine = null; journeyRouter = null; tripEngine = null }

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        else getSystemService(VIBRATOR_SERVICE) as? Vibrator
        initTts()
        startFusedLocation()
    }

    private fun startAsForeground() {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL_ID).setContentTitle("ПИКЕТ")
            .setContentText("Контроль ограничений активен")
            .setSmallIcon(R.drawable.ic_launcher_foreground).setContentIntent(pending)
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(1, notification)
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("ru", "RU")) ?: TextToSpeech.LANG_NOT_SUPPORTED
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
    }

    private fun speak(text: String) {
        if (ttsReady && text.isNotBlank()) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "piket_say")
    }

    private fun vibrate(kind: String) {
        val device = vibrator ?: return
        if (!device.hasVibrator()) return
        val pattern = if (kind == "danger") longArrayOf(0, 160, 80, 160, 80, 260)
        else longArrayOf(0, 120, 90, 120)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            device.vibrate(VibrationEffect.createWaveform(pattern, -1))
        else device.vibrate(pattern, -1)
    }

    private fun beep(kind: String) = runCatching {
        val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
        tone.startTone(if (kind == "danger") ToneGenerator.TONE_CDMA_PIP else ToneGenerator.TONE_PROP_BEEP,
            if (kind == "danger") 150 else 200)
        if (kind == "danger") mainHandler.postDelayed({
            val second = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
            second.startTone(ToneGenerator.TONE_CDMA_PIP, 150)
            mainHandler.postDelayed(second::release, 250)
        }, 280)
        mainHandler.postDelayed(tone::release, 300)
    }

    private fun locationRequest(priority: Int = Priority.PRIORITY_HIGH_ACCURACY, interval: Long = 1_000L) =
        LocationRequest.Builder(interval).setPriority(priority)
            .setMinUpdateIntervalMillis(if (priority == Priority.PRIORITY_HIGH_ACCURACY) 500 else 2_000)
            .setMaxUpdateAgeMillis(0).setMaxUpdateDelayMillis(0).setWaitForAccurateLocation(false).build()

    private fun startFusedLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        mainLocationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                if (isFreshRealFix(location)) lastFixReceivedAt = System.currentTimeMillis()
                processLocation(location, false)
            }
            override fun onLocationAvailability(value: LocationAvailability) {
                diagnostics.event("location_availability", diagnosticContext() + mapOf(
                    "available" to value.isLocationAvailable))
                // The watchdog confirms a real silence before changing the trip state.
            }
        }
        lastFixReceivedAt = System.currentTimeMillis()
        lastUsableFixAt = lastFixReceivedAt
        runCatching { fusedClient?.requestLocationUpdates(locationRequest(), mainLocationCallback!!, Looper.getMainLooper()) }
        startWatchdog()
        startGnssMonitor()
    }

    private fun startGnssMonitor() {
        locationManager = getSystemService(LOCATION_SERVICE) as? LocationManager
        if (locationManager == null || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        gnssCallback = object : GnssStatus.Callback() {
            override fun onStarted() { diagnostics.event("gnss_status", diagnosticContext() + mapOf("state" to "started")) }
            override fun onFirstFix(ttffMillis: Int) { diagnostics.event("gnss_first_fix", diagnosticContext() + mapOf("ttff_ms" to ttffMillis)) }
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                gnssTelemetrySeen = true
                var used = 0; var sum = 0f
                repeat(status.satelliteCount) { index ->
                    if (status.usedInFix(index)) { used++; sum += status.getCn0DbHz(index) }
                }
                satellitesUsed = used; averageCn0 = if (used > 0) sum / used else 0f
            }
            override fun onStopped() {
                satellitesUsed = 0; averageCn0 = 0f
                diagnostics.event("gnss_status", diagnosticContext() + mapOf("state" to "stopped"))
            }
        }
        runCatching { locationManager?.registerGnssStatusCallback(gnssCallback!!, mainHandler) }
    }

    private fun startWatchdog() {
        if (watchdog != null) return
        watchdog = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val silence = now - lastFixReceivedAt
                if (silence > 8_000 && !signalUnavailableMarked) {
                    signalUnavailableMarked = true
                    motionFilter.markSignalUnavailable(); tripEngine?.markSignalUnavailable()
                    markGpsOutage("location_silence", mapOf("silence_ms" to silence))
                    persistSnapshot(null, 999f)
                } else if (silence <= 3_000) signalUnavailableMarked = false
                if (silence > 10_000 && !networkBackupActive) startNetworkBackup()
                else if (silence <= 10_000 && networkBackupActive) stopNetworkBackup()
                val unusableFor = now - lastUsableFixAt
                if (!directGpsActive && unusableFor > 10_000 && now - lastDirectGpsStopAt > 30_000)
                    startDirectGps(now, silence, unusableFor)
                if (directGpsActive && fusedRecoveryFixes >= 3) stopDirectGps("fused-recovered")
                else if (directGpsActive && !directGpsExtendedLogged && now - directGpsStartedAt > 120_000) {
                    directGpsExtendedLogged = true
                    diagnostics.event("direct_gps_extended", diagnosticContext() + mapOf(
                        "active_ms" to (now - directGpsStartedAt),
                        "reason" to "primary-not-recovered"))
                }
                // Fresh network fixes can mask a stalled precise-GPS stream.
                // Bound retries to avoid continually restarting acquisition in interference.
                if ((silence > 15_000 || now - lastUsableFixAt > 30_000) && now - lastFusedRestartAt > 120_000) {
                    diagnostics.event("location_request_restarted", mapOf("silence_ms" to silence,
                        "unusable_ms" to now - lastUsableFixAt))
                    restartFused(now)
                }
                mainHandler.postDelayed(this, 5_000)
            }
        }.also { mainHandler.postDelayed(it, 5_000) }
    }

    private fun restartFused(now: Long) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            mainLocationCallback?.let { fusedClient?.removeLocationUpdates(it) }
            fusedClient = LocationServices.getFusedLocationProviderClient(this)
            mainLocationCallback?.let { fusedClient?.requestLocationUpdates(locationRequest(), it, Looper.getMainLooper()) }
            lastFusedRestartAt = now
        }
    }

    private fun startNetworkBackup() {
        if (networkBackupActive || fusedClient == null) return
        networkCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) { result.lastLocation?.let { processLocation(it, false) } }
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            fusedClient?.requestLocationUpdates(
                locationRequest(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 3_000), networkCallback!!, Looper.getMainLooper())
            networkBackupActive = true
            diagnostics.event("network_gps_reserve_started", diagnosticContext())
        }.onFailure { diagnostics.event("network_gps_reserve_error", diagnosticContext() + mapOf(
            "error" to it.javaClass.simpleName)) }
    }

    private fun stopNetworkBackup() {
        networkCallback?.let { runCatching { fusedClient?.removeLocationUpdates(it) } }
        networkCallback = null
        if (networkBackupActive) diagnostics.event("network_gps_reserve_stopped", diagnosticContext())
        networkBackupActive = false
    }

    private fun startDirectGps(now: Long, silence: Long, unusableFor: Long) {
        val manager = locationManager ?: (getSystemService(LOCATION_SERVICE) as? LocationManager) ?: return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            !manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) return
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = processLocation(location, true)
            override fun onProviderDisabled(provider: String) {
                diagnostics.event("location_provider_changed", diagnosticContext() + mapOf(
                    "provider" to provider, "enabled" to false))
            }
            override fun onProviderEnabled(provider: String) {
                diagnostics.event("location_provider_changed", diagnosticContext() + mapOf(
                    "provider" to provider, "enabled" to true))
            }
            @Deprecated("Deprecated in Android")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        runCatching {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper())
            directGpsListener = listener; directGpsActive = true; directGpsStartedAt = now
            fusedRecoveryFixes = 0; directGpsExtendedLogged = false; directGpsStartCount++
            diagnostics.event("direct_gps_started", diagnosticContext() + mapOf(
                "silence_ms" to silence, "unusable_ms" to unusableFor))
        }
    }

    private fun stopDirectGps(reason: String) {
        directGpsListener?.let { runCatching { locationManager?.removeUpdates(it) } }
        if (directGpsActive) diagnostics.event("direct_gps_stopped", diagnosticContext() + mapOf("reason" to reason,
            "active_ms" to (System.currentTimeMillis() - directGpsStartedAt).coerceAtLeast(0)))
        directGpsListener = null; directGpsActive = false
        lastDirectGpsStopAt = System.currentTimeMillis(); fusedRecoveryFixes = 0
        directGpsExtendedLogged = false
    }

    private fun isFreshRealFix(location: Location): Boolean {
        val age = ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000).coerceAtLeast(0)
        return age <= 5_000 && !location.isMock
    }

    private fun processLocation(location: Location, fromDirectGps: Boolean) {
        if (location.elapsedRealtimeNanos <= lastProcessedFixNanos) return
        lastProcessedFixNanos = location.elapsedRealtimeNanos
        val age = ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000).coerceAtLeast(0)
        val accuracy = if (location.hasAccuracy()) location.accuracy else 999f
        val result = motionFilter.process(NativeMotionFilter.Fix(
            location.latitude, location.longitude, location.elapsedRealtimeNanos / 1_000_000, age, accuracy,
            location.speed.takeIf { location.hasSpeed() },
            location.speedAccuracyMetersPerSecond.takeIf { location.hasSpeedAccuracy() },
            location.isMock, satellitesUsed, averageCn0, gnssTelemetrySeen))
        val wallNow = System.currentTimeMillis()
        if (result.accepted) acceptedGpsSamples++ else rejectedGpsSamples++
        if (result.accepted && result.quality in setOf("good", "stationary")) {
            recoverGpsOutage(if (fromDirectGps) "direct-gps" else location.provider ?: "fused")
            lastUsableFixAt = wallNow
            unusableFixMarked = false
            if (directGpsActive && !fromDirectGps) fusedRecoveryFixes++
        } else if (!unusableFixMarked && wallNow - lastUsableFixAt > 8_000) {
            // FusedLocation can keep sending fresh but useless fixes with an
            // accuracy radius of hundreds or thousands of metres. The old
            // watchdog treated those callbacks as a healthy signal.
            unusableFixMarked = true
            motionFilter.markSignalUnavailable()
            tripEngine?.markSignalUnavailable()
            markGpsOutage("unusable_fix", mapOf("accuracy_m" to accuracy,
                "quality" to result.quality, "reason" to result.reason))
            diagnostics.event("usable_gps_lost", diagnosticContext() + mapOf("accuracy_m" to accuracy,
                "quality" to result.quality, "reason" to result.reason))
        }
        val state = tripEngine?.save()
        var currentSnap = routeEngine?.snap(routeLabel, location.latitude, location.longitude, state?.direction)
        if (state != null && result.accepted && result.quality in setOf("good", "stationary")) {
            val next = journeyRouter?.nextLeg(journeyId, routeLabel, state.direction)
            val route = routeEngine?.route(routeLabel)
            val routeBoundary = route?.points?.takeIf { it.isNotEmpty() }?.let {
                if (state.direction == "obratno") it.first().physicalM else it.last().physicalM
            }
            val boundary = next?.boundaryM ?: routeBoundary
            val stoppedForJunction = result.stationary ||
                (result.filteredSpeedMps ?: state.speedMps) <= 1.5f
            val boundaryTolerance = next?.maxBoundaryOffsetM ?: 80.0
            val nearBoundary = boundary != null &&
                ((state.physicalM?.let { abs(it - boundary) <= maxOf(800.0, boundaryTolerance) } == true) ||
                    (currentSnap?.let { abs(it.physicalM - boundary) <= boundaryTolerance } == true))
            // Do not scan the entire next route on every fix hundreds of km from its junction.
            val nextSnap = if (nearBoundary && (next?.requireStop != true || stoppedForJunction)) next?.let {
                routeEngine?.snap(it.route, location.latitude, location.longitude, it.direction)
            } else null
            val transition = journeyRouter?.consider(journeyId, routeLabel, state.direction,
                state.physicalM, boundary, currentSnap?.distanceM, nextSnap?.distanceM,
                currentSnap?.physicalM, stoppedForJunction)
            if (nearBoundary && next != null) {
                val probeStatus = when {
                    next.requireStop && !stoppedForJunction -> "waiting_stop"
                    nextSnap == null -> "next_projection_unavailable"
                    transition != null -> "confirmed"
                    else -> "confirming_or_ambiguous"
                }
                recordTransitionProbe(next, probeStatus, boundary, state.physicalM,
                    currentSnap?.distanceM, nextSnap?.distanceM, stoppedForJunction)
            }
            if (transition != null && nextSnap != null) {
                routeTransitionCount++
                diagnostics.event("route_transition", diagnosticContext() + mapOf("from" to routeLabel,
                    "to" to transition.route, "direction" to transition.direction,
                    "physical_m" to nextSnap.physicalM, "official_m" to nextSnap.officialM))
                routeLabel = transition.route
                updateSpeedCeiling()
                tripEngine?.switchRoute(transition.route, transition.direction, nextSnap)
                currentSnap = nextSnap
            }
        }
        // Position and Doppler speed are independent measurements. Previously a
        // filtered speed also discarded an accurate on-track position, leaving
        // both speed and kilometre frozen until Stop/Start. NativeTripEngine has
        // its own two-fix recovery confirmation, so a good position remains safe.
        val positionAccepted = result.accepted && result.quality in setOf("good", "stationary")
        val engineSpeed = result.filteredSpeedMps
        val beforeUpdate = tripEngine?.save()
        // A fresh coarse fix near the selected route is good enough to remember where
        // the manual calibration was made. It is never shown as a trusted GPS fix; the
        // normal two-fix recovery still confirms the first precise position.
        val provisionalCalibrationFix = beforeUpdate?.physicalM == null && !positionAccepted &&
            isFreshRealFix(location) && accuracy in 1f..120f &&
            currentSnap?.distanceM?.let { it <= 120.0 } == true &&
            (satellitesUsed > 0 || !gnssTelemetrySeen)
        val fixElapsedMs = location.elapsedRealtimeNanos / 1_000_000
        val output = tripEngine?.update(NativeTripEngine.Input(fixElapsedMs, engineSpeed,
            positionAccepted, currentSnap, result.stationary, provisionalCalibrationFix))
        val lateCalibration = beforeUpdate?.takeIf {
            it.physicalM == null && it.calibrationWaitStartedElapsedMs > 0L &&
                fixElapsedMs - it.calibrationWaitStartedElapsedMs >= 30_000L
        }
        if (lateCalibration != null && output?.physicalM != null && positionAccepted &&
            !provisionalCalibrationFix) {
            diagnostics.event("calibration_anchor_recovered_late", diagnosticContext() + mapOf(
                "wait_ms" to (fixElapsedMs - lateCalibration.calibrationWaitStartedElapsedMs),
                "manual_official_m" to lateCalibration.manualOfficialM,
                "gps_physical_m" to currentSnap?.physicalM, "official_m" to output.officialM,
                "distance_to_route_m" to currentSnap?.distanceM))
        }
        if (provisionalCalibrationFix && beforeUpdate?.physicalM == null && output?.physicalM != null) {
            diagnostics.event("calibration_anchor_provisional", diagnosticContext() + mapOf(
                "accuracy_m" to accuracy, "satellites" to satellitesUsed,
                "distance_to_route_m" to currentSnap?.distanceM,
                "physical_m" to output.physicalM, "official_m" to output.officialM))
        }
        lastEngineOutput = output
        recordPositionRecovery(beforeUpdate, output, currentSnap, positionAccepted)
        tripEngine?.save()?.let(::persistTripState)
        output?.let {
            updateInterferenceMemory(it, result.quality in setOf("weak", "recovering", "rejected"))
        }
        persistSnapshot(output, accuracy); handleAlert(output)
        recordDiagnosticSample(location, result, currentSnap, output, fromDirectGps)
        recordReplayGps(location, result, currentSnap, output, fromDirectGps)
        output?.officialM?.let { official ->
            val text=NativePositionLabel.kmPk(official)
            val now=SystemClock.elapsedRealtime()
            if(text!=lastNotificationText||now-lastNotificationAt>=10_000){
                lastNotificationText=text;lastNotificationAt=now;updateNotificationText(this,text)
            }
        }
    }

    private fun recordPositionRecovery(before: NativeTripEngine.SavedState?, output: NativeTripEngine.Output?,
        snap: NativeRouteEngine.Snap?, positionAccepted: Boolean) {
        if (output == null) return
        val previous = lastRecoveryState
        if (previous == true && !output.recovering && positionAccepted && snap != null) {
            val beforePhysical = before?.physicalM
            val appliedPhysical = output.physicalM
            positionReconciliationCount++
            val correction = if (beforePhysical != null && appliedPhysical != null)
                appliedPhysical - beforePhysical else null
            maxPositionCorrectionM = maxOf(maxPositionCorrectionM, abs(correction ?: 0.0))
            diagnostics.event("position_reconciled", diagnosticContext() + mapOf(
                "route" to routeLabel, "direction" to before?.direction,
                "before_physical_m" to beforePhysical, "gps_physical_m" to snap.physicalM,
                "applied_physical_m" to appliedPhysical,
                "correction_m" to correction,
                "official_m" to output.officialM, "distance_to_route_m" to snap.distanceM,
                "source" to output.source))
        }
        lastRecoveryState = output.recovering
    }

    private fun recordTransitionProbe(next: NativeJourneyRouter.Transition, status: String,
        boundaryM: Double?, physicalM: Double?, currentDistanceM: Double?, nextDistanceM: Double?,
        stopped: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val key = "$routeLabel|${next.route}|$status|$stopped"
        if (key == lastTransitionProbeKey && now - lastTransitionProbeAt < 10_000L) return
        lastTransitionProbeKey = key; lastTransitionProbeAt = now
        diagnostics.event("route_transition_probe", diagnosticContext() + mapOf(
            "from" to routeLabel, "to" to next.route, "direction" to next.direction,
            "status" to status, "boundary_m" to boundaryM, "physical_m" to physicalM,
            "current_route_distance_m" to currentDistanceM,
            "next_route_distance_m" to nextDistanceM, "stopped" to stopped,
            "requires_stop" to next.requireStop))
    }
    private fun recordDiagnosticSample(location: Location, result: NativeMotionFilter.Result,
        snap: NativeRouteEngine.Snap?, output: NativeTripEngine.Output?, fromDirectGps: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val qualityChanged = result.quality != lastDiagnosticQuality
        if (!qualityChanged && now - lastDiagnosticSampleAt < 5_000) return
        lastDiagnosticSampleAt = now; lastDiagnosticQuality = result.quality
        diagnostics.event(if (qualityChanged) "gps_quality_changed" else "gps_sample", diagnosticContext() + mapOf(
            "route" to routeLabel, "journey" to journeyId, "train" to trainNumber,
            "direction" to tripEngine?.save()?.direction,
            "lat" to location.latitude, "lon" to location.longitude,
            "accuracy_m" to location.accuracy,
            "provider" to location.provider,
            "direct_gps_reserve" to fromDirectGps,
            "fix_elapsed_ms" to location.elapsedRealtimeNanos / 1_000_000,
            "fix_age_ms" to ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000).coerceAtLeast(0),
            "mock" to location.isMock, "gnss_telemetry" to gnssTelemetrySeen,
            "speed_accuracy_mps" to if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else null,
            "provider_speed_kmh" to if (location.hasSpeed()) location.speed * 3.6f else null,
            "bearing_deg" to if (location.hasBearing()) location.bearing else null,
            "filtered_speed_kmh" to result.filteredSpeedMps?.times(3.6f),
            "accepted" to result.accepted, "stationary" to result.stationary,
            "quality" to result.quality, "reason" to result.reason,
            "satellites" to satellitesUsed, "average_cn0" to averageCn0,
            "distance_to_route_m" to snap?.distanceM,
            "snapped_physical_m" to snap?.physicalM,
            "snap_minus_engine_m" to if (snap != null && output?.physicalM != null)
                snap.physicalM - output.physicalM else null,
            "engine_physical_m" to output?.physicalM,
            "official_m" to output?.officialM,
            "source" to output?.source, "recovering" to output?.recovering
        ))
    }

    private fun persistSnapshot(output: NativeTripEngine.Output?, accuracy: Float, force:Boolean=false) = runCatching {
        val now=SystemClock.elapsedRealtime();if(!force&&now-lastSnapshotPersistAt<750)return@runCatching
        lastSnapshotPersistAt=now
        val saved = tripEngine?.save()
        val json = JSONObject().put("active", output?.active ?: saved?.active ?: false)
            .put("route", saved?.route ?: routeLabel).put("direction", saved?.direction ?: "tuda")
            .put("speedKmh", (output?.speedMps ?: 0f) * 3.6f).put("recovering", output?.recovering ?: false)
            .put("source", output?.source ?: "unavailable").put("satellites", satellitesUsed)
            .put("averageCn0", averageCn0).put("accuracyM", accuracy)
            .put("directGpsReserve", directGpsActive)
            .put("alertInZone", output?.alertInZone ?: false)
            .put("frequentInterference", frequentInterference)
        output?.officialM?.let { json.put("officialM", it) }; output?.physicalM?.let { json.put("physicalM", it) }
        output?.alertId?.let { json.put("alertId", it) }; output?.alertDistanceM?.let { json.put("alertDistanceM", it) }
        val raw=json.toString();latestSnapshotJson=raw
        if(force||now-lastSnapshotDiskAt>=10_000){
            lastSnapshotDiskAt=now
            getSharedPreferences("piket_native", MODE_PRIVATE).edit().putString("snapshot",raw).apply()
        }
    }

    private fun persistTripState(state: NativeTripEngine.SavedState, force:Boolean=false) = runCatching {
        val now=SystemClock.elapsedRealtime();if(!force&&now-lastTripPersistAt<5_000)return@runCatching
        lastTripPersistAt=now
        val json = JSONObject().put("active", state.active).put("route", state.route)
            .put("direction", state.direction).put("manualOfficialM", state.manualOfficialM)
            .put("offsetM", state.offsetM).put("speedMps", state.speedMps)
            .put("calibrationWaitStartedElapsedMs", state.calibrationWaitStartedElapsedMs)
        state.physicalM?.let { json.put("physicalM", it) }
        journeyId?.let { json.put("journey", it) }
        trainNumber?.let { json.put("train", it) }
        getSharedPreferences("piket_native", MODE_PRIVATE).edit().putString("trip", json.toString()).apply()
    }

    private fun restoreTripState() = runCatching {
        val raw = getSharedPreferences("piket_native", MODE_PRIVATE).getString("trip", null) ?: return@runCatching
        val json = JSONObject(raw)
        journeyId = json.optString("journey").takeUnless { it.isBlank() || it == "null" }
        trainNumber = json.optString("train").takeUnless { it.isBlank() || it == "null" }
        routeLabel = json.optString("route", routeLabel)
        updateSpeedCeiling()
        val ceilingMps = RouteSpeedCeilings.trustedKmh(routeLabel, trainNumber) / 3.6f
        val savedSpeed = json.optDouble("speedMps").toFloat().takeIf { it.isFinite() && it in 0f..ceilingMps } ?: 0f
        tripEngine?.restore(NativeTripEngine.SavedState(json.optBoolean("active"), routeLabel,
            json.optString("direction", "tuda"), json.optDouble("manualOfficialM"),
            json.optDouble("physicalM").takeIf { json.has("physicalM") }, json.optDouble("offsetM"),
            savedSpeed, 0, json.optLong("calibrationWaitStartedElapsedMs", 0L)))
    }

    private fun applyConfig(raw: String?) {
        runCatching {
        val root = JSONObject(raw ?: return@runCatching)
        val previousState = tripEngine?.save()
        routeLabel = root.optString("route", "Все участки")
        journeyId = root.optString("journey").takeUnless { it.isBlank() || it == "null" }
        trainNumber = root.optString("train").takeUnless { it.isBlank() || it == "null" }
        updateSpeedCeiling()
        soundEnabled = root.optBoolean("sound", true); vibrationEnabled = root.optBoolean("vibration", true)
        alertSpeech.clear(); alertSpeed.clear(); alertReason.clear()
        val restrictionDiagnostics = org.json.JSONArray()
        val restrictions = buildList {
            val items = root.optJSONArray("restrictions") ?: return@buildList
            repeat(items.length()) { index ->
                val item = items.optJSONObject(index) ?: return@repeat
                val start = item.optDouble("km") * 1_000 + (item.optDouble("pk", 1.0) - 1.0).coerceIn(0.0, 9.0) * 100 + item.optDouble("m", 0.0)
                val end = if (item.has("kmE")) item.optDouble("kmE") * 1_000 + (item.optDouble("pkE", 1.0) - 1.0).coerceIn(0.0, 9.0) * 100 + item.optDouble("mE", 0.0) else start + 100.0
                val id = item.optString("id", index.toString())
                val startTrackHint = item.optDouble("trackStartM", Double.NaN).takeIf { it.isFinite() }
                val endTrackHint = item.optDouble("trackEndM", Double.NaN).takeIf { it.isFinite() }
                add(NativeTripEngine.Restriction(id, item.optString("peregon", "Все участки"),
                    item.optString("dir", "both"), start, end, root.optDouble("lead", 3_000.0),
                    startTrackHint, endTrackHint))
                val speed = item.optInt("speed")
                val reason = item.optString("reason", "Ограничение")
                alertSpeed[id] = speed; alertReason[id] = reason
                alertSpeech[id] = "$speed километров в час. $reason"
                restrictionDiagnostics.put(JSONObject().put("id", id).put("route", item.optString("peregon", "Все участки"))
                    .put("direction", item.optString("dir", "both")).put("start_m", start)
                    .put("end_m", end).put("speed_kmh", speed).put("reason", reason)
                    .put("track_start_m", startTrackHint ?: JSONObject.NULL)
                    .put("track_end_m", endTrackHint ?: JSONObject.NULL))
            }
        }
        val nextDirection = root.optString("direction", "tuda")
        val nextActive = root.optBoolean("active")
        if ((previousState?.active != true && nextActive) || previousState?.route != routeLabel ||
            previousState?.direction != nextDirection) {
            completedAlertIds.clear(); warnedAlertIds.clear()
            lastRecoveryState = null; lastTransitionProbeKey = ""; lastTransitionProbeAt = 0L
        }
        tripEngine?.configure(routeLabel, nextDirection, root.optDouble("manualOfficialM"),
            nextActive, restrictions)
        val startsNewSession = nextActive && (previousState?.active != true ||
            previousState?.route != routeLabel || previousState?.direction != nextDirection)
        if (startsNewSession) startTripSession() else if (!nextActive) finishTripSession("configured-inactive")
        diagnostics.event("trip_configured", diagnosticContext() + mapOf("route" to routeLabel, "journey" to journeyId,
            "train" to trainNumber, "speed_ceiling_kmh" to RouteSpeedCeilings.maxKmh(routeLabel, trainNumber),
            "trusted_speed_ceiling_kmh" to RouteSpeedCeilings.trustedKmh(routeLabel, trainNumber),
            "direction" to nextDirection, "active" to nextActive,
            "manual_official_m" to root.optDouble("manualOfficialM"),
            "lead_m" to root.optDouble("lead", 3_000.0), "sound" to soundEnabled,
            "vibration" to vibrationEnabled, "restrictions" to restrictions.size))
        diagnostics.event("restrictions_configured", diagnosticContext() + mapOf(
            "lead_m" to root.optDouble("lead", 3_000.0), "items" to restrictionDiagnostics))
        if (previousState?.manualOfficialM != null &&
            abs(previousState.manualOfficialM - root.optDouble("manualOfficialM")) >= 0.5)
            diagnostics.event("manual_calibration_changed", diagnosticContext() + mapOf(
                "from_official_m" to previousState.manualOfficialM,
                "to_official_m" to root.optDouble("manualOfficialM"),
                "delta_m" to root.optDouble("manualOfficialM") - previousState.manualOfficialM))
        }.onFailure { diagnostics.event("trip_config_error", diagnosticContext() + mapOf(
            "error" to it.javaClass.simpleName, "message" to it.message,
            "payload_length" to (raw?.length ?: 0))) }
    }

    private fun handleAlert(output: NativeTripEngine.Output?) {
        val id = output?.alertId
        if (id == null) { lastAlertId = null; lastAlertInZone = false; return }
        val completedKey = "$routeLabel|${tripEngine?.save()?.direction}|$id"
        if (completedKey in completedAlertIds) {
            lastAlertId = id; lastAlertInZone = output.alertInZone
            return
        }
        val entered = output.alertInZone && (id != lastAlertId || !lastAlertInZone)
        if (entered || (!output.alertInZone && warnedAlertIds.add(completedKey))) {
            diagnostics.event("restriction_alert", diagnosticContext() + mapOf(
                "id" to id, "route" to routeLabel, "speed_kmh" to alertSpeed[id],
                "reason" to alertReason[id], "distance_m" to output.alertDistanceM,
                "in_zone" to entered, "official_m" to output.officialM,
                "physical_m" to output.physicalM))
            val kind = if (entered) "danger" else "warning"
            val distance = output.alertDistanceM ?: 0.0
            val ahead = if (distance >= 1_000) String.format(Locale.forLanguageTag("ru"), "Через %.1f километра. ", distance / 1_000)
                else "Через ${distance.roundToInt()} метров. "
            val phrase = (if (entered) "Ограничение. " else ahead) + (alertSpeech[id] ?: "Ограничение")
            if (soundEnabled) { beep(kind); speak(phrase) }
            if (vibrationEnabled) vibrate(kind)
        }
        if (entered) completedAlertIds += completedKey
        lastAlertId = id; lastAlertInZone = output.alertInZone
    }

    private fun startTripTicker() {
        tripTicker = object : Runnable {
            override fun run() {
                recordPowerSample()
                tripEngine?.let { engine ->
                    val tickElapsed = SystemClock.elapsedRealtime()
                    val output = engine.update(NativeTripEngine.Input(tickElapsed, null, false, null))
                    lastEngineOutput = output
                    recordReplayTick(tickElapsed, output)
                    updateInterferenceMemory(output, output.recovering && System.currentTimeMillis() - lastFixReceivedAt > 5_000)
                    if (output.active) persistTripState(engine.save())
                    persistSnapshot(output, 999f); handleAlert(output)
                }
                mainHandler.postDelayed(this, 1_000)
            }
        }.also { mainHandler.postDelayed(it, 1_000) }
    }

    private fun recordPowerSample() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPowerSampleAt < 60_000L) return
        val cpu = android.os.Process.getElapsedCpuTime()
        val battery = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryTemperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            ?.takeIf { it >= 0 }?.div(10.0)
        if (batteryTemperature != null)
            maxBatteryTemperatureC = maxOf(maxBatteryTemperatureC ?: batteryTemperature, batteryTemperature)
        diagnostics.event("power_sample", diagnosticContext() + mapOf(
            "sample_ms" to if (lastPowerSampleAt == 0L) null else now - lastPowerSampleAt,
            "process_cpu_ms" to if (lastPowerSampleAt == 0L) null else cpu - lastProcessCpuMs,
            "battery_level" to battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            "battery_scale" to battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
            "battery_temperature_c" to batteryTemperature,
            "thermal_status" to if (Build.VERSION.SDK_INT >= 29)
                (getSystemService(POWER_SERVICE) as? PowerManager)?.currentThermalStatus else null,
            "direct_gps_reserve" to directGpsActive,
            "plugged" to battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)))
        lastPowerSampleAt = now; lastProcessCpuMs = cpu
    }

    private fun updateInterferenceMemory(output: NativeTripEngine.Output, bad: Boolean) {
        if (!output.active) { frequentInterference = false; return }
        val official = output.officialM ?: return
        if (output.speedMps * 3.6f < 30f) { frequentInterference = false; return }
        val now = System.currentTimeMillis()
        val prefs = getSharedPreferences("piket_native_zones", MODE_PRIVATE)
        if (now - lastZoneSampleAt >= 10_000) {
            lastZoneSampleAt = now
            val bucket = kotlin.math.floor(official / 1_000.0).toInt()
            val key = "${routeLabel}_$bucket"
            val total = prefs.getInt("${key}_total", 0) + 1
            val failures = prefs.getInt("${key}_bad", 0) + if (bad) 1 else 0
            prefs.edit().putInt("${key}_total", total).putInt("${key}_bad", failures).apply()
        }
        val sign = if (tripEngine?.save()?.direction == "obratno") -1 else 1
        val buckets = NativeInterferenceZones.currentAndAheadBuckets(official, sign)
        frequentInterference = buckets.any { bucket ->
            val key = "${routeLabel}_$bucket"
            val total = prefs.getInt("${key}_total", 0)
            NativeInterferenceZones.isFrequent(total, prefs.getInt("${key}_bad", 0))
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "ПИКЕТ", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Фоновый контроль ограничений скорости"
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun readAsset(path: String) = assets.open(path).bufferedReader().use { it.readText() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        diagnostics.event("service_command_received", diagnosticContext() + mapOf(
            "action" to intent?.action, "start_id" to startId, "flags" to flags))
        if (intent?.action == ACTION_CONFIGURE_NATIVE) applyConfig(intent.getStringExtra(EXTRA_NATIVE_CONFIG))
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onDestroy() {
        diagnostics.event("service_stopped", diagnosticContext() + mapOf("route" to routeLabel,
            "physical_m" to tripEngine?.save()?.physicalM,
            "manual_official_m" to tripEngine?.save()?.manualOfficialM))
        finishTripSession("service-stopped")
        tripTicker?.let(mainHandler::removeCallbacks); watchdog?.let(mainHandler::removeCallbacks)
        tripTicker = null; watchdog = null; stopNetworkBackup(); stopDirectGps("service-stopped")
        mainLocationCallback?.let { fusedClient?.removeLocationUpdates(it) }
        gnssCallback?.let { locationManager?.unregisterGnssStatusCallback(it) }
        tts?.stop(); tts?.shutdown(); tts = null
        tripEngine?.let { it.stop(); persistTripState(it.save(),true) }
        persistSnapshot(null, 999f,true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
