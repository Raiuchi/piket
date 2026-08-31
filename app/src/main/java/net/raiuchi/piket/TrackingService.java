package net.raiuchi.piket;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.GnssStatus;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.util.Locale;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Фоновая служба трекинга. Держит СВОЙ собственный headless WebView (без экрана) —
 * вся JS-логика (счисление позиции, проверка ограничений, решение о голосе/вибро)
 * продолжает работать здесь независимо от того, жива ли MainActivity и горит ли экран.
 *
 * MainActivity, когда открыта, просто отображает зеркало того же WebView — но
 * источник правды по позиции и предупреждениям один: этот headless движок.
 */
public class TrackingService extends Service {

    private static final String CHANNEL_ID = "piket_tracking";
    public static final String ACTION_RECALIBRATE = "net.raiuchi.piket.ACTION_RECALIBRATE";
    public static final String ACTION_CONFIGURE_NATIVE = "net.raiuchi.piket.ACTION_CONFIGURE_NATIVE";
    public static final String EXTRA_NATIVE_CONFIG = "net.raiuchi.piket.extra.NATIVE_CONFIG";

    private PowerManager.WakeLock wakeLock;
    private FusedLocationProviderClient fusedClient;
    private LocationCallback networkBackupCallback;
    private boolean networkBackupActive = false;
    private SensorManager sensorManager;
    private Sensor accelSensor;
    private SensorEventListener accelListener;
    private float accelMag = 0f; // та же логика, что в JS: модуль вектора ускорения, сглаженный
    private LocationCallback locationCallback;
    private long lastFixReceivedAt = 0;
    private long lastFusedRestartAt = 0;
    private long locationUnavailableSince = 0;
    private Runnable locationWatchdogRunnable;
    private LocationManager locationManager;
    private GnssStatus.Callback gnssStatusCallback;
    private volatile int gnssSatellitesUsed = 0;
    private volatile float gnssAverageCn0 = 0f;
    private volatile boolean gnssTelemetrySeen = false;
    private volatile int gnssConstellationDiversity = 0;
    private final NativeMotionFilter nativeMotionFilter = new NativeMotionFilter();
    private NativeRouteEngine nativeRouteEngine;
    private NativeTripEngine nativeTripEngine;
    private volatile String nativeRouteLabel = "Все участки";

    private WebView headlessWeb;
    private boolean headlessPageReady = false;
    private boolean headlessRestartPending = false;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private Vibrator vibrator;
    private Handler mainHandler;
    private Runnable nativeTripTicker;

    public static void updateNotificationText(Context ctx, String text) {
        Intent open = new Intent(ctx, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                ctx, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(ctx, CHANNEL_ID)
                .setContentTitle("ПИКЕТ · " + text)
                .setContentText("Контроль ограничений активен")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(1, notification);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());

        createChannel();

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("ПИКЕТ")
                .setContentText("Контроль ограничений активен")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(1, notification);
        }

