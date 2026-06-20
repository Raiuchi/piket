package net.raiuchi.piket;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

public class TrackingService extends Service {

    private static final String CHANNEL_ID = "piket_tracking";
    private PowerManager.WakeLock wakeLock;
    private FusedLocationProviderClient fusedClient;
    private LocationCallback locationCallback;

    /** Мост к MainActivity — пока она жива, координаты сразу летят в WebView */
    public interface LocationListener {
        void onNativeLocation(double lat, double lon, float accuracy, float speedMps, boolean hasSpeed, long time);
    }
    private static volatile LocationListener listener;
    public static void setLocationListener(LocationListener l) { listener = l; }

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

        startFusedLocation();
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
                LocationListener l = listener;
                if (l != null) {
                    l.onNativeLocation(
                            loc.getLatitude(), loc.getLongitude(),
                            loc.hasAccuracy() ? loc.getAccuracy() : 999f,
                            loc.hasSpeed() ? loc.getSpeed() : 0f,
                            loc.hasSpeed(),
                            loc.getTime());
                }
            }
        };

        try {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper());
        } catch (SecurityException e) {
            // разрешение не выдано — координаты просто не пойдут, без падения приложения
        }
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
        // приложение смахнули из списка задач — гасим уведомление и службу
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
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
