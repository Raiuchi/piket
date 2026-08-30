import fs from 'node:fs';
import vm from 'node:vm';

const root = new URL('../', import.meta.url);
const read = path => fs.readFileSync(new URL(path, root), 'utf8');
const checks = [];
const check = (name, condition) => {
  checks.push({ name, ok: Boolean(condition) });
  if (!condition) process.exitCode = 1;
};

const html = read('app/src/main/assets/index.html');
const scripts = [...html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/gi)].map(match => match[1]);
check('all embedded JavaScript parses', scripts.every((script, index) => {
  try { new vm.Script(script, { filename: `asset-script-${index}.js` }); return true; }
  catch { return false; }
}));

const ids = [...html.matchAll(/\bid=["']([^"']+)["']/g)].map(match => match[1]);
check('HTML ids are unique', new Set(ids).size === ids.length);
check('bottom sheets and dialogs stay above navigation', html.includes('.nav{left:10px;right:10px;bottom:calc(16px + env(safe-area-inset-bottom));z-index:100') && html.includes('.sheet{position:fixed;left:0;right:0;bottom:0;z-index:120') && html.includes('.cfScrim{position:fixed;inset:0;background:rgba(0,0,0,.72);z-index:130'));
check('closed bottom sheets cannot cast shadows over navigation', html.includes('.sheet:not(.on){visibility:hidden!important;box-shadow:none!important}'));
check('night mode and in-app replacement disclaimer are removed', !html.includes('Ночной режим') && !html.includes('nightOverlay') && !html.includes('Помощник, а не замена'));
check('calibration fields use examples instead of preset-looking values', html.includes('id="cKm" class="num" inputmode="numeric" placeholder="напр. 1"') && html.includes('id="cPk" class="num" inputmode="numeric" placeholder="напр. 1"') && html.includes('id="cM" class="num" inputmode="numeric" placeholder="напр. 0"') && html.includes('$("#cKm").value="";') && html.includes('$("#cPk").value="";') && html.includes('$("#cM").value="";'));
check('DU-61 practical reasons and power commands are present', ['Неисправность пути','Дефект рельса','Опустить токоприёмник','Поднять токоприёмник','Отключить ток','Включить ток','Неисправность средств СЦБ и связи','Негабаритный груз'].every(x => html.includes(x)));

const manifest = read('app/src/main/AndroidManifest.xml');
check('fine and coarse location declared', manifest.includes('ACCESS_FINE_LOCATION') && manifest.includes('ACCESS_COARSE_LOCATION'));
check('location foreground service declared', manifest.includes('FOREGROUND_SERVICE_LOCATION') && manifest.includes('foregroundServiceType="location"'));
check('service is not exported', /<service[\s\S]*?android:exported="false"/.test(manifest));
check('premium adaptive launcher art exists', fs.existsSync(new URL('app/src/main/res/drawable-nodpi/ic_launcher_art.png', root)));
check('APK header icon exists', fs.existsSync(new URL('app/src/main/assets/icons/icon-192.png', root)));
check('animated signal emblem is bundled and used', fs.existsSync(new URL('app/src/main/assets/icons/piket-signal.gif', root)) && html.includes('url("icons/piket-signal.gif")'));
check('bottom navigation uses crisp text and icons', html.includes('text-shadow:none!important') && html.includes('filter:none!important;shape-rendering:geometricPrecision'));
check('offline premium Manrope fonts exist', fs.existsSync(new URL('app/src/main/assets/assets/fonts/manrope-cyrillic.woff2', root)) && fs.existsSync(new URL('app/src/main/assets/assets/fonts/manrope-latin.woff2', root)));

