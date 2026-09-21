package net.raiuchi.piket

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.*
import android.speech.tts.TextToSpeech
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.*
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

internal class NativeStartGate {
    private var route: String? = null
    private var direction: String? = null

    fun expect(route: String?, direction: String?) {
        this.route = route
        this.direction = direction
    }

    fun accepts(route: String, direction: String): Boolean {
        val expectedRoute = this.route ?: return true
        if (route != expectedRoute || direction != this.direction) return false
        this.route = null
        this.direction = null
        return true
    }
}

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
    @Volatile private var uiReady = false
    @Volatile private var uiStartupError: String? = null
    private var pendingConfig: String? = null
    private val nativeSessionCounter = AtomicLong()
    private var nativeSessionId = 0L
    private val nativeStartGate = NativeStartGate()
    private val handler = Handler(Looper.getMainLooper())
    private val repository by lazy { PiketRepository(this) }
    private val diagnostics by lazy { DiagnosticsLogger(this) }
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var thermalStatus = PowerManager.THERMAL_STATUS_NONE
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var screenMode = "auto"
    private var screenDimmed = false
    private val autoDim = Runnable { if (screenMode == "auto") setWindowBrightness(0.16f, true) }
    private val snapshotPump = object : Runnable {
        override fun run() {
            if (pageReady) publishSnapshot(repository.loadSnapshot())
            // Keep the safety-critical numbers moving once per second even when the phone is hot.
            // The page itself throttles expensive structural redraws in thermal mode.
            handler.postDelayed(this, 1_000)
        }
    }
    @Volatile private var updateCheckRunning = false
    @Volatile private var lastUpdateCheckAt = 0L
    private var updateRetryCount = 0
    @Volatile private var updateDownloadRunning = false
    @Volatile private var latestUpdateUrl: String? = null
    @Volatile private var latestUpdateVersion: String? = null
    private var pendingInstallFile: File? = null

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
                override fun onPageFinished(v:WebView,url:String){pageReady=true;publishSnapshot(repository.loadSnapshot());publishThermalState();checkForUpdate(true)}
                override fun shouldOverrideUrlLoading(v:WebView,r:WebResourceRequest)=openExternal(r.url)
                override fun shouldOverrideUrlLoading(v:WebView,url:String)=openExternal(Uri.parse(url))
            }
            view.webChromeClient=object:WebChromeClient(){
                override fun onGeolocationPermissionsShowPrompt(origin:String?,callback:GeolocationPermissions.Callback)=callback.invoke(origin,false,false)
                override fun onPermissionRequest(request:PermissionRequest)=request.deny()
            }
            view.addJavascriptInterface(PiketBridge(),"Android");setContentView(view);view.loadUrl(APP_URL)
        }
        requestPermissionsIfNeeded()
        registerThermalMonitor()
    }

    private fun registerThermalMonitor() {
        if (Build.VERSION.SDK_INT < 29) return
        val power = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        thermalStatus = power.currentThermalStatus
        thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
            thermalStatus = status
            runOnUiThread(::publishThermalState)
        }.also(power::addThermalStatusListener)
    }

    private fun publishThermalState() {
        if (pageReady) web?.evaluateJavascript(
            "if(window.onNativeThermalState)window.onNativeThermalState($thermalStatus)", null)
    }

    private fun setWindowBrightness(value: Float, dimmed: Boolean) {
        window.attributes = window.attributes.apply { screenBrightness = value }
        screenDimmed = dimmed
    }

    private fun applyScreenMode(mode: String) {
        screenMode = mode.takeIf { it in setOf("always", "auto", "system") } ?: "auto"
        handler.removeCallbacks(autoDim)
        when (screenMode) {
            "always" -> { window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); setWindowBrightness(-1f, false) }
            "auto" -> { window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); setWindowBrightness(-1f, false); handler.postDelayed(autoDim, 90_000) }
            else -> { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); setWindowBrightness(-1f, false) }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && screenMode == "auto") {
            if (screenDimmed) setWindowBrightness(-1f, false)
            handler.removeCallbacks(autoDim)
            handler.postDelayed(autoDim, 90_000)
        }
        return super.dispatchTouchEvent(event)
    }

    private fun publishSnapshot(s:TripSnapshot){
        if (!nativeStartGate.accepts(s.route, s.direction)) {
            diagnostics.event("stale_native_snapshot_ignored", mapOf("route" to s.route,
                "direction" to s.direction, "session_id" to nativeSessionId))
            return
        }
        val q={v:String->v.replace("\\","\\\\").replace("'","\\'")}
        val js="if(window.onNativeLocation)window.onNativeLocation(0,0,${s.accuracyM?:999f},${s.speedKmh/3.6f},${System.currentTimeMillis()},0,null,${s.satellites},${s.averageCn0},true,1,false,null,'native','${q(s.source)}',${s.physicalM?:"null"},${s.officialM?:"null"},null,${s.physicalM?:"null"},${s.officialM?:"null"},${s.recovering},'${q(s.source)}',${s.alertId?.let{"'${q(it)}'"}?:"null"},${s.alertDistanceM?:"null"},${s.alertInZone},'${q(s.route)}','${q(s.direction)}',${s.frequentInterference},$nativeSessionId);"
        web?.evaluateJavascript(js,null)
    }
    private fun startNative(rawConfig:String?, sessionId:Long){
        val raw=rawConfig?:pendingConfig?:return
        val active=runCatching{JSONObject(raw).put("active",true).toString()}.getOrDefault(raw);pendingConfig=active
        runCatching { JSONObject(active) }.getOrNull()?.let {
            nativeStartGate.expect(it.optString("route").takeIf(String::isNotBlank),
                it.optString("direction", "tuda"))
        }
        nativeSessionId=sessionId
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
    private fun checkForUpdate(force:Boolean=false){
        val now=System.currentTimeMillis()
        if(updateCheckRunning||(!force&&now-lastUpdateCheckAt<60_000))return
        updateCheckRunning=true;lastUpdateCheckAt=now
        Thread{
            val success=runCatching{
                val connection=(URL("https://api.github.com/repos/Raiuchi/piket/releases/latest").openConnection() as HttpURLConnection).apply{
                    connectTimeout=7_000;readTimeout=7_000
                    useCaches=false
                    setRequestProperty("Accept","application/vnd.github+json")
                    setRequestProperty("User-Agent","Piket-Android-Update-Check")
                }
                val code=connection.responseCode
                if(code !in 200..299)throw java.io.IOException("GitHub update check HTTP $code")
                val payload=connection.inputStream.bufferedReader().use{it.readText()}
                connection.disconnect()
                val release=JSONObject(payload)
                val latest=release.optString("tag_name").removePrefix("v")
                val current=packageManager.getPackageInfo(packageName,0).versionName?:"0.0.0"
                if(isNewerVersion(latest,current)){
                    var download=release.optString("html_url","https://github.com/Raiuchi/piket/releases/latest")
                    val assets=release.optJSONArray("assets")
                    if(assets!=null)for(i in 0 until assets.length()){
                        val asset=assets.optJSONObject(i)?:continue
                        if(asset.optString("name")=="piket.apk"){
                            download=asset.optString("browser_download_url",download);break
                        }
                    }
                    latestUpdateVersion=latest
                    latestUpdateUrl=download
                    handler.post{web?.evaluateJavascript("if(window.showUpdateBanner)window.showUpdateBanner('${jsQuote(latest)}','${jsQuote(download)}')",null)}
                }
            }.isSuccess
            updateCheckRunning=false
            if(success)updateRetryCount=0 else if(updateRetryCount<3){
                updateRetryCount++
                handler.postDelayed({checkForUpdate(true)},15_000L*updateRetryCount)
            }
        }.start()
    }

    private fun updateDownloadJs(script: String) =
        handler.post { if (pageReady) web?.evaluateJavascript(script, null) }

    private fun downloadLatestUpdate() {
        if (updateDownloadRunning) return
        val source = latestUpdateUrl
        val version = latestUpdateVersion
        if (source.isNullOrBlank() || version.isNullOrBlank()) {
            updateDownloadJs("if(window.onUpdateDownloadError)window.onUpdateDownloadError('Сначала проверь обновление')")
            checkForUpdate(true)
            return
        }
        updateDownloadRunning = true
        updateDownloadJs("if(window.onUpdateDownloadProgress)window.onUpdateDownloadProgress(0)")
        Thread {
            var partial: File? = null
            runCatching {
                val sourceUri = Uri.parse(source)
                require(sourceUri.scheme == "https" && sourceUri.host.equals("github.com", true)) {
                    "Недопустимый адрес обновления"
                }
                val updateDir = File(cacheDir, "updates").apply { mkdirs() }
                partial = File(updateDir, "piket-$version.apk.part")
                val target = File(updateDir, "piket-$version.apk")
                partial!!.delete()
                target.delete()
                val connection = (URL(source).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 45_000
                    instanceFollowRedirects = true
                    useCaches = false
                    setRequestProperty("Accept", "application/vnd.android.package-archive")
                    setRequestProperty("User-Agent", "Piket-Android-Updater")
                }
                val code = connection.responseCode
                if (code !in 200..299) throw java.io.IOException("GitHub download HTTP $code")
                val total = connection.contentLengthLong
                var copied = 0L
                var lastPercent = -1
                connection.inputStream.use { input ->
                    FileOutputStream(partial!!).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                            if (copied > 200L * 1024 * 1024) throw java.io.IOException("Файл обновления слишком большой")
                            val percent = if (total > 0) ((copied * 100) / total).toInt().coerceIn(0, 100) else -1
                            if (percent >= 0 && percent >= lastPercent + 2) {
                                lastPercent = percent
                                updateDownloadJs("if(window.onUpdateDownloadProgress)window.onUpdateDownloadProgress($percent)")
                            }
                        }
                        output.fd.sync()
                    }
                }
                connection.disconnect()
                if (copied < 1_000_000) throw java.io.IOException("Получен неполный APK")
                if (!partial!!.renameTo(target)) {
                    partial!!.copyTo(target, overwrite = true)
                    partial!!.delete()
                }
                handler.post {
                    updateDownloadRunning = false
                    web?.evaluateJavascript("if(window.onUpdateDownloadReady)window.onUpdateDownloadReady()", null)
                    promptInstall(target)
                }
            }.onFailure { error ->
                partial?.delete()
                updateDownloadRunning = false
                updateDownloadJs(
                    "if(window.onUpdateDownloadError)window.onUpdateDownloadError('${jsQuote(error.message ?: "Не удалось скачать APK")}')"
                )
            }
        }.start()
    }

    private fun promptInstall(file: File) {
        if (!file.exists()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            pendingInstallFile = file
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            }.onFailure {
                updateDownloadJs("if(window.onUpdateDownloadError)window.onUpdateDownloadError('Разреши установку из этого источника в настройках Android')")
            }
            return
        }
        pendingInstallFile = null
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.onFailure {
            updateDownloadJs("if(window.onUpdateDownloadError)window.onUpdateDownloadError('Не удалось открыть установщик Android')")
        }
    }

    override fun onResume(){
        super.onResume()
        web?.onResume()
        web?.resumeTimers()
        pendingInstallFile?.let { file ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) promptInstall(file)
        }
        handler.removeCallbacks(snapshotPump)
        if(web != null) handler.post(snapshotPump)
        if(pageReady)checkForUpdate()
    }
    override fun onPause(){
        handler.removeCallbacks(snapshotPump)
        web?.onPause()
        web?.pauseTimers()
        super.onPause()
    }
    private fun jsQuote(value:String)=value.replace("\\","\\\\").replace("'","\\'")
    private fun isNewerVersion(latest:String,current:String):Boolean{
        val a=latest.split('.').map{it.takeWhile(Char::isDigit).toIntOrNull()?:0}
        val b=current.split('.').map{it.takeWhile(Char::isDigit).toIntOrNull()?:0}
        for(i in 0 until maxOf(a.size,b.size)){val av=a.getOrElse(i){0};val bv=b.getOrElse(i){0};if(av!=bv)return av>bv}
        return false
    }
    private fun initTts(){tts=TextToSpeech(this){status->if(status==TextToSpeech.SUCCESS){val r=tts?.setLanguage(Locale("ru","RU"))?:TextToSpeech.LANG_NOT_SUPPORTED;ttsReady=r!=TextToSpeech.LANG_MISSING_DATA&&r!=TextToSpeech.LANG_NOT_SUPPORTED}}}

    inner class PiketBridge{
        @JavascriptInterface fun notifyUiReady() { uiReady = true }
        @JavascriptInterface fun notifyUiStartupError(message: String) { uiStartupError = message }
        @JavascriptInterface fun configureNativeTrip(json:String)=runOnUiThread{configureRunning(json)}
        @JavascriptInterface fun startTracking():Long {
            if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED){
                val sessionId=nativeSessionCounter.incrementAndGet()
                runOnUiThread{startNative(pendingConfig,sessionId)}
                return sessionId
            }
            runOnUiThread{requestPermissionsIfNeeded();web?.evaluateJavascript("if(window.toast)toast('Разреши точную геолокацию')",null)}
            return 0L
        }
        @JavascriptInterface fun stopTracking()=runOnUiThread{stopService(Intent(this@MainActivity,TrackingService::class.java))}
        @JavascriptInterface fun recalibrate()=runOnUiThread{pendingConfig?.let(::configureRunning)}
        @JavascriptInterface fun setKeepScreen(enabled:Boolean)=runOnUiThread{if(enabled)window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        @JavascriptInterface fun setScreenMode(mode:String)=runOnUiThread{applyScreenMode(mode)}
        @JavascriptInterface fun lowerBrightness()=runOnUiThread{setWindowBrightness(0.18f,true)}
        @JavascriptInterface fun isServiceTracking()=serviceRunning()
        @JavascriptInterface fun isHeadless()=false
        @JavascriptInterface fun isTtsReady()=ttsReady
        @JavascriptInterface fun getAppVersion():String=packageManager.getPackageInfo(packageName,0).versionName?:"0.0.0"
        @JavascriptInterface fun downloadUpdate()=runOnUiThread{downloadLatestUpdate()}
        @JavascriptInterface fun speak(text:String)=runOnUiThread{if(ttsReady)tts?.speak(text,TextToSpeech.QUEUE_FLUSH,null,"piket-ui")}
        @JavascriptInterface fun vibrate(kind:String)=runOnUiThread{val v=if(Build.VERSION.SDK_INT>=31)(getSystemService(VIBRATOR_MANAGER_SERVICE)as VibratorManager).defaultVibrator else getSystemService(VIBRATOR_SERVICE)as Vibrator;val p=if(kind=="danger")longArrayOf(0,160,80,160,80,260)else longArrayOf(0,120,90,120);if(Build.VERSION.SDK_INT>=26)v.vibrate(VibrationEffect.createWaveform(p,-1))else v.vibrate(p,-1)}
        @JavascriptInterface fun beep(kind:String){}
        @JavascriptInterface fun updatePosition(text:String){}
        @JavascriptInterface fun logUiDiagnostic(event:String, json:String) {
            if (event != "schedule_card_changed") return
            runCatching {
                val row = JSONObject(json)
                diagnostics.event(event, mapOf(
                    "route" to row.optString("route"), "direction" to row.optString("direction"),
                    "train" to row.optString("train"), "index" to row.optInt("index"),
                    "from" to row.optString("from"), "to" to row.optString("to"),
                    "physical_m" to row.optDouble("physical_m").takeIf { row.has("physical_m") }
                ))
            }
        }
        @JavascriptInterface fun openUrl(url:String)=runOnUiThread{openExternal(Uri.parse(url))}
        @JavascriptInterface fun shareText(subject:String,text:String)=runOnUiThread{runCatching{startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_SUBJECT,subject);putExtra(Intent.EXTRA_TEXT,text)},"Экспорт"))}}
        @JavascriptInterface fun shareDiagnostics()=runOnUiThread{
            runCatching {
                val file=diagnostics.exportFile()
                val uri=FileProvider.getUriForFile(this@MainActivity,"$packageName.files",file)
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{
                    type="text/plain";putExtra(Intent.EXTRA_SUBJECT,"ПИКЕТ — диагностика GPS")
                    putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },"Поделиться диагностикой"))
            }.onFailure{web?.evaluateJavascript("if(window.toast)toast('Не удалось подготовить журнал')",null)}
        }
        @JavascriptInterface fun clearDiagnostics()=runOnUiThread{
            diagnostics.clear();web?.evaluateJavascript("if(window.toast)toast('Диагностический журнал очищен')",null)
        }
        @JavascriptInterface fun openAutostartSettings()=false
    }
    fun evaluateJavascriptForTest(script: String, callback: (String) -> Unit) =
        evaluateJavascriptForTest(script, callback, 0)

    fun isUiReadyForTest(): Boolean = uiReady
    fun uiStartupErrorForTest(): String? = uiStartupError

    private fun evaluateJavascriptForTest(script: String, callback: (String) -> Unit, attempt: Int) {
        if ((!pageReady || !uiReady) && attempt < 600) {
            handler.postDelayed({ evaluateJavascriptForTest(script, callback, attempt + 1) }, 100)
            return
        }
        if (!pageReady || !uiReady) { callback("\"page-not-ready\""); return }
        web?.evaluateJavascript(script, callback) ?: callback("\"webview-missing\"")
    }
    override fun onBackPressed(){if(web?.canGoBack()==true)web?.goBack()else moveTaskToBack(true)}
    override fun onDestroy(){
        thermalListener?.let { if(Build.VERSION.SDK_INT>=29)(getSystemService(POWER_SERVICE)as? PowerManager)?.removeThermalStatusListener(it) }
        handler.removeCallbacksAndMessages(null);tts?.stop();tts?.shutdown();web?.let{v->(v.parent as? android.view.ViewGroup)?.removeView(v);v.stopLoading();v.removeJavascriptInterface("Android");v.destroy()};web=null;super.onDestroy()
    }
}
