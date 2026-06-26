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
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
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

    private PowerManager.WakeLock wakeLock;
    private FusedLocationProviderClient fusedClient;
    private LocationCallback locationCallback;
    private long lastFixReceivedAt = 0;
    private long locationUnavailableSince = 0;
    private Runnable locationWatchdogRunnable;

    private WebView headlessWeb;
    private boolean headlessPageReady = false;
    private boolean headlessRestartPending = false;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private Vibrator vibrator;
    private Handler mainHandler;

    public static void updateNotificationText(Context ctx, String text) {
        Notification notification = new Notification.Builder(ctx, CHANNEL_ID)
                .setContentTitle("ПИКЕТ · " + text)
                .setContentText("Контроль ограничений активен")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
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

        LocationRequest request = new LocationRequest.Builder(2000)
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMinUpdateIntervalMillis(1000)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                if (result == null) return;
                Location loc = result.getLastLocation();
                if (loc == null) return;
                lastFixReceivedAt = System.currentTimeMillis();
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
                if (fusedClient != null && locationCallback != null && (now - lastFixReceivedAt) > 15000) {
                    try {
                        fusedClient.removeLocationUpdates(locationCallback);
                        LocationRequest req = new LocationRequest.Builder(2000)
                                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                                .setMinUpdateIntervalMillis(1000)
                                .build();
                        fusedClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper());
                        lastFixReceivedAt = now; // не дёргаем перезапрос на каждом тике, ждём ещё 15с
                    } catch (SecurityException ignored) {}
                }
                mainHandler.postDelayed(this, 5000);
            }
        };
        mainHandler.postDelayed(locationWatchdogRunnable, 5000);
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
    private void feedLocationToWebView(Location loc) {
        if (headlessWeb == null) return;
        final double lat = loc.getLatitude();
        final double lon = loc.getLongitude();
        final float accuracy = loc.hasAccuracy() ? loc.getAccuracy() : 999f;
        final boolean hasSpeed = loc.hasSpeed();
        final float speedMps = hasSpeed ? loc.getSpeed() : 0f;
        final long time = loc.getTime();
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (headlessWeb == null) return;
                String js = "if(window.onNativeLocation)window.onNativeLocation("
                        + lat + "," + lon + "," + accuracy + ","
                        + (hasSpeed ? String.valueOf(speedMps) : "null") + "," + time + ");";
                headlessWeb.evaluateJavascript(js, null);
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
        if (intent != null && ACTION_RECALIBRATE.equals(intent.getAction())) {
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
        if (locationWatchdogRunnable != null) {
            mainHandler.removeCallbacks(locationWatchdogRunnable);
            locationWatchdogRunnable = null;
        }
        if (fusedClient != null && locationCallback != null) {
            fusedClient.removeLocationUpdates(locationCallback);
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
