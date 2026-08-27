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

const gradle = read('app/build.gradle');
check('release version is 1.4.86', gradle.includes('versionName "1.4.86"') && gradle.includes('versionCode 92'));
const workflow = read('.github/workflows/build.yml');
check('release tags build release APK', workflow.includes('gradle assembleRelease') && workflow.includes('app-release.apk'));
check('CI restores signing key from secret', workflow.includes('PIKET_KEYSTORE_B64') && workflow.includes('base64 --decode'));
check('signing key is not stored in repository tree', !fs.existsSync(new URL('app/piket-release.keystore', root)));

for (const result of checks) console.log(`${result.ok ? 'PASS' : 'FAIL'} ${result.name}`);
console.log(`${checks.filter(result => result.ok).length}/${checks.length} checks passed`);
