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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

    @Test fun premiumWebViewSelectsUnifiedRouteAndSavesCalibration() {
        val context = getApplicationContext<Context>()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            val readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            var ready = false
            while (!ready && System.nanoTime() < readyDeadline) {
                scenario.onActivity { ready = it.isUiReadyForTest() }
                if (!ready) Thread.sleep(100)
            }
            assertTrue("Premium HTML UI did not report readiness", ready)
            val result = AtomicReference("")
            val completed = CountDownLatch(1)
            scenario.onActivity { activity ->
                activity.evaluateJavascriptForTest("""
                    (function(){
                      document.getElementById('peregonBtn').click();
                      var route=Array.from(document.querySelectorAll('#perList [data-p]')).find(function(x){return x.getAttribute('data-p').indexOf('СПбФин - Каменногорск')===0;});
                      if(!route)return 'route-missing'; route.click();
                      document.getElementById('btnCalib').click();
                      document.getElementById('cKm').value='128'; document.getElementById('cPk').value='9'; document.getElementById('cM').value='42';
                      document.getElementById('calSave').click();
                      return document.getElementById('peregonVal').textContent+'|'+document.getElementById('oKm').textContent+'|'+document.getElementById('oPk').textContent+'|'+document.getElementById('oM').textContent;
                    })()
                """.trimIndent()) { value -> result.set(value); completed.countDown() }
            }
            assertTrue("WebView did not finish the interaction", completed.await(15, TimeUnit.SECONDS))
            println("PIKET_WEB_TEST_RESULT=${result.get()}")
            assertTrue("Unified route or calibration did not persist: ${result.get()}", result.get().contains("СПбФин - Каменногорск|128|9|42"))
        }
    }
}
