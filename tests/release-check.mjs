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
check('night mode and in-app replacement disclaimer are removed', !html.includes('Ночной режим') && !html.includes('nightOverlay') && !html.includes('Помощник, а не замена'));
check('calibration fields use examples instead of preset-looking values', html.includes('id="cKm" class="num" inputmode="numeric" placeholder="напр. 1"') && html.includes('id="cPk" class="num" inputmode="numeric" placeholder="напр. 1"') && html.includes('id="cM" class="num" inputmode="numeric" placeholder="напр. 0"') && html.includes('$("#cKm").value="";') && html.includes('$("#cPk").value="";') && html.includes('$("#cM").value="";'));
check('DU-61 practical reasons and power commands are present', ['Неисправность пути','Дефект рельса','Опустить токоприёмник','Поднять токоприёмник','Отключить ток','Включить ток','Неисправность средств СЦБ и связи','Негабаритный груз'].every(x => html.includes(x)));

const manifest = read('app/src/main/AndroidManifest.xml');
check('fine and coarse location declared', manifest.includes('ACCESS_FINE_LOCATION') && manifest.includes('ACCESS_COARSE_LOCATION'));
check('location foreground service declared', manifest.includes('FOREGROUND_SERVICE_LOCATION') && manifest.includes('foregroundServiceType="location"'));
check('service is not exported', /<service[\s\S]*?android:exported="false"/.test(manifest));
check('premium adaptive launcher art exists', fs.existsSync(new URL('app/src/main/res/drawable-nodpi/ic_launcher_art.png', root)));
check('APK header icon exists', fs.existsSync(new URL('app/src/main/assets/icons/icon-192.png', root)));
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
check('fresh high-frequency locations requested', service.includes('new LocationRequest.Builder(1000)') && service.includes('.setMaxUpdateAgeMillis(0)') && service.includes('.setWaitForAccurateLocation(true)'));
check('stale fixes and smooth recovery are implemented', html.includes('fixAge>5000') && html.includes('correctionTargetOdo') && html.includes('GPS восстановлен — плавно уточняю позицию'));
check('spoofing and poor Doppler data are rejected', service.includes('loc.isMock()') && service.includes('getSpeedAccuracyMetersPerSecond') && html.includes('mockLocation===true') && html.includes('poorDoppler'));
check('multi-constellation GNSS quality is evaluated', service.includes('getConstellationType') && html.includes('constellationDiversity'));

const gradle = read('app/build.gradle');
check('release version is 1.4.89', gradle.includes('versionName "1.4.89"') && gradle.includes('versionCode 95'));
const workflow = read('.github/workflows/build.yml');
check('release tags build release APK', workflow.includes('gradle assembleRelease') && workflow.includes('app-release.apk'));
check('release notes include generated GPS report', workflow.includes('tail -n +2 GPS_TEST_RESULTS.md'));
check('CI restores signing key from secret', workflow.includes('PIKET_KEYSTORE_B64') && workflow.includes('base64 --decode'));
check('signing key is not stored in repository tree', !fs.existsSync(new URL('app/piket-release.keystore', root)));

for (const result of checks) console.log(`${result.ok ? 'PASS' : 'FAIL'} ${result.name}`);
console.log(`${checks.filter(result => result.ok).length}/${checks.length} checks passed`);
