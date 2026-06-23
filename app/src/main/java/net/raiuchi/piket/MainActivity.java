package net.raiuchi.piket;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.speech.tts.TextToSpeech;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Видимый экран приложения. С переходом на headless-движок в TrackingService:
 * - НЕ принимает GPS напрямую и не ведёт собственный расчёт позиции во время активного трекинга —
 *   этим занимается headless WebView внутри службы, независимо от того, открыт этот экран или нет.
 * - Просто отображает UI (настройки, список ограничений, справочник) и, если трекинг уже идёт
 *   в фоне, входит в режим зеркала (JS сам считывает снэпшот состояния из localStorage).
 * - Голос на этом экране используется только пока сам экран открыт (например, демо-режим);
 *   реальные предупреждения во время поездки озвучивает служба через свой собственный TTS.
 */
public class MainActivity extends Activity {

    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // держим экран включённым во время поездки, если приложение открыто на экране
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        initTts();

        web = new WebView(this);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);           // localStorage — общий со службой (тот же процесс)
        s.setGeolocationEnabled(true);          // запасной браузерный GPS (для теста вне APK, экран открыт)
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false); // звук/голос без доп. жеста
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                    GeolocationPermissions.Callback callback) {
                // разрешаем геолокацию странице (запасной канал, когда headless-служба не используется)
                callback.invoke(origin, true, true);
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                request.grant(request.getResources());
            }
        });

        web.addJavascriptInterface(new PiketBridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");

        requestNeededPermissions();
        checkForUpdate();
    }

    /** Запрос к GitHub Releases API в фоне; репозиторий публичный — без токена */
    private void checkForUpdate() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    URL url = new URL("https://api.github.com/repos/Raiuchi/piket/releases/latest");
                    HttpURLConnection con = (HttpURLConnection) url.openConnection();
                    con.setRequestProperty("Accept", "application/vnd.github+json");
                    con.setConnectTimeout(6000);
                    con.setReadTimeout(6000);
                    if (con.getResponseCode() != 200) return;

                    StringBuilder sb = new StringBuilder();
                    BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream()));
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    r.close();

                    JSONObject json = new JSONObject(sb.toString());
                    String tag = json.optString("tag_name", "");
                    String htmlUrl = json.optString("html_url", "https://github.com/Raiuchi/piket/releases/latest");
                    String latest = tag.startsWith("v") ? tag.substring(1) : tag;
                    String current = currentVersionName();

                    if (!latest.isEmpty() && isNewer(latest, current)) {
                        final String jsUrl = htmlUrl;
                        final String jsVer = latest;
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (web != null) {
                                    web.evaluateJavascript(
                                        "if(window.showUpdateBanner)window.showUpdateBanner('" + jsVer + "','" + jsUrl + "');",
                                        null);
                                }
                            }
                        });
                    }
                } catch (Exception e) {
                    // нет связи или сервис недоступен — просто не показываем баннер
                }
            }
        }).start();
    }

    private String currentVersionName() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName != null ? pi.versionName : "0.0.0";
        } catch (Exception e) {
            return "0.0.0";
        }
    }

    /** Простое сравнение версий вида X.Y.Z */
    private boolean isNewer(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int va = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int vb = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (va != vb) return va > vb;
        }
        return false;
    }

    private int parseIntSafe(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); } catch (Exception e) { return 0; }
    }

    private void requestNeededPermissions() {
        ArrayList<String> need = new ArrayList<>();
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(android.Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), 100);
        }
    }

    /** Многие производители (особенно Xiaomi/MIUI, частично Samsung, Huawei) убивают
     *  foreground-службы с GPS вопреки официальной политике Android - это подтверждённая,
     *  массовая проблема (см. dontkillmyapp.com), не специфичная для нашего приложения.
     *  Системного способа полностью обойти агрессивные OEM-надстройки нет, но запрос
     *  исключения из официальной оптимизации батареи Android снимает хотя бы стандартный
     *  слой ограничений - и явно показывает машинисту, что нужно проверить вручную в
     *  настройках телефона, если трекинг останавливается при потушенном экране. */
    private void requestIgnoreBatteryOptimizations() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        } catch (Exception e) {
            // если системный диалог недоступен на этой прошивке - просто пропускаем,
            // не критично для запуска приложения
        }
    }

    /** Android НЕ разрешает приложению самому включить чужой системный переключатель
     *  (автозапуск, "без ограничений" и т.п.) - это было бы дырой безопасности, и ни одно
     *  стороннее приложение (Яндекс.Навигатор, WhatsApp и другие) не может это обойти.
     *  Но можно ОТКРЫТЬ человеку нужный экран настроек одним нажатием, вместо того чтобы
     *  он искал его сам по инструкции - финальное нажатие переключателя остаётся за ним,
     *  это единственное, что разрешает система. */
    private boolean openManufacturerAutostartSettings() {
        String manufacturer = Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        // На каждого производителя — несколько известных вариантов компонента, так как
        // разные версии прошивки/модели одного и того же бренда могут называть экран
        // настроек по-разному. Пробуем по очереди, пока один не откроется успешно.
        String[][] candidates;
        if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco")) {
            candidates = new String[][]{
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"}
            };
        } else if (manufacturer.contains("huawei") || manufacturer.contains("honor")) {
            candidates = new String[][]{
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"}
            };
        } else if (manufacturer.contains("vivo") || manufacturer.contains("iqoo")) {
            candidates = new String[][]{
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartupManagerDetailActivity"}
            };
        } else if (manufacturer.contains("samsung")) {
            candidates = new String[][]{
                {"com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"}
            };
        } else if (manufacturer.contains("oppo") || manufacturer.contains("oneplus") || manufacturer.contains("realme")) {
            candidates = new String[][]{
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
                {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"}
            };
        } else if (manufacturer.contains("letv")) {
            candidates = new String[][]{
                {"com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"}
            };
        } else if (manufacturer.contains("asus")) {
            candidates = new String[][]{
                {"com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity"}
            };
        } else {
            // Незнакомый производитель — нет смысла угадывать наугад, честно говорим
            // JS, что автоматического перехода нет, дальше показываем обычную инструкцию
            return false;
        }
        PackageManager pm = getPackageManager();
        for (String[] c : candidates) {
            try {
                Intent intent = new Intent();
                intent.setComponent(new android.content.ComponentName(c[0], c[1]));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                // Проверяем заранее, что система реально может обработать этот intent -
                // иначе startActivity бросит ActivityNotFoundException, и на части
                // прошивок это может показать пользователю системный краш-тост
                if (intent.resolveActivity(pm) != null) {
                    startActivity(intent);
                    return true;
                }
            } catch (Exception e) {
                // этот конкретный вариант не подошёл - пробуем следующий
            }
        }
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // разрешения получены — служба запустится сама при нажатии «Старт» в приложении
        requestIgnoreBatteryOptimizations();
    }

    /** Нативный Android TTS для этого экрана (демо-режим и т.п., пока экран открыт) */
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

    private Vibrator getVibrator() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
            return vm != null ? vm.getDefaultVibrator() : null;
        }
        return (Vibrator) getSystemService(VIBRATOR_SERVICE);
    }

    private void vibrateNative(String kind) {
        Vibrator v = getVibrator();
        if (v == null || !v.hasVibrator()) return;
        long[] timings = "danger".equals(kind)
                ? new long[]{0, 160, 80, 160, 80, 260}
                : new long[]{0, 120, 90, 120};
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(timings, -1));
        } else {
            v.vibrate(timings, -1);
        }
    }

    private void startTrackingService() {
        Intent i = new Intent(this, TrackingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    private boolean isTrackingServiceRunning() {
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am == null) return false;
        for (android.app.ActivityManager.RunningServiceInfo info : am.getRunningServices(Integer.MAX_VALUE)) {
            if (TrackingService.class.getName().equals(info.service.getClassName())) return true;
        }
        return false;
    }

    private void stopTrackingService() {
        stopService(new Intent(this, TrackingService.class));
    }

    class PiketBridge {
        @android.webkit.JavascriptInterface
        public void updatePosition(final String text) {
            // во время активного трекинга уведомление уже обновляет сама служба (headless-движок);
            // здесь не дублируем, чтобы не было гонки двух источников одного и того же текста
        }

        @android.webkit.JavascriptInterface
        public void startTracking() {
            runOnUiThread(new Runnable() {
                @Override public void run() { startTrackingService(); }
            });
        }

        @android.webkit.JavascriptInterface
        public boolean openAutostartSettings() {
            return openManufacturerAutostartSettings();
        }

        @android.webkit.JavascriptInterface
        public void stopTracking() {
            runOnUiThread(new Runnable() {
                @Override public void run() { stopTrackingService(); }
            });
        }

        @android.webkit.JavascriptInterface
        public void openUrl(final String url) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) {}
                }
            });
        }

        @android.webkit.JavascriptInterface
        public String getAppVersion() {
            return currentVersionName();
        }

        @android.webkit.JavascriptInterface
        public void speak(final String text) {
            runOnUiThread(new Runnable() {
                @Override public void run() { speakNative(text); }
            });
        }

        @android.webkit.JavascriptInterface
        public void vibrate(final String kind) {
            runOnUiThread(new Runnable() {
                @Override public void run() { vibrateNative(kind); }
            });
        }

        @android.webkit.JavascriptInterface
        public boolean isTtsReady() {
            return ttsReady;
        }

        @android.webkit.JavascriptInterface
        public boolean isHeadless() {
            return false;
        }

        @android.webkit.JavascriptInterface
        public boolean isServiceTracking() {
            return isTrackingServiceRunning();
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            // не закрываем, а сворачиваем — приложение продолжает работать в фоне
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onDestroy() {
        // Трекинг НЕ останавливается здесь — он живёт в headless-службе независимо
        // от жизни этого экрана. Останов только по явному «Стоп» или смахиванию задачи.
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        if (web != null) {
            ((android.view.ViewGroup) web.getParent()).removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
