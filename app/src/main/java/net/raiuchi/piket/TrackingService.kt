package net.raiuchi.piket

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.*
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
import kotlin.math.sqrt

/**
 * Полностью нативная фоновая служба. Получение координат, фильтрация движения,
 * переходы маршрутных осей, счисление при потере сигнала и ограничения не зависят от UI.
 */
@Suppress("DEPRECATION")
class TrackingService : Service() {
    companion object {
        private const val CHANNEL_ID = "piket_tracking"
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

    private var wakeLock: PowerManager.WakeLock? = null
    private var fusedClient: FusedLocationProviderClient? = null
    private var mainLocationCallback: LocationCallback? = null
    private var networkCallback: LocationCallback? = null
    private var networkBackupActive = false
    private var lastFixReceivedAt = 0L
    private var lastFusedRestartAt = 0L
    private var watchdog: Runnable? = null

    private var locationManager: LocationManager? = null
    private var gnssCallback: GnssStatus.Callback? = null
    @Volatile private var satellitesUsed = 0
    @Volatile private var averageCn0 = 0f
    @Volatile private var gnssTelemetrySeen = false

    private var sensorManager: SensorManager? = null
    private var accelListener: SensorEventListener? = null
    private var accelMagnitude = 0f

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

    override fun onCreate() {
        super.onCreate()
        mainHandler = Handler(Looper.getMainLooper())
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

        (getSystemService(POWER_SERVICE) as? PowerManager)?.let { manager ->
            wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "piket:tracking").apply { acquire() }
        }
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        else getSystemService(VIBRATOR_SERVICE) as? Vibrator
        initTts()
        startFusedLocation()
        initAccelerometer()
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
                if (!value.isLocationAvailable) {
                    motionFilter.markSignalUnavailable(); tripEngine?.markSignalUnavailable()
                    persistSnapshot(null, 999f)
                }
            }
        }
        lastFixReceivedAt = System.currentTimeMillis()
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
                if (silence > 10_000 && !networkBackupActive) startNetworkBackup()
                else if (silence <= 10_000 && networkBackupActive) stopNetworkBackup()
                if (silence > 15_000 && now - lastFusedRestartAt > 15_000) restartFused(now)
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
        var currentSnap = routeEngine?.snap(routeLabel, location.latitude, location.longitude)
        val state = tripEngine?.save()
        if (state != null) {
            val next = journeyRouter?.nextLeg(journeyId, routeLabel, state.direction)
            val nextSnap = next?.let { routeEngine?.snap(it.route, location.latitude, location.longitude) }
            val route = routeEngine?.route(routeLabel)
            val boundary = route?.points?.takeIf { it.isNotEmpty() }?.let {
                if (state.direction == "obratno") it.first().physicalM else it.last().physicalM
            }
            val transition = journeyRouter?.consider(journeyId, routeLabel, state.direction,
                state.physicalM, boundary, currentSnap?.distanceM, nextSnap?.distanceM, currentSnap?.physicalM)
            if (transition != null && nextSnap != null) {
                routeLabel = transition.route
                tripEngine?.switchRoute(transition.route, transition.direction, nextSnap)
                currentSnap = nextSnap
            }
        }
        val output = tripEngine?.update(NativeTripEngine.Input(location.elapsedRealtimeNanos / 1_000_000,
            result.filteredSpeedMps, result.accepted, currentSnap))
        tripEngine?.save()?.let(::persistTripState)
        persistSnapshot(output, accuracy); handleAlert(output)
        output?.officialM?.let { official ->
            val value = official.roundToInt()
            updateNotificationText(this, "${value / 1000} км ${(value % 1000) / 100} пк")
        }
    }

    private fun persistSnapshot(output: NativeTripEngine.Output?, accuracy: Float) = runCatching {
        val saved = tripEngine?.save()
        val json = JSONObject().put("active", output?.active ?: saved?.active ?: false)
            .put("route", saved?.route ?: routeLabel).put("direction", saved?.direction ?: "tuda")
            .put("speedKmh", (output?.speedMps ?: 0f) * 3.6f).put("recovering", output?.recovering ?: false)
            .put("source", output?.source ?: "unavailable").put("satellites", satellitesUsed)
            .put("averageCn0", averageCn0).put("accuracyM", accuracy)
            .put("alertInZone", output?.alertInZone ?: false)
        output?.officialM?.let { json.put("officialM", it) }; output?.physicalM?.let { json.put("physicalM", it) }
        output?.alertId?.let { json.put("alertId", it) }; output?.alertDistanceM?.let { json.put("alertDistanceM", it) }
        getSharedPreferences("piket_native", MODE_PRIVATE).edit().putString("snapshot", json.toString()).apply()
    }

    private fun persistTripState(state: NativeTripEngine.SavedState) = runCatching {
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
        routeLabel = root.optString("route", "Все участки")
        journeyId = root.optString("journey").ifBlank { null }
        soundEnabled = root.optBoolean("sound", true); vibrationEnabled = root.optBoolean("vibration", true)
        alertSpeech.clear()
        val restrictions = buildList {
            val items = root.optJSONArray("restrictions") ?: return@buildList
            repeat(items.length()) { index ->
                val item = items.optJSONObject(index) ?: return@repeat
                val start = item.optDouble("km") * 1_000 + item.optDouble("pk") * 100 + item.optDouble("m")
                val end = if (item.has("kmE")) item.optDouble("kmE") * 1_000 + item.optDouble("pkE") * 100 + item.optDouble("mE") else start
                val id = item.optString("id", index.toString())
                add(NativeTripEngine.Restriction(id, item.optString("peregon", "Все участки"),
                    item.optString("dir", "both"), start, end, item.optDouble("lead", root.optDouble("lead", 3_000.0))))
                alertSpeech[id] = "${item.optInt("speed")} километров в час. ${item.optString("reason", "Ограничение")}" 
            }
        }
        tripEngine?.configure(routeLabel, root.optString("direction", "tuda"), root.optDouble("manualOfficialM"),
            root.optBoolean("active"), restrictions)
    }

    private fun handleAlert(output: NativeTripEngine.Output?) {
        val id = output?.alertId
        if (id == null) { lastAlertId = null; lastAlertInZone = false; return }
        val entered = output.alertInZone && (id != lastAlertId || !lastAlertInZone)
        if (id != lastAlertId || entered) {
            val kind = if (entered) "danger" else "warning"
            val phrase = (if (entered) "Ограничение. " else "Впереди ограничение. ") + (alertSpeech[id] ?: "Ограничение")
            if (soundEnabled) { beep(kind); speak(phrase) }
            if (vibrationEnabled) vibrate(kind)
        }
        lastAlertId = id; lastAlertInZone = output.alertInZone
    }

    private fun startTripTicker() {
        tripTicker = object : Runnable {
            override fun run() {
                tripEngine?.let { engine ->
                    val output = engine.update(NativeTripEngine.Input(SystemClock.elapsedRealtime(), null, false, null))
                    if (output.active) persistTripState(engine.save())
                    persistSnapshot(output, 999f); handleAlert(output)
                }
                mainHandler.postDelayed(this, 1_000)
            }
        }.also { mainHandler.postDelayed(it, 1_000) }
    }

    private fun initAccelerometer() {
        sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) ?: return
        accelListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val magnitude = sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2])
                accelMagnitude = accelMagnitude * .7f + magnitude * .3f
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }.also { sensorManager?.registerListener(it, sensor, SensorManager.SENSOR_DELAY_NORMAL) }
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
        tripTicker?.let(mainHandler::removeCallbacks); watchdog?.let(mainHandler::removeCallbacks)
        tripTicker = null; watchdog = null; stopNetworkBackup()
        mainLocationCallback?.let { fusedClient?.removeLocationUpdates(it) }
        accelListener?.let { sensorManager?.unregisterListener(it) }
        gnssCallback?.let { locationManager?.unregisterGnssStatusCallback(it) }
        wakeLock?.takeIf { it.isHeld }?.release()
        tts?.stop(); tts?.shutdown(); tts = null
        tripEngine?.let { it.stop(); persistTripState(it.save()) }
        persistSnapshot(null, 999f)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
