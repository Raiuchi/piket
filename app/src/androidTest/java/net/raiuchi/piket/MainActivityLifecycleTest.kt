package net.raiuchi.piket

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class MainActivityLifecycleTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS
    )

    @Test fun activityCanBePausedResumedAndDestroyed() {
        val context = getApplicationContext<Context>()
        val intent = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_LIFECYCLE_TEST, true)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.moveToState(Lifecycle.State.DESTROYED)
        }
    }

    @Test fun foregroundServiceAcceptsStartAndStopLifecycle() {
        val context = getApplicationContext<Context>()
        val service = Intent(context, TrackingService::class.java)
        context.startForegroundService(service)
        Thread.sleep(750)
        assertTrue("Foreground service must stop cleanly", context.stopService(service))
    }

    @Test fun foregroundServiceCanBeRecreatedAfterStop() {
        val context = getApplicationContext<Context>()
        val service = Intent(context, TrackingService::class.java)
        context.startForegroundService(service); Thread.sleep(500); assertTrue(context.stopService(service))
        context.startForegroundService(service); Thread.sleep(500)
        assertTrue("Recreated foreground service must stop cleanly", context.stopService(service))
    }

    @Test fun installedApplicationDoesNotAllowBackup() {
        val context = getApplicationContext<Context>()
        val info = context.packageManager.getApplicationInfo(
            context.packageName, PackageManager.ApplicationInfoFlags.of(0))
        assertFalse(info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
    }
}
