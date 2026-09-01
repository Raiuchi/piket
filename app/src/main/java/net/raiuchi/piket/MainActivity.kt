package net.raiuchi.piket

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import android.webkit.*
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Locale

/** The restored premium HTML is a view. GPS, routes, recovery and alerts stay native. */
@Suppress("DEPRECATION", "SetJavaScriptEnabled")
class MainActivity : Activity() {
    companion object {
        @JvmField val EXTRA_LIFECYCLE_TEST = "net.raiuchi.piket.extra.LIFECYCLE_TEST"
        private const val APP_URL = "file:///android_asset/index.html"
        private const val REQUEST_PERMISSIONS = 100
    }
    private var web: WebView? = null
    private var pageReady = false
    private var pendingConfig: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val repository by lazy { PiketRepository(this) }
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val snapshotPump = object : Runnable {
        override fun run() { if (pageReady) publishSnapshot(repository.loadSnapshot()); handler.postDelayed(this, 500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_LIFECYCLE_TEST, false)) { setContentView(android.widget.FrameLayout(this)); return }
        initTts()
        web = WebView(this).also { view ->
            view.settings.apply {
                javaScriptEnabled=true;domStorageEnabled=true;setGeolocationEnabled(true);allowFileAccess=true
                allowContentAccess=false;allowFileAccessFromFileURLs=false;allowUniversalAccessFromFileURLs=false
                mediaPlaybackRequiresUserGesture=false;cacheMode=WebSettings.LOAD_NO_CACHE
            }
            view.webViewClient=object:WebViewClient(){
                override fun onPageFinished(v:WebView,url:String){pageReady=true;publishSnapshot(repository.loadSnapshot())}
                override fun shouldOverrideUrlLoading(v:WebView,r:WebResourceRequest)=openExternal(r.url)
                override fun shouldOverrideUrlLoading(v:WebView,url:String)=openExternal(Uri.parse(url))
            }
            view.webChromeClient=object:WebChromeClient(){
                override fun onGeolocationPermissionsShowPrompt(origin:String?,callback:GeolocationPermissions.Callback)=callback.invoke(origin,false,false)
                override fun onPermissionRequest(request:PermissionRequest)=request.deny()
            }
            view.addJavascriptInterface(PiketBridge(),"Android");setContentView(view);view.loadUrl(APP_URL)
        }
        requestPermissionsIfNeeded();handler.post(snapshotPump)
    }

