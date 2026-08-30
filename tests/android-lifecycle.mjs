import fs from 'node:fs';

const root = new URL('../', import.meta.url);
const activity = fs.readFileSync(new URL('app/src/main/java/net/raiuchi/piket/MainActivity.java', root), 'utf8');
const service = fs.readFileSync(new URL('app/src/main/java/net/raiuchi/piket/TrackingService.java', root), 'utf8');
const html = fs.readFileSync(new URL('app/src/main/assets/index.html', root), 'utf8');
const manifest = fs.readFileSync(new URL('app/src/main/AndroidManifest.xml', root), 'utf8');

const checks = [
  ['экран не удерживается безусловно при запуске', !activity.slice(0, activity.indexOf('class PiketBridge')).includes('addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)')],
  ['JS-мост умеет включать и выключать экран', activity.includes('public void setKeepScreen(final boolean enabled)') && activity.includes('clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)')],
  ['переключатель экрана применяется во время поездки', html.includes('key==="wake"&&rt.tracking') && html.includes('window.Android.setKeepScreen(false)')],
  ['старт передаётся foreground-службе', activity.includes('startForegroundService') && service.includes('forceHeadlessStart()')],
  ['перекалибровка передаётся живой службе', activity.includes('ACTION_RECALIBRATE') && service.includes('forceHeadlessRecalibrate()')],
  ['служба останавливается по команде и при смахивании', activity.includes('stopService(new Intent(this, TrackingService.class))') && service.includes('onTaskRemoved') && service.includes('stopSelf()')],
  ['ресурсы освобождаются в onDestroy', ['removeLocationUpdates','unregisterListener','unregisterGnssStatusCallback','wakeLock.release()','tts.shutdown()','headlessWeb.destroy()'].every(x => service.includes(x))],
  ['Android Backup отключён', manifest.includes('android:allowBackup="false"') && manifest.includes('android:fullBackupContent="false"')]
];

for (const [name, ok] of checks) console.log(`${ok ? 'PASS' : 'FAIL'} ${name}`);
const passed = checks.filter(([, ok]) => ok).length;
console.log(`${passed}/${checks.length} Android lifecycle checks passed`);
if (passed !== checks.length) process.exitCode = 1;
