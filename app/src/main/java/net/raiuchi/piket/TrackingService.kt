package net.raiuchi.piket

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.GnssStatus
import android.location.Location
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
    private var lastUsableFixAt = 0L
    private var unusableFixMarked = false
    private var watchdog: Runnable? = null
    private var lastPowerSampleAt = 0L
    private var lastProcessCpuMs = 0L

    private var locationManager: LocationManager? = null
    private var gnssCallback: GnssStatus.Callback? = null
    @Volatile private var satellitesUsed = 0
    @Volatile private var averageCn0 = 0f
    @Volatile private var gnssTelemetrySeen = false

    private val motionFilter = NativeMotionFilter()
    private var routeEngine: NativeRouteEngine? = null
    private var tripEngine: NativeTripEngine? = null
    private var journeyRouter: NativeJourneyRouter? = null
    @Volatile private var routeLabel = "Все участки"
    @Volatile private var journeyId: String? = null

    private lateinit var mainHandler: Handler
    private var tripTicker: Runnable? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var vibrator: Vibrator? = null
    private var soundEnabled = true
    private var vibrationEnabled = true
    private val alertSpeech = mutableMapOf<String, String>()
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

    override fun onCreate() {
        super.onCreate()
        mainHandler = Handler(Looper.getMainLooper())
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
                processLocation(location)
            }
            override fun onLocationAvailability(value: LocationAvailability) {
                // Android may toggle this flag for a fraction of a second even while
                // fixes continue. The watchdog below confirms a real silence first.
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
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                gnssTelemetrySeen = true
                var used = 0; var sum = 0f
                repeat(status.satelliteCount) { index ->
                    if (status.usedInFix(index)) { used++; sum += status.getCn0DbHz(index) }
                }
                satellitesUsed = used; averageCn0 = if (used > 0) sum / used else 0f
            }
            override fun onStopped() { satellitesUsed = 0; averageCn0 = 0f }
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
                    persistSnapshot(null, 999f)
                } else if (silence <= 3_000) signalUnavailableMarked = false
                if (silence > 10_000 && !networkBackupActive) startNetworkBackup()
                else if (silence <= 10_000 && networkBackupActive) stopNetworkBackup()
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
            override fun onLocationResult(result: LocationResult) { result.lastLocation?.let(::processLocation) }
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            fusedClient?.requestLocationUpdates(
                locationRequest(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 3_000), networkCallback!!, Looper.getMainLooper())
            networkBackupActive = true
        }
    }

    private fun stopNetworkBackup() {
        networkCallback?.let { runCatching { fusedClient?.removeLocationUpdates(it) } }
        networkCallback = null; networkBackupActive = false
    }

    private fun isFreshRealFix(location: Location): Boolean {
        val age = ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000).coerceAtLeast(0)
        return age <= 5_000 && !location.isMock
    }

    private fun processLocation(location: Location) {
        val age = ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000).coerceAtLeast(0)
        val accuracy = if (location.hasAccuracy()) location.accuracy else 999f
        val result = motionFilter.process(NativeMotionFilter.Fix(
            location.latitude, location.longitude, location.elapsedRealtimeNanos / 1_000_000, age, accuracy,
            location.speed.takeIf { location.hasSpeed() },
            location.speedAccuracyMetersPerSecond.takeIf { location.hasSpeedAccuracy() },
            location.isMock, satellitesUsed, averageCn0, gnssTelemetrySeen))
        val wallNow = System.currentTimeMillis()
        if (result.accepted && result.quality in setOf("good", "stationary")) {
            lastUsableFixAt = wallNow
            unusableFixMarked = false
        } else if (!unusableFixMarked && wallNow - lastUsableFixAt > 8_000) {
            // FusedLocation can keep sending fresh but useless fixes with an
            // accuracy radius of hundreds or thousands of metres. The old
            // watchdog treated those callbacks as a healthy signal.
            unusableFixMarked = true
            motionFilter.markSignalUnavailable()
            tripEngine?.markSignalUnavailable()
            diagnostics.event("usable_gps_lost", mapOf("accuracy_m" to accuracy,
                "quality" to result.quality, "reason" to result.reason))
        }
        var currentSnap = routeEngine?.snap(routeLabel, location.latitude, location.longitude)
        val state = tripEngine?.save()
        if (state != null && result.accepted && result.quality in setOf("good", "stationary")) {
            val next = journeyRouter?.nextLeg(journeyId, routeLabel, state.direction)
            val route = routeEngine?.route(routeLabel)
            val boundary = route?.points?.takeIf { it.isNotEmpty() }?.let {
                if (state.direction == "obratno") it.first().physicalM else it.last().physicalM
            }
            val nearBoundary = boundary != null &&
                ((state.physicalM?.let { abs(it - boundary) <= 800.0 } == true) ||
                    (currentSnap?.let { abs(it.physicalM - boundary) <= 80.0 } == true))
            // Do not scan the entire next route on every fix hundreds of km from its junction.
            val nextSnap = if (nearBoundary) next?.let { routeEngine?.snap(it.route, location.latitude, location.longitude) } else null
            val transition = journeyRouter?.consider(journeyId, routeLabel, state.direction,
                state.physicalM, boundary, currentSnap?.distanceM, nextSnap?.distanceM, currentSnap?.physicalM)
            if (transition != null && nextSnap != null) {
                diagnostics.event("route_transition", mapOf("from" to routeLabel,
                    "to" to transition.route, "direction" to transition.direction,
                    "physical_m" to nextSnap.physicalM, "official_m" to nextSnap.officialM))
                routeLabel = transition.route
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
        val output = tripEngine?.update(NativeTripEngine.Input(location.elapsedRealtimeNanos / 1_000_000,
            engineSpeed, positionAccepted, currentSnap, result.stationary))
        tripEngine?.save()?.let(::persistTripState)
        output?.let {
            updateInterferenceMemory(it, result.quality in setOf("weak", "recovering", "rejected"))
        }
        persistSnapshot(output, accuracy); handleAlert(output)
        recordDiagnosticSample(location, result, currentSnap, output)
        output?.officialM?.let { official ->
            val text=NativePositionLabel.kmPk(official)
            val now=SystemClock.elapsedRealtime()
            if(text!=lastNotificationText||now-lastNotificationAt>=10_000){
                lastNotificationText=text;lastNotificationAt=now;updateNotificationText(this,text)
            }
        }
    }

    private fun recordDiagnosticSample(location: Location, result: NativeMotionFilter.Result,
        snap: NativeRouteEngine.Snap?, output: NativeTripEngine.Output?) {
        val now = SystemClock.elapsedRealtime()
        val qualityChanged = result.quality != lastDiagnosticQuality
        if (!qualityChanged && now - lastDiagnosticSampleAt < 5_000) return
        lastDiagnosticSampleAt = now; lastDiagnosticQuality = result.quality
        diagnostics.event(if (qualityChanged) "gps_quality_changed" else "gps_sample", mapOf(
            "route" to routeLabel, "journey" to journeyId,
            "direction" to tripEngine?.save()?.direction,
            "lat" to location.latitude, "lon" to location.longitude,
            "accuracy_m" to location.accuracy,
            "provider" to location.provider,
            "fix_age_ms" to ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000).coerceAtLeast(0),
            "speed_accuracy_mps" to if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else null,
            "provider_speed_kmh" to if (location.hasSpeed()) location.speed * 3.6f else null,
            "filtered_speed_kmh" to result.filteredSpeedMps?.times(3.6f),
            "accepted" to result.accepted, "stationary" to result.stationary,
            "quality" to result.quality, "reason" to result.reason,
            "satellites" to satellitesUsed, "average_cn0" to averageCn0,
            "distance_to_route_m" to snap?.distanceM,
            "snapped_physical_m" to snap?.physicalM,
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
        state.physicalM?.let { json.put("physicalM", it) }
        journeyId?.let { json.put("journey", it) }
        getSharedPreferences("piket_native", MODE_PRIVATE).edit().putString("trip", json.toString()).apply()
    }

    private fun restoreTripState() = runCatching {
        val raw = getSharedPreferences("piket_native", MODE_PRIVATE).getString("trip", null) ?: return@runCatching
        val json = JSONObject(raw); journeyId = json.optString("journey").ifBlank { null }
        tripEngine?.restore(NativeTripEngine.SavedState(json.optBoolean("active"), json.optString("route", "Все участки"),
            json.optString("direction", "tuda"), json.optDouble("manualOfficialM"),
            json.optDouble("physicalM").takeIf { json.has("physicalM") }, json.optDouble("offsetM"),
            json.optDouble("speedMps").toFloat(), 0))
        routeLabel = json.optString("route", routeLabel)
    }

    private fun applyConfig(raw: String?) = runCatching {
        val root = JSONObject(raw ?: return@runCatching)
        val previousState = tripEngine?.save()
        routeLabel = root.optString("route", "Все участки")
        journeyId = root.optString("journey").takeUnless { it.isBlank() || it == "null" }
        soundEnabled = root.optBoolean("sound", true); vibrationEnabled = root.optBoolean("vibration", true)
        alertSpeech.clear()
        val restrictions = buildList {
            val items = root.optJSONArray("restrictions") ?: return@buildList
            repeat(items.length()) { index ->
                val item = items.optJSONObject(index) ?: return@repeat
                val start = item.optDouble("km") * 1_000 + (item.optDouble("pk", 1.0) - 1.0).coerceIn(0.0, 9.0) * 100 + item.optDouble("m", 0.0)
                val end = if (item.has("kmE")) item.optDouble("kmE") * 1_000 + (item.optDouble("pkE", 1.0) - 1.0).coerceIn(0.0, 9.0) * 100 + item.optDouble("mE", 0.0) else start + 100.0
                val id = item.optString("id", index.toString())
                add(NativeTripEngine.Restriction(id, item.optString("peregon", "Все участки"),
                    item.optString("dir", "both"), start, end, root.optDouble("lead", 3_000.0)))
                alertSpeech[id] = "${item.optInt("speed")} километров в час. ${item.optString("reason", "Ограничение")}" 
            }
        }
        val nextDirection = root.optString("direction", "tuda")
        val nextActive = root.optBoolean("active")
        if ((previousState?.active != true && nextActive) || previousState?.route != routeLabel ||
            previousState?.direction != nextDirection) { completedAlertIds.clear(); warnedAlertIds.clear() }
        tripEngine?.configure(routeLabel, nextDirection, root.optDouble("manualOfficialM"),
            nextActive, restrictions)
        diagnostics.event("trip_configured", mapOf("route" to routeLabel, "journey" to journeyId,
            "direction" to nextDirection, "active" to nextActive,
            "manual_official_m" to root.optDouble("manualOfficialM"), "restrictions" to restrictions.size))
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
            diagnostics.event("restriction_alert", mapOf("id" to id, "route" to routeLabel,
                "distance_m" to output.alertDistanceM, "in_zone" to entered,
                "official_m" to output.officialM))
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
                    val output = engine.update(NativeTripEngine.Input(SystemClock.elapsedRealtime(), null, false, null))
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
        diagnostics.event("power_sample", mapOf(
            "sample_ms" to if (lastPowerSampleAt == 0L) null else now - lastPowerSampleAt,
            "process_cpu_ms" to if (lastPowerSampleAt == 0L) null else cpu - lastProcessCpuMs,
            "battery_level" to battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            "battery_scale" to battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
            "battery_temperature_c" to battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it >= 0 }?.div(10.0),
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
        if (intent?.action == ACTION_CONFIGURE_NATIVE) applyConfig(intent.getStringExtra(EXTRA_NATIVE_CONFIG))
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onDestroy() {
        diagnostics.event("service_stopped", mapOf("route" to routeLabel,
            "physical_m" to tripEngine?.save()?.physicalM,
            "manual_official_m" to tripEngine?.save()?.manualOfficialM))
        tripTicker?.let(mainHandler::removeCallbacks); watchdog?.let(mainHandler::removeCallbacks)
        tripTicker = null; watchdog = null; stopNetworkBackup()
        mainLocationCallback?.let { fusedClient?.removeLocationUpdates(it) }
        gnssCallback?.let { locationManager?.unregisterGnssStatusCallback(it) }
        tts?.stop(); tts?.shutdown(); tts = null
        tripEngine?.let { it.stop(); persistTripState(it.save(),true) }
        persistSnapshot(null, 999f,true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