const activity = read('app/src/main/java/net/raiuchi/piket/MainActivity.java');
check('WebView content access disabled', activity.includes('setAllowContentAccess(false)'));
check('WebView cross-file access disabled', activity.includes('setAllowUniversalAccessFromFileURLs(false)'));
check('web permission requests denied', activity.includes('request.deny()'));
check('tracking requires fine location', activity.includes('hasFineLocationPermission()'));
check('tracking resumes after permission grant', activity.includes('startTrackingAfterPermission') && activity.includes('onRequestPermissionsResult'));
check('permission callback is declared once', (activity.match(/void onRequestPermissionsResult\(/g) || []).length === 1);
check('external bridge allows only HTTP(S)', activity.includes('"https".equalsIgnoreCase(scheme)'));

const service = read('app/src/main/java/net/raiuchi/piket/TrackingService.java');
check('notification updates retain content intent', /updateNotificationText[\s\S]*?setContentIntent\(pi\)/.test(service));
check('accelerometer listener is unregistered', service.includes('unregisterListener(accelListener)'));
check('GNSS quality monitor is registered and released', service.includes('registerGnssStatusCallback') && service.includes('unregisterGnssStatusCallback'));
check('fresh high-frequency locations requested without suppressing degraded fixes', service.includes('new LocationRequest.Builder(1000)') && service.includes('.setMaxUpdateAgeMillis(0)') && service.includes('.setWaitForAccurateLocation(false)'));
check('watchdog keeps backup alive until a fresh real fused fix', service.includes('lastFusedRestartAt') && service.includes('now - lastFusedRestartAt > 15000') && !service.includes('lastFixReceivedAt = now;') && service.includes('if (isFreshRealFix(loc)) lastFixReceivedAt') && service.includes('ageMs <= 5000L && !mock'));
check('official kilometer offset is learned and isolated per route', html.includes('sap_routeOffsets') && html.includes('function routeOfficialOffset') && html.includes('function learnRouteOffset') && html.includes('officialTrackM(autoTM,state.ctx.peregon)'));
check('spline snapping continuously refines sub-segment position', html.includes('for(var refine=0;refine<10;refine++)') && html.includes('var refined=(left+right)/2'));
check('stale fixes and smooth recovery are implemented', html.includes('fixAge>5000') && html.includes('correctionTargetOdo') && html.includes('GPS восстановлен — плавно уточняю позицию'));
check('official chainage is separated from physical track', html.includes('var CHAINAGE =') && html.includes('function baseOfficialTrackM') && html.includes('official<=0 || Math.abs(official-physical)>3000'));
check('moving recovery follows confirmed physical GPS target', html.includes('targetTrackM=state.calib._trackM+(dirDown()?-1:1)*rt.correctionTargetOdo') && html.includes('plausibilityDiff=Math.abs(tMfinal-targetTrackM)') && html.includes('rt.correctionTargetOdo=newOdoVal'));
check('GPS jitter is filtered along the moving track without coordinate lag', html.includes('function stableAlongTrackCandidate') && html.includes('rt.gpsResiduals.length>5'));
check('dead-reckoning speed decay is based on elapsed time, not callback count', html.includes('decayPerSecond') && html.includes('Math.pow(decayPerSecond,lossDt)'));
check('train dynamics reject impossible acceleration and confirm speed recovery', html.includes('maxSpeedChange=Math.min(12*Math.max(dt,0.5)+5, 45)') && html.includes('rt.speedCandCount<2'));
check('confirmed large position recovery is immediate', html.includes('diff>=50 && signalGood && !satelliteWeak') && html.includes('rt.odo=newOdoVal; rt.correctionTargetOdo=null'));
check('PIKET RS premium red theme is present', html.includes('PIKET RS · единая спортивная премиум-тема') && html.includes('#F02D3A'));
check('speed reference uses red main and yellow side track palette', html.includes('🔴 Гл.п — главный путь · 🟡 Бок.п — боковой путь') && html.includes('.srBadge.glp{background:linear-gradient(180deg,rgba(240,45,58,.30)') && html.includes('.srBadge.bokp{background:linear-gradient(180deg,rgba(245,183,36,.28)') && !html.includes('rgba(47,157,235,.3)'));
check('restriction acknowledgement uses premium red styling', html.includes('background:linear-gradient(180deg,#f42b43 0%,#c8102e 58%,#8d071e 100%)'));
check('spoofing and poor Doppler data are rejected', service.includes('loc.isMock()') && service.includes('getSpeedAccuracyMetersPerSecond') && html.includes('mockLocation===true') && html.includes('poorDoppler'));
check('multi-constellation GNSS quality is evaluated', service.includes('getConstellationType') && html.includes('constellationDiversity'));

const gradle = read('app/build.gradle');
check('release version is 1.4.91', gradle.includes('versionName "1.4.91"') && gradle.includes('versionCode 97'));
const workflow = read('.github/workflows/build.yml');
check('release tags build release APK', workflow.includes('gradle assembleRelease') && workflow.includes('app-release.apk'));
check('release notes include generated GPS report', workflow.includes('tail -n +2 GPS_TEST_RESULTS.md'));
check('CI restores signing key from secret', workflow.includes('PIKET_KEYSTORE_B64') && workflow.includes('base64 --decode'));
check('signing key is not stored in repository tree', !fs.existsSync(new URL('app/piket-release.keystore', root)));

for (const result of checks) console.log(`${result.ok ? 'PASS' : 'FAIL'} ${result.name}`);
console.log(`${checks.filter(result => result.ok).length}/${checks.length} checks passed`);
