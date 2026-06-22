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

    private PowerManager.WakeLock wakeLock;
    private FusedLocationProviderClient fusedClient;
    private LocationCallback locationCallback;

    private WebView headlessWeb;
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
        headlessWeb.setWebViewClient(new WebViewClient());
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
            // КРИТИЧНЫЙ ФИКС: headless-копия создаётся один раз в onCreate() и живёт
            // молча всё время жизни службы — её JS-состояние (state.calib, state.settings)
            // не обновляется само просто потому что видимый экран что-то записал в localStorage.
            // Раньше этот метод был пустой заглушкой → headless продолжал работать со старой
            // (или вообще отсутствующей) калибровкой, и счётчик км не двигался, хотя GPS
            // и тикер технически были живы. Теперь явно командуем headless-копии заново
            // прочитать состояние из storage и реально запустить трекинг.
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
                feedLocationToWebView(loc);
            }
        };

        try {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper());
        } catch (SecurityException e) {
            // разрешение не выдано — координаты просто не пойдут, без падения приложения
        }
    }

    /** Команда headless-копии: перечитать калибровку/настройки из localStorage и реально
     *  запустить тикер. Вызывается из видимого экрана при каждом нажатии «Старт» — это
     *  единственный момент, когда headless-JS обязан подхватить актуальное состояние. */
    private void forceHeadlessStart() {
        if (headlessWeb == null) return;
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (headlessWeb == null) return;
                headlessWeb.evaluateJavascript(
                    "if(window.headlessRestart)window.headlessRestart();", null);
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
