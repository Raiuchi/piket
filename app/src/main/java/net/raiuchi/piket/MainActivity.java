package net.raiuchi.piket;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.View;
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

public class MainActivity extends Activity implements TrackingService.LocationListener {

    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // держим экран включённым во время поездки
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        initTts();

        web = new WebView(this);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);           // localStorage — сохранение ограничений
        s.setGeolocationEnabled(true);          // запасной браузерный GPS (для теста вне APK)
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false); // звук/голос без доп. жеста
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                    GeolocationPermissions.Callback callback) {
                // разрешаем геолокацию странице
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

        // координаты от Fused Location (как у Яндекс.Навигатора) идут сюда, даже если
        // экран потушен или приложение свёрнуто — пока сам объект Activity жив
        TrackingService.setLocationListener(this);

        requestNeededPermissions();
        checkForUpdate();
    }

    /** Координата от TrackingService (Fused Location) — передаём прямо в WebView */
    @Override
    public void onNativeLocation(final double lat, final double lon, final float accuracy,
                                  final float speedMps, final boolean hasSpeed, final long time) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (web == null) return;
                String js = "if(window.onNativeLocation)window.onNativeLocation("
                        + lat + "," + lon + "," + accuracy + ","
                        + (hasSpeed ? String.valueOf(speedMps) : "null") + "," + time + ");";
                web.evaluateJavascript(js, null);
            }
        });
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

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // разрешения получены — служба запустится сама при нажатии «Старт» в приложении
    }

    /** Нативный Android TTS — Web Speech API внутри WebView не работает, это известное ограничение */
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

    private void startTrackingService() {
        Intent i = new Intent(this, TrackingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    private void stopTrackingService() {
        stopService(new Intent(this, TrackingService.class));
    }

    class PiketBridge {
        @android.webkit.JavascriptInterface
        public void updatePosition(final String text) {
            TrackingService.updateNotificationText(MainActivity.this, text);
        }

        @android.webkit.JavascriptInterface
        public void startTracking() {
            runOnUiThread(new Runnable() {
                @Override public void run() { startTrackingService(); }
            });
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
        public boolean isTtsReady() {
            return ttsReady;
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
        TrackingService.setLocationListener(null);
        stopTrackingService();
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