        // Android требует вызвать startForeground немедленно. Разбор 1558 маршрутных
        // точек выполняем только после него, иначе холодный/повторный запуск службы может
        // получить ForegroundServiceDidNotStartInTimeException на медленном устройстве.
        try {
            nativeRouteEngine = NativeRouteEngine.Companion.fromCoreJs(readAsset("assets/piket-core.js"));
            nativeTripEngine = new NativeTripEngine(nativeRouteEngine);
            restoreNativeTripState();
            startNativeTripTicker();
        } catch (Exception ignored) {
            nativeRouteEngine = null; // JS остаётся рабочим источником истины.
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "piket:tracking");
            wakeLock.acquire();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = vm != null ? vm.getDefaultVibrator() : null;
        } else {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }

        initTts();
        initHeadlessWebView();
        startFusedLocation();
        initAccelHelper();
    }

    /** Headless WebView — та же страница, тот же JS-движок, но без экрана. Живёт пока жива служба. */
    private void initHeadlessWebView() {
        headlessWeb = new WebView(this);
        WebSettings s = headlessWeb.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        // КРИТИЧНЫЙ ФИКС: при первом запуске службы (после установки или перезагрузки
        // телефона) onCreate() -> onStartCommand() выполняются практически сразу друг за
        // другом, но loadUrl() асинхронный - страница может физически не успеть загрузиться
        // к моменту, когда onStartCommand вызывает forceHeadlessStart(). evaluateJavascript
        // на ещё не загруженной странице просто молча ничего не делает - команда терялась
        // без какой-либо ошибки, счётчик не двигался именно в первую поездку после установки.
        // Теперь явно ждём onPageFinished и держим "отложенную команду", если Старт был
        // нажат раньше, чем страница успела прогрузиться.
        headlessWeb.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                headlessPageReady = true;
                if (headlessRestartPending) {
                    headlessRestartPending = false;
                    forceHeadlessStart();
                }
            }
        });
        headlessWeb.addJavascriptInterface(new PiketBridge(), "Android");
        headlessWeb.loadUrl("file:///android_asset/index.html");
    }

    /** Тот же мост, что раньше был у MainActivity — теперь живёт здесь, в службе */
    public class PiketBridge {
        @JavascriptInterface
        public void setNativeRouteContext(String routeLabel) {
            nativeRouteLabel = routeLabel != null ? routeLabel : "Все участки";
        }

        @JavascriptInterface
        public void configureNativeTrip(String json) {
            applyNativeTripConfig(json);
        }

        @JavascriptInterface
        public void updatePosition(final String text) {
            updateNotificationText(TrackingService.this, text);
        }

        @JavascriptInterface
        public void startTracking() {
            // Этот bridge принадлежит ТОЛЬКО headless-копии (см. addJavascriptInterface выше) -
            // нажатие "Старт" на видимом экране сюда не попадает, оно идёт в ДРУГОЙ PiketBridge,
            // определённый в MainActivity.java. Реальный путь команды от кнопки "Старт" до
            // headless-копии - через onStartCommand() этой службы, см. forceHeadlessStart().
            // Этот метод остаётся как запасной путь, если JS headless-страницы когда-либо сам
            // вызовет window.Android.startTracking() из своего кода.

            forceHeadlessStart();
        }

        @JavascriptInterface
        public void stopTracking() {
            mainHandler.post(new Runnable() {
                @Override public void run() { stopSelf(); }
            });
        }

        @JavascriptInterface
        public void openUrl(final String url) {
            // открытие ссылок не имеет смысла без экрана — игнорируем здесь,
            // в видимой Activity это всё равно работает через её собственный bridge
        }

        @JavascriptInterface
        public String getAppVersion() {
            try {
                PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                return pi.versionName != null ? pi.versionName : "0.0.0";
            } catch (Exception e) {
                return "0.0.0";
            }
        }

        @JavascriptInterface
        public void speak(final String text) {
            mainHandler.post(new Runnable() {
                @Override public void run() { speakNative(text); }
            });
        }

        @JavascriptInterface
        public void vibrate(final String kind) {
            mainHandler.post(new Runnable() {
                @Override public void run() { vibrateNative(kind); }
            });
        }

        @JavascriptInterface
        public void beep(final String kind) {
            mainHandler.post(new Runnable() {
                @Override public void run() { beepNative(kind); }
            });
        }

        @JavascriptInterface
        public boolean isTtsReady() {
            return ttsReady;
        }

        @JavascriptInterface
        public boolean isHeadless() {
            return true;
        }
    }

    private void initTts() {
        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    int res = tts.setLanguage(new Locale("ru", "RU"));
                    ttsReady = (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED);
                }
            }
        });
    }

    private void speakNative(String text) {
        if (tts == null || !ttsReady || text == null || text.isEmpty()) return;
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "piket_say");
    }

    /** Нативная вибрация — гарантированно работает в фоне, в отличие от navigator.vibrate()
     * из JS, который может не сработать в скрытом (headless) WebView. pattern — массив
     * длительностей в мс, как в браузерном Vibration API (даже индексы — паузы). */
    private void vibrateNative(String kind) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        long[] timings = "danger".equals(kind)
                ? new long[]{0, 160, 80, 160, 80, 260}
                : new long[]{0, 120, 90, 120};
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(timings, -1));
        } else {
            vibrator.vibrate(timings, -1);
        }
    }

    /** Нативный звуковой сигнал через ToneGenerator - в отличие от Web Audio API (AudioContext)
     *  в JS, это надёжно работает в headless-службе (фоновый WebView без видимого окна не
     *  всегда подключён к аудио-выходу системы так же надёжно, как обычная Activity). */
    private void beepNative(String kind) {
        try {
            android.media.ToneGenerator tg = new android.media.ToneGenerator(
                    android.media.AudioManager.STREAM_NOTIFICATION, 90);
            if ("danger".equals(kind)) {
                tg.startTone(android.media.ToneGenerator.TONE_CDMA_PIP, 150);
                mainHandler.postDelayed(() -> {
                    android.media.ToneGenerator tg2 = new android.media.ToneGenerator(
                            android.media.AudioManager.STREAM_NOTIFICATION, 90);
                    tg2.startTone(android.media.ToneGenerator.TONE_CDMA_PIP, 150);
                    mainHandler.postDelayed(tg2::release, 250);
                }, 280);
            } else {
                tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 200);
            }
            mainHandler.postDelayed(tg::release, 300);
        } catch (Exception e) {
            // если тональный генератор недоступен на этой прошивке - не критично,
            // вибрация и голос всё равно сработают
        }
    }

    /** Fused Location — то же самое, чем пользуется Яндекс.Навигатор (GPS + WiFi + сотовые вышки) */
    private void startFusedLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        fusedClient = LocationServices.getFusedLocationProviderClient(this);

        LocationRequest request = new LocationRequest.Builder(1000)
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMinUpdateIntervalMillis(500)
                .setMaxUpdateAgeMillis(0)
                .setMaxUpdateDelayMillis(0)
                // Не заставляем Play Services молчать до "идеального" фикса. В кабине,
                // под контактной сетью и при помехах accuracy может быть хуже, но такие
                // координаты всё равно нужны JS-движку: он сам оценивает accuracy, GNSS,
                // Doppler и правдоподобие и при необходимости оставляет счёт по пути.
                .setWaitForAccurateLocation(false)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                if (result == null) return;
                Location loc = result.getLastLocation();
                if (loc == null) return;
                // Watchdog должен считать восстановлением только действительно свежий
                // системный фикс. Устаревшая кэшированная или mock-точка всё равно
                // передаётся в JS для диагностики и там отбрасывается, но больше не
                // останавливает восстановление GPS-подписки и резервного канала.
                if (isFreshRealFix(loc)) lastFixReceivedAt = System.currentTimeMillis();
                feedLocationToWebView(loc);
            }

            @Override
            public void onLocationAvailability(com.google.android.gms.location.LocationAvailability availability) {
                // Система явно сообщает - сейчас GPS/сеть не могут дать локацию. Это не
                // ошибка, просто сигнал "жди" - но если это длится слишком долго (видно
                // через watchdog ниже), активно перезапускаем сам запрос, а не пассивно
                // ждать, пока система сама решит прислать новые координаты.
                if (!availability.isLocationAvailable()) {
                    locationUnavailableSince = System.currentTimeMillis();
                    nativeMotionFilter.markSignalUnavailable();
                    if (nativeTripEngine != null) nativeTripEngine.markSignalUnavailable();
                    // КРИТИЧНО: раньше эта ветка только устанавливала Java-переменную и
                    // НИКОГДА не сообщала об этом JS-коду внутри headless WebView - значит
                    // вся защита "плавное снижение скорости при потере сигнала" (функция
                    // onErr в index.html) физически не могла сработать через основной путь
                    // на реальном Android-телефоне (она работала только в веб-версии, где
                    // браузер сам вызывает onErr через стандартный geolocation API).
                    // Добавлен явный мост в JS, аналогичный onNativeLocation ниже.
                    notifyLocationUnavailable();
                } else {
                    locationUnavailableSince = 0;
                }
            }
        };

        lastFixReceivedAt = System.currentTimeMillis();
        try {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper());
        } catch (SecurityException e) {
            // разрешение не выдано — координаты просто не пойдут, без падения приложения
        }
        startLocationWatchdog();
        startGnssQualityMonitor();
    }

    /** Отдельно наблюдаем качество спутникового сигнала. Accuracy иногда остаётся
     * правдоподобной даже в начале помехи; число реально использованных спутников и C/N0
     * позволяют JS-движку раньше перейти в осторожный режим и не принять ложный скачок. */
    private void startGnssQualityMonitor() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;
        gnssStatusCallback = new GnssStatus.Callback() {
            @Override public void onSatelliteStatusChanged(GnssStatus status) {
                gnssTelemetrySeen = true;
                int used = 0;
                float cn0Sum = 0f;
                boolean[] constellations = new boolean[8];
                for (int i = 0; i < status.getSatelliteCount(); i++) {
                    if (status.usedInFix(i)) {
                        used++;
                        cn0Sum += status.getCn0DbHz(i);
                        int constellation = status.getConstellationType(i);
                        if (constellation >= 0 && constellation < constellations.length) constellations[constellation] = true;
                    }
                }
                int diversity = 0;
                for (boolean present : constellations) if (present) diversity++;
                gnssSatellitesUsed = used;
                gnssAverageCn0 = used > 0 ? cn0Sum / used : 0f;
                gnssConstellationDiversity = diversity;
            }
            @Override public void onStopped() {
                gnssSatellitesUsed = 0;
                gnssAverageCn0 = 0f;
                gnssConstellationDiversity = 0;
            }
        };
        try { locationManager.registerGnssStatusCallback(gnssStatusCallback, mainHandler); }
        catch (SecurityException ignored) {}
    }

    /** Watchdog: если за последние 15 секунд не пришёл ни один фикс GPS (ни хороший, ни
     *  плохой) - принудительно перезапускаем сам LocationRequest (отписка+подписка). Это
     *  имитирует то, что многие навигационные приложения (включая, предположительно,
     *  Яндекс.Навигатор) делают агрессивнее системы по умолчанию - активно борются за
     *  восстановление сигнала, а не пассивно ждут, когда система сама решит его вернуть.
     *  Без этого после потери сигнала восстановление может занимать заметно дольше, чем
     *  открытие другого навигационного приложения, которое запрашивает локацию заново
     *  при каждом своём старте. */
    private void startLocationWatchdog() {
        if (locationWatchdogRunnable != null) return;
        locationWatchdogRunnable = new Runnable() {
            @Override public void run() {
                long now = System.currentTimeMillis();
                long sinceGoodFix = now - lastFixReceivedAt;

                // ЗАПАСНОЙ КАНАЛ (по просьбе Владислава - "перебросить на другой канал" при
                // глушении): дополнительный, менее точный источник позиции через сети/сотовые
                // вышки (Priority.PRIORITY_BALANCED_POWER_ACCURACY) - НЕ заменяет основной
                // точный GPS, включается только как подстраховка, когда основной молчит
                // дольше 10 секунд, и отключается, как только основной сигнал восстановился
                // (чтобы не путать данные зря и не расходовать лишнюю батарею). Передаём
                // данные через ТОТ ЖЕ JS-мост (onNativeLocation) с ЧЕСТНОЙ, обычно худшей
                // accuracy от сетевого провайдера - существующая JS-логика приоритезации GPS
                // по точности и так правильно учитывает это, без необходимости менять JS.
                if (sinceGoodFix > 10000 && !networkBackupActive) {
                    startNetworkBackup();
                } else if (sinceGoodFix <= 10000 && networkBackupActive) {
                    stopNetworkBackup();
                }

                if (fusedClient != null && locationCallback != null && sinceGoodFix > 15000
                        && now - lastFusedRestartAt > 15000) {
                    try {
                        // КРИТИЧНО: найден документированный баг конкретно Samsung One UI 8 /
                        // Android 16 на серии Galaxy S24 - GPS физически "застывает" через
                        // несколько минут работы во ВСЕХ приложениях (подтверждено множеством
                        // независимых пользователей S24/S24+ в сообществе Samsung), и помогает
                        // ТОЛЬКО полное отключение и включение служб локации (или полный
                        // перезапуск приложения) - простой переподписки на ТОТ ЖЕ
                        // FusedLocationProviderClient объект (как было раньше) недостаточно.
                        // Теперь пересоздаём САМ клиент целиком, не только LocationRequest -
                        // это ближе к тому, что реально помогает пользователям при ручном
                        // обходе бага.
                        fusedClient.removeLocationUpdates(locationCallback);
                        fusedClient = LocationServices.getFusedLocationProviderClient(TrackingService.this);
                        LocationRequest req = new LocationRequest.Builder(1000)
                                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                                .setMinUpdateIntervalMillis(500)
                                .setMaxUpdateAgeMillis(0)
                                .setMaxUpdateDelayMillis(0)
                                .setWaitForAccurateLocation(false)
                                .build();
                        fusedClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper());
                        // ВАЖНО: не трогаем lastFixReceivedAt. Раньше переподписка сама
                        // считалась новым фиксом, хотя координаты ещё не пришли. На следующем
                        // тике резервный канал выключался как будто GPS восстановился, затем
                        // всё повторялось. Отдельный таймер ограничивает частоту рестартов,
                        // а резерв остаётся включённым до настоящего fused-фикса.
                        lastFusedRestartAt = now;
                    } catch (SecurityException ignored) {}
                }
                mainHandler.postDelayed(this, 5000);
            }
        };
        mainHandler.postDelayed(locationWatchdogRunnable, 5000);
    }

    /** Включает запасной сетевой канал определения позиции - см. подробный комментарий в
     *  watchdog выше. PRIORITY_BALANCED_POWER_ACCURACY - это современный эквивалент старого
     *  NETWORK_PROVIDER, определяет позицию через Wi-Fi/сотовые вышки, не спутники. */
    private void startNetworkBackup() {
        if (fusedClient == null || networkBackupActive) return;
        try {
            networkBackupCallback = new LocationCallback() {
                @Override public void onLocationResult(LocationResult result) {
                    if (result == null) return;
                    Location loc = result.getLastLocation();
                    if (loc == null) return;
                    // НЕ обновляем lastFixReceivedAt здесь - это поле отслеживает именно
                    // ОСНОВНОЙ (точный) канал, чтобы watchdog продолжал пытаться его восстановить
                    // даже пока запасной подстраховывает.
                    feedLocationToWebView(loc);
                }
            };
            LocationRequest backupReq = new LocationRequest.Builder(3000)
                    .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                    .setMinUpdateIntervalMillis(2000)
                    .build();
            fusedClient.requestLocationUpdates(backupReq, networkBackupCallback, Looper.getMainLooper());
            networkBackupActive = true;
        } catch (SecurityException ignored) {}
    }

    private void stopNetworkBackup() {
        if (fusedClient == null || !networkBackupActive || networkBackupCallback == null) return;
        try { fusedClient.removeLocationUpdates(networkBackupCallback); } catch (Exception ignored) {}
        networkBackupActive = false;
        networkBackupCallback = null;
    }

    /** Команда headless-копии: перечитать калибровку/настройки из localStorage и реально
     *  запустить тикер. Вызывается onStartCommand'ом при каждом нажатии «Старт» (через
     *  startForegroundService из MainActivity) — это единственный реальный путь, которым
     *  команда добирается до headless-JS. Если страница headless ещё не успела загрузиться
     *  (первый запуск службы после установки/перезагрузки телефона) — откладываем команду,
     *  её выполнит сам onPageFinished, как только страница будет готова. */
    private void forceHeadlessStart() {
        if (headlessWeb == null) return;
        if (!headlessPageReady) { headlessRestartPending = true; return; }
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (headlessWeb == null) return;
                headlessWeb.evaluateJavascript(
                    "if(window.headlessRestart)window.headlessRestart();", null);
            }
        });
    }

    /** Команда headless-копии: перечитать ТОЛЬКО калибровку, без полного перезапуска
     *  трекинга (звук, wake lock, тикер не трогаются). Нужна, когда машинист на остановке
     *  поправляет км/пикет/метр прямо во время активной поездки, не нажимая «Стоп» -
     *  без этого headless продолжал бы считать со старой калибровкой в памяти, пока её
     *  явно не попросят перечитать (localStorage сам по себе JS-переменную не обновляет). */
    private void forceHeadlessRecalibrate() {
        if (headlessWeb == null || !headlessPageReady) return;
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (headlessWeb == null) return;
                headlessWeb.evaluateJavascript(
                    "if(window.headlessRecalibrate)window.headlessRecalibrate();", null);
            }
        });
    }

    /** Передаём координату прямо в headless WebView — работает независимо от Activity и экрана */
    private boolean isFreshRealFix(Location loc) {
        long ageMs = Math.max(0L, (SystemClock.elapsedRealtimeNanos()
                - loc.getElapsedRealtimeNanos()) / 1_000_000L);
        boolean mock = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? loc.isMock() : loc.isFromMockProvider();
        return ageMs <= 5000L && !mock;
    }

    private void feedLocationToWebView(Location loc) {
        if (headlessWeb == null) return;
        final double lat = loc.getLatitude();
        final double lon = loc.getLongitude();
        final float accuracy = loc.hasAccuracy() ? loc.getAccuracy() : 999f;
        final boolean hasSpeed = loc.hasSpeed();
        final long time = loc.getTime();
        final long ageMs = Math.max(0L, (SystemClock.elapsedRealtimeNanos()
                - loc.getElapsedRealtimeNanos()) / 1_000_000L);
        final boolean hasBearing = loc.hasBearing();
        final float bearing = hasBearing ? loc.getBearing() : 0f;
        final int satellitesUsed = gnssSatellitesUsed;
        final float averageCn0 = gnssAverageCn0;
        final boolean hasGnssTelemetry = gnssTelemetrySeen;
        final int constellationDiversity = gnssConstellationDiversity;
        final boolean mockLocation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? loc.isMock() : loc.isFromMockProvider();
        final float speedAccuracyMps = loc.hasSpeedAccuracy() ? loc.getSpeedAccuracyMetersPerSecond() : -1f;
        NativeMotionFilter.Result nativeResult = nativeMotionFilter.process(new NativeMotionFilter.Fix(
                lat, lon, loc.getElapsedRealtimeNanos() / 1_000_000L, ageMs, accuracy,
                hasSpeed ? loc.getSpeed() : null,
                loc.hasSpeedAccuracy() ? speedAccuracyMps : null,
                mockLocation, satellitesUsed, averageCn0, hasGnssTelemetry));
        final Float nativeSpeedMps = nativeResult.getFilteredSpeedMps();
        final String nativeQuality = nativeResult.getQuality();
        final String nativeReason = nativeResult.getReason();
        NativeRouteEngine.Snap shadowSnap = nativeRouteEngine != null
                ? nativeRouteEngine.snap(nativeRouteLabel, lat, lon) : null;
        final Double nativePhysicalM = shadowSnap != null ? shadowSnap.getPhysicalM() : null;
        final Double nativeOfficialM = shadowSnap != null ? shadowSnap.getOfficialM() : null;
        final Double nativeRouteDistanceM = shadowSnap != null ? shadowSnap.getDistanceM() : null;
        NativeTripEngine.Output nativeTrip = nativeTripEngine != null
                ? nativeTripEngine.update(new NativeTripEngine.Input(
                    loc.getElapsedRealtimeNanos() / 1_000_000L, nativeSpeedMps,
                    nativeResult.getAccepted(), shadowSnap)) : null;
        if (nativeTripEngine != null) persistNativeTripState(nativeTripEngine.save());
        final Double nativeTripPhysicalM = nativeTrip != null ? nativeTrip.getPhysicalM() : null;
        final Double nativeTripOfficialM = nativeTrip != null ? nativeTrip.getOfficialM() : null;
        final boolean nativeTripRecovering = nativeTrip != null && nativeTrip.getRecovering();
        final String nativeTripSource = nativeTrip != null ? nativeTrip.getSource() : "unavailable";
        final String nativeAlertId = nativeTrip != null ? nativeTrip.getAlertId() : null;
        final Double nativeAlertDistanceM = nativeTrip != null ? nativeTrip.getAlertDistanceM() : null;
        final boolean nativeAlertInZone = nativeTrip != null && nativeTrip.getAlertInZone();
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (headlessWeb == null) return;
                String js = "if(window.onNativeLocation)window.onNativeLocation("
                        + lat + "," + lon + "," + accuracy + ","
                        + (nativeSpeedMps != null ? String.valueOf(nativeSpeedMps) : "null") + "," + time + ","
                        + ageMs + "," + (hasBearing ? String.valueOf(bearing) : "null") + ","
                        + satellitesUsed + "," + averageCn0 + "," + hasGnssTelemetry + ","
                        + constellationDiversity + "," + mockLocation + "," + speedAccuracyMps + ",\""
                        + nativeQuality + "\",\"" + nativeReason + "\","
                        + (nativePhysicalM != null ? nativePhysicalM : "null") + ","
                        + (nativeOfficialM != null ? nativeOfficialM : "null") + ","
                        + (nativeRouteDistanceM != null ? nativeRouteDistanceM : "null") + ","
                        + (nativeTripPhysicalM != null ? nativeTripPhysicalM : "null") + ","
                        + (nativeTripOfficialM != null ? nativeTripOfficialM : "null") + ","
                        + nativeTripRecovering + ",\"" + nativeTripSource + "\","
                        + (nativeAlertId != null ? JSONObject.quote(nativeAlertId) : "null") + ","
                        + (nativeAlertDistanceM != null ? nativeAlertDistanceM : "null") + ","
                        + nativeAlertInZone + ");";
                headlessWeb.evaluateJavascript(js, null);
            }
        });
    }

    private String readAsset(String path) throws Exception {
        try (InputStream input = getAssets().open(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void persistNativeTripState(NativeTripEngine.SavedState state) {
        try {
            JSONObject json = new JSONObject();
            json.put("active", state.getActive()); json.put("route", state.getRoute());
            json.put("direction", state.getDirection()); json.put("manualOfficialM", state.getManualOfficialM());
            if (state.getPhysicalM() != null) json.put("physicalM", state.getPhysicalM());
            json.put("offsetM", state.getOffsetM()); json.put("speedMps", state.getSpeedMps());
            getSharedPreferences("piket_native", MODE_PRIVATE).edit().putString("trip", json.toString()).apply();
        } catch (Exception ignored) { }
    }

    private void applyNativeTripConfig(String raw) {
        try {
            JSONObject root = new JSONObject(raw);
            nativeRouteLabel = root.optString("route", "Все участки");
            List<NativeTripEngine.Restriction> parsed = new ArrayList<>();
            JSONArray items = root.optJSONArray("restrictions");
            if (items != null) for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                double start = item.optDouble("km", 0) * 1000.0 + item.optDouble("pk", 0) * 100.0 + item.optDouble("m", 0);
                double end = item.has("kmE") ? item.optDouble("kmE", 0) * 1000.0
                        + item.optDouble("pkE", 0) * 100.0 + item.optDouble("mE", 0) : start;
                parsed.add(new NativeTripEngine.Restriction(item.optString("id", String.valueOf(i)),
                        item.optString("peregon", "Все участки"), item.optString("dir", "both"),
                        start, end, item.optDouble("lead", root.optDouble("lead", 3000))));
            }
            if (nativeTripEngine != null) nativeTripEngine.configure(nativeRouteLabel,
                    root.optString("direction", "tuda"), root.optDouble("manualOfficialM", 0),
                    root.optBoolean("active", false), parsed);
        } catch (Exception ignored) { }
    }

    private void restoreNativeTripState() {
        try {
            String raw = getSharedPreferences("piket_native", MODE_PRIVATE).getString("trip", null);
            if (raw == null || nativeTripEngine == null) return;
            JSONObject json = new JSONObject(raw);
            nativeTripEngine.restore(new NativeTripEngine.SavedState(json.optBoolean("active", false),
                    json.optString("route", "Все участки"), json.optString("direction", "tuda"),
                    json.optDouble("manualOfficialM", 0), json.has("physicalM") ? json.getDouble("physicalM") : null,
                    json.optDouble("offsetM", 0), (float) json.optDouble("speedMps", 0), 0L));
        } catch (Exception ignored) { }
    }

    private void startNativeTripTicker() {
        nativeTripTicker = new Runnable() {
            @Override public void run() {
                if (nativeTripEngine != null) {
                    NativeTripEngine.Output output = nativeTripEngine.update(new NativeTripEngine.Input(
                            SystemClock.elapsedRealtime(), null, false, null));
                    if (output.getActive()) persistNativeTripState(nativeTripEngine.save());
                }
                if (mainHandler != null) mainHandler.postDelayed(this, 1000L);
            }
        };
        mainHandler.postDelayed(nativeTripTicker, 1000L);
    }

    /** Сообщает headless JS-коду, что система явно объявила локацию недоступной прямо сейчас
     *  (см. onLocationAvailability выше). Без этого моста JS-функция onErr() (плавное снижение
     *  скорости при потере сигнала, не застывание на старом значении) никогда не вызывалась бы
     *  на реальном Android-телефоне через основной путь - только в веб-версии, где браузер сам
     *  вызывает её через стандартный geolocation API. */
    private void notifyLocationUnavailable() {
        if (headlessWeb == null) return;
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (headlessWeb == null) return;
                headlessWeb.evaluateJavascript("if(window.onNativeLocationUnavailable)window.onNativeLocationUnavailable();", null);
            }
        });
    }

    /** Вспомогательный акселерометр — мягкая подсказка JS-коду при потере/глушении GPS, не
     *  замена GPS (та же идея, что в веб-версии piket-web, но читается напрямую через нативный
     *  Android SensorManager, а не через браузерный devicemotion — тот может ненадёжно работать
     *  именно в невидимом headless WebView, который не attached к экрану; SensorManager этой
     *  проблемы не имеет, он не зависит от WebView вообще). Передаём в JS только общую величину
     *  (модуль) вектора ускорения без гравитации — простой индикатор "идёт ли явное резкое
     *  изменение скорости прямо сейчас", не зависящий от того, как телефон лежит в кабине. */
    private void initAccelHelper() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager == null) return;
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        if (accelSensor == null) return; // датчик отсутствует на этом телефоне — просто не используем
        accelListener = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent event) {
                float mag = (float) Math.sqrt(event.values[0]*event.values[0]
                        + event.values[1]*event.values[1] + event.values[2]*event.values[2]);
                accelMag = accelMag*0.7f + mag*0.3f; // то же сглаживание, что и в JS-версии
                feedAccelToWebView();
            }
            @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        };
        sensorManager.registerListener(accelListener, accelSensor, SensorManager.SENSOR_DELAY_NORMAL);
    }

    private long lastAccelFeedAt = 0;
    private void feedAccelToWebView() {
        if (headlessWeb == null) return;
        // Не дёргаем JS на каждое срабатывание сенсора (может быть десятки раз в секунду) —
        // достаточно нескольких раз в секунду для нашей цели (мягкая подсказка, не точный замер).
        long now = System.currentTimeMillis();
        if (now - lastAccelFeedAt < 200) return;
        lastAccelFeedAt = now;
        final float magToSend = accelMag;
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (headlessWeb == null) return;
                headlessWeb.evaluateJavascript("if(window.onNativeAccel)window.onNativeAccel(" + magToSend + ");", null);
            }
        });
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "ПИКЕТ", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Фоновый контроль ограничений скорости");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // КРИТИЧНЫЙ ФИКС: видимый экран (MainActivity) вызывает Android.startTracking(), который
        // бьёт в СВОЙ собственный PiketBridge (определённый в MainActivity.java, а не здесь) —
        // тот лишь делает startForegroundService(...), что и приводит сюда, в onStartCommand.
        // Если служба уже жива (обычный случай - служба не пересоздаётся), Android вызывает
        // именно onStartCommand, а НЕ onCreate(). Раньше здесь ничего не происходило -
        // headless-копия не получала команду перечитать калибровку, и счётчик не двигался.
        // forceHeadlessStart() (TrackingService.PiketBridge.startTracking()) физически
        // никогда не вызывался реальным потоком выполнения - тот bridge принадлежит ДРУГОМУ
        // WebView (headless), а не видимому экрану, который и инициирует "Старт".
        // Здесь - единственная реальная точка, куда долетает команда от нажатия "Старт".
        //
        // ACTION_RECALIBRATE - отдельный путь для перекалибровки НА ХОДУ (машинист поправил
        // км/пикет/метр прямо во время поездки, не нажимая «Стоп»). Полный forceHeadlessStart()
        // здесь не подходит - он сбросил бы звук/тикер/wake lock без необходимости, когда
        // трекинг и так уже активен. Нужна только лёгкая перечитка калибровки.
        if (intent != null && ACTION_CONFIGURE_NATIVE.equals(intent.getAction())) {
            applyNativeTripConfig(intent.getStringExtra(EXTRA_NATIVE_CONFIG));
        } else if (intent != null && ACTION_RECALIBRATE.equals(intent.getAction())) {
            forceHeadlessRecalibrate();
        } else {
            forceHeadlessStart();
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // приложение смахнули из списка задач — гасим уведомление и службу, как договорились
        super.onTaskRemoved(rootIntent);
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (nativeTripTicker != null) {
            mainHandler.removeCallbacks(nativeTripTicker);
            nativeTripTicker = null;
        }
        if (locationWatchdogRunnable != null) {
            mainHandler.removeCallbacks(locationWatchdogRunnable);
            locationWatchdogRunnable = null;
        }
        stopNetworkBackup();
        if (fusedClient != null && locationCallback != null) {
            fusedClient.removeLocationUpdates(locationCallback);
        }
        if (sensorManager != null && accelListener != null) {
            sensorManager.unregisterListener(accelListener);
            accelListener = null;
        }
        if (locationManager != null && gnssStatusCallback != null) {
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback);
            gnssStatusCallback = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        if (headlessWeb != null) {
            headlessWeb.destroy();
            headlessWeb = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
