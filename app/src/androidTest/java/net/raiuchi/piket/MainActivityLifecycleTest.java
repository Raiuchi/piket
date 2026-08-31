package net.raiuchi.piket;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.rule.GrantPermissionRule;

import org.junit.Test;
import org.junit.Rule;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class MainActivityLifecycleTest {

    @Rule
    public GrantPermissionRule permissions = GrantPermissionRule.grant(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.POST_NOTIFICATIONS
    );

    @Test
    public void activityCanBePausedResumedAndDestroyed() {
        Context context = getApplicationContext();
        Intent intent = new Intent(context, MainActivity.class)
                .putExtra(MainActivity.EXTRA_LIFECYCLE_TEST, true);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(intent)) {
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.moveToState(Lifecycle.State.DESTROYED);
        }
    }

    @Test
    public void foregroundServiceAcceptsStartAndStopLifecycle() throws Exception {
        Context context = getApplicationContext();
        Intent service = new Intent(context, TrackingService.class);
        context.startForegroundService(service);
        Thread.sleep(750);
        assertTrue("Foreground service must stop cleanly", context.stopService(service));
    }

    @Test
    public void foregroundServiceCanBeRecreatedAfterStop() throws Exception {
        Context context = getApplicationContext();
        Intent service = new Intent(context, TrackingService.class);
        context.startForegroundService(service);
        Thread.sleep(500);
        assertTrue(context.stopService(service));
        context.startForegroundService(service);
        Thread.sleep(500);
        assertTrue("Recreated foreground service must stop cleanly", context.stopService(service));
    }

    @Test
    public void installedApplicationDoesNotAllowBackup() throws Exception {
        Context context = getApplicationContext();
        ApplicationInfo info = context.getPackageManager().getApplicationInfo(context.getPackageName(), PackageManager.ApplicationInfoFlags.of(0));
        assertFalse((info.flags & ApplicationInfo.FLAG_ALLOW_BACKUP) != 0);
    }
}
