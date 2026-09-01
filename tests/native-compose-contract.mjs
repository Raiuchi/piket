import fs from 'node:fs';

const root = new URL('../', import.meta.url);
const read = path => fs.readFileSync(new URL(path, root), 'utf8');
const json = path => JSON.parse(read(path));
const checks = [];
const check = (name, condition) => {
  checks.push({ name, ok: Boolean(condition) });
  if (!condition) process.exitCode = 1;
};

const activity = read('app/src/main/java/net/raiuchi/piket/MainActivity.kt');
const app = read('app/src/main/java/net/raiuchi/piket/PiketApp.kt');
const references = read('app/src/main/java/net/raiuchi/piket/NativeReferenceData.kt');
const screens = read('app/src/main/java/net/raiuchi/piket/NativeReferenceScreens.kt');
const calculator = read('app/src/main/java/net/raiuchi/piket/NativeTimetableCalculator.kt');
const service = read('app/src/main/java/net/raiuchi/piket/TrackingService.java');
const routeEngine = read('app/src/main/java/net/raiuchi/piket/NativeRouteEngine.kt');
const tripEngine = read('app/src/main/java/net/raiuchi/piket/NativeTripEngine.kt');
const manifest = read('app/src/main/AndroidManifest.xml');
const gradle = read('app/build.gradle');
const routes = json('app/src/main/assets/data/routes.json');
const schedules = json('app/src/main/assets/data/schedules.json');
const speeds = json('app/src/main/assets/data/speed-reference.json');
const timing = json('app/src/main/assets/data/timing.json');

check('activity uses Compose without WebView', activity.includes('ComponentActivity') && activity.includes('setContent') && !activity.includes('WebView'));
check('trip, restrictions and settings are native', ['TripScreen', 'RestrictionScreen', 'SettingsScreen'].every(name => app.includes(name)));
check('timetable and speed reference are native', screens.includes('NativeTimetableScreen') && screens.includes('NativeSpeedReferenceScreen'));
check('premium station time editor is native', screens.includes('PremiumTimeDialog') && app.includes('updateScheduleTime'));
check('running-time formula is isolated and guarded', calculator.includes('object NativeTimetableCalculator') && calculator.includes('average <= maxKmh'));
check('GPS route engine reads JSON', routeEngine.includes('fun fromJson') && service.includes('data/routes.json') && !service.includes('piket-core.js'));
check('native engines own motion, route and trip state', ['NativeMotionFilter', 'NativeRouteEngine', 'NativeTripEngine'].every(name => service.includes(name)));
check('native trip survives signal loss and recreation', tripEngine.includes('markSignalUnavailable') && service.includes('persistNativeTripState') && service.includes('restoreNativeTripState'));
check('official kilometer discontinuities remain explicit', routeEngine.includes('abs(official - physical) > 3_000.0'));
check('all 10 route geometries migrated', routes.schemaVersion === 1 && routes.tracks.labels.length === 10 && routes.tracks.segs.length === 10 && routes.chainage.length === 10);
check('all 1558 route points migrated', routes.tracks.segs.reduce((sum, segment) => sum + segment.length, 0) === 1558);
check('all 66 trains and 2712 passages migrated', schedules.trains.length === 66 && schedules.trains.reduce((sum, train) => sum + train.stops.length, 0) === 2712);
check('819 and 820 migrated', ['819', '820'].every(number => schedules.trains.some(train => train.number === number)));
check('all speed reference rows migrated', speeds.schemaVersion === 1 && speeds.routes.length === 15 && speeds.routes.reduce((sum, route) => sum + route.groups.reduce((n, group) => n + group.rows.length, 0), 0) === 1306);
check('through routes are implemented in Kotlin', ['chudovoThroughStations', 'dachaThroughStations', 'vyborgThroughStations'].every(name => references.includes(name)));
check('timing chains migrated', timing.schemaVersion === 1 && Object.keys(timing.stations).length === 10 && timing.railChains.length === 4);
check('main and side speed colors are red and yellow', screens.includes('PiketRedDark') && screens.includes('Color(0xFF735411)'));
check('location foreground service is private', manifest.includes('FOREGROUND_SERVICE_LOCATION') && manifest.includes('android:exported="false"'));
check('Android backup is disabled', manifest.includes('android:allowBackup="false"'));
check('keep-screen is controlled by Android window', activity.includes('FLAG_KEEP_SCREEN_ON'));
check('Compose is enabled', gradle.includes('compose true') && gradle.includes('compose-bom'));

for (const result of checks) console.log(`${result.ok ? 'PASS' : 'FAIL'} ${result.name}`);
console.log(`${checks.filter(result => result.ok).length}/${checks.length} native checks passed`);