    private fun publishSnapshot(s:TripSnapshot){
        val q={v:String->v.replace("\\","\\\\").replace("'","\\'")}
        val js="if(window.onNativeLocation)window.onNativeLocation(0,0,${s.accuracyM?:999f},${s.speedKmh/3.6f},${System.currentTimeMillis()},0,null,${s.satellites},${s.averageCn0},true,1,false,null,'native','${q(s.source)}',${s.physicalM?:"null"},${s.officialM?:"null"},null,${s.physicalM?:"null"},${s.officialM?:"null"},${s.recovering},'${q(s.source)}',${s.alertId?.let{"'${q(it)}'"}?:"null"},${s.alertDistanceM?:"null"},${s.alertInZone});"
        web?.evaluateJavascript(js,null)
    }
    private fun startNative(rawConfig:String?){
        val raw=rawConfig?:pendingConfig?:return
        val active=runCatching{JSONObject(raw).put("active",true).toString()}.getOrDefault(raw);pendingConfig=active
        ContextCompat.startForegroundService(this,Intent(this,TrackingService::class.java).apply{action=TrackingService.ACTION_CONFIGURE_NATIVE;putExtra(TrackingService.EXTRA_NATIVE_CONFIG,active)})
    }
    private fun configureRunning(raw:String){
        pendingConfig=raw;if(!serviceRunning())return
        startService(Intent(this,TrackingService::class.java).apply{action=TrackingService.ACTION_CONFIGURE_NATIVE;putExtra(TrackingService.EXTRA_NATIVE_CONFIG,raw)})
    }
    private fun serviceRunning():Boolean=(getSystemService(ACTIVITY_SERVICE)as? ActivityManager)?.getRunningServices(Int.MAX_VALUE)?.any{it.service.className==TrackingService::class.java.name}==true
    private fun requestPermissionsIfNeeded(){
        val need=mutableListOf<String>();if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)need+=Manifest.permission.ACCESS_FINE_LOCATION
        if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED)need+=Manifest.permission.ACCESS_COARSE_LOCATION
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)need+=Manifest.permission.POST_NOTIFICATIONS
        if(need.isNotEmpty())requestPermissions(need.toTypedArray(),REQUEST_PERMISSIONS)
    }
    private fun openExternal(uri:Uri?):Boolean{if(uri==null||uri.toString()==APP_URL)return false;if(uri.scheme in listOf("http","https"))runCatching{startActivity(Intent(Intent.ACTION_VIEW,uri))};return true}
    private fun initTts(){tts=TextToSpeech(this){status->if(status==TextToSpeech.SUCCESS){val r=tts?.setLanguage(Locale("ru","RU"))?:TextToSpeech.LANG_NOT_SUPPORTED;ttsReady=r!=TextToSpeech.LANG_MISSING_DATA&&r!=TextToSpeech.LANG_NOT_SUPPORTED}}}

    inner class PiketBridge{
        @JavascriptInterface fun configureNativeTrip(json:String)=runOnUiThread{configureRunning(json)}
        @JavascriptInterface fun startTracking()=runOnUiThread{if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)startNative(pendingConfig)else{requestPermissionsIfNeeded();web?.evaluateJavascript("if(window.toast)toast('Разреши точную геолокацию')",null)}}
        @JavascriptInterface fun stopTracking()=runOnUiThread{stopService(Intent(this@MainActivity,TrackingService::class.java))}
        @JavascriptInterface fun recalibrate()=runOnUiThread{pendingConfig?.let(::configureRunning)}
        @JavascriptInterface fun setKeepScreen(enabled:Boolean)=runOnUiThread{if(enabled)window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        @JavascriptInterface fun isServiceTracking()=serviceRunning()
        @JavascriptInterface fun isHeadless()=false
        @JavascriptInterface fun isTtsReady()=ttsReady
        @JavascriptInterface fun getAppVersion():String=packageManager.getPackageInfo(packageName,0).versionName?:"0.0.0"
        @JavascriptInterface fun speak(text:String)=runOnUiThread{if(ttsReady)tts?.speak(text,TextToSpeech.QUEUE_FLUSH,null,"piket-ui")}
        @JavascriptInterface fun vibrate(kind:String)=runOnUiThread{val v=if(Build.VERSION.SDK_INT>=31)(getSystemService(VIBRATOR_MANAGER_SERVICE)as VibratorManager).defaultVibrator else getSystemService(VIBRATOR_SERVICE)as Vibrator;val p=if(kind=="danger")longArrayOf(0,160,80,160,80,260)else longArrayOf(0,120,90,120);if(Build.VERSION.SDK_INT>=26)v.vibrate(VibrationEffect.createWaveform(p,-1))else v.vibrate(p,-1)}
        @JavascriptInterface fun beep(kind:String){}
        @JavascriptInterface fun updatePosition(text:String){}
        @JavascriptInterface fun openUrl(url:String)=runOnUiThread{openExternal(Uri.parse(url))}
        @JavascriptInterface fun shareText(subject:String,text:String)=runOnUiThread{runCatching{startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_SUBJECT,subject);putExtra(Intent.EXTRA_TEXT,text)},"Экспорт"))}}
        @JavascriptInterface fun openAutostartSettings()=false
    }
    override fun onBackPressed(){if(web?.canGoBack()==true)web?.goBack()else moveTaskToBack(true)}
    override fun onDestroy(){handler.removeCallbacks(snapshotPump);tts?.stop();tts?.shutdown();web?.let{v->(v.parent as? android.view.ViewGroup)?.removeView(v);v.stopLoading();v.removeJavascriptInterface("Android");v.destroy()};web=null;super.onDestroy()}
}
