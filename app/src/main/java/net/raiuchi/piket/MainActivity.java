package net.raiuchi.piket;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayList;

public class MainActivity extends Activity {

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // держим экран включённым во время поездки
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);           // localStorage — сохранение ограничений
        s.setGeolocationEnabled(true);          // GPS внутри WebView
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

        requestNeededPermissions();
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
        stopTrackingService();
        if (web != null) {
            ((android.view.ViewGroup) web.getParent()).removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
