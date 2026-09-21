import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';

// Execute the shipped browser functions, not a second implementation of them.
const html = fs.readFileSync('app/src/main/assets/index.html', 'utf8');
const routes = JSON.parse(fs.readFileSync('app/src/main/assets/data/routes.json', 'utf8'));
const timing = JSON.parse(fs.readFileSync('app/src/main/assets/data/timing.json','utf8'));
const box = {TRACK: routes.tracks, CHAINAGE: routes.chainage,
  RAILCHAINS: timing.railChains, TIMING_STATIONS: timing.stations,
  state:{ctx:{peregon:'Павлово - Горы II путь'}}, rt:{tracking:true,posM:31000,physicalM:0},
  activeThrough:()=> 'dacha'};
box.journeyTowards=()=>box.state.ctx.towards||'tuda';
vm.createContext(box);
for (const name of ['exactAxisProfile','profileOfficial','baseOfficialTrackM','officialToTrackCandidates','officialToTrackM','restrictionAxisPlace','restrictionAxisOptions','scheduleScale','scheduleLiveM','isDachaLeg','normalizeRestrictionRoutes']) {
  const start = html.indexOf(`  function ${name}(`);
  assert.ok(start >= 0, name);
  const end = html.indexOf('\n  function ', start + 1);
  vm.runInContext(html.slice(start, end), box);
}
box.metersOf=(km,pk=1,m=0)=>km*1000+(pk-1)*100+(m||0);
box.state.restrictions=[{peregon:'Д. Долг - Павлово',km:216,pk:6,kmE:216,pkE:7},
  {peregon:'Д. Долг - Павлово',km:19,pk:9}];
box.normalizeRestrictionRoutes();
assert.equal(box.state.restrictions[0].peregon,'Горы - Петрозаводск');
assert.equal(box.state.restrictions[0].previousPeregon,'Д. Долг - Павлово');
assert.equal(box.state.restrictions[1].peregon,'Д. Долг - Павлово');
box.normalizeRestrictionRoutes();
assert.equal(box.state.restrictions[0].peregon,'Горы - Петрозаводск');
for (const label of ['Д. Долг - Павлово','Павлово - Горы II путь']) {
  assert.equal(box.officialToTrackM(190000,label,28000),null);
}
assert.ok(box.officialToTrackM(33000,'Павлово - Горы II путь',33000) !== null,
  'the old axis immediately before Gory must still resolve');
assert.equal(box.officialToTrackM(1000,'Д. Долг - Павлово',1000),1000,
  'the supported short prefix before the first map point remains usable');
const duplicateAxes=box.restrictionAxisOptions('Д. Долг - Павлово',2300,2300,'tuda');
assert.ok(duplicateAxes.length>=2,'a repeated 2 km mark must require an explicit axis choice');
assert.notEqual(duplicateAxes[0].trackStartM,duplicateAxes[1].trackStartM);
box.state.ctx.towards='tuda';
assert.ok(Math.abs(box.officialToTrackM(7400,'Д. Долг - Павлово',6073)-6073)<2);
assert.ok(Math.abs(box.officialToTrackM(2300,'Д. Долг - Павлово',6074)-6074)<2);
assert.ok(Math.abs(box.officialToTrackM(42800,'Павлово - Горы II путь',33500)-33500)<2);
box.state.ctx.towards='obratno';
assert.ok(Math.abs(box.officialToTrackM(6400,'Д. Долг - Павлово',6073)-6073)<2);
assert.ok(Math.abs(box.officialToTrackM(42800,'Горы - Павлово I путь',33500)-33500)<2);
for (const [route,a,b] of [['Павлово - Горы II путь',29807,33500],['Горы - Павлово I путь',28200,33500]]) {
  box.state.ctx.peregon=route;
  for (const [physical,expected] of [[a,29200],[(a+b)/2,31600],[b,34000]]) {
    box.rt.physicalM=physical;
    assert.equal(box.scheduleLiveM(),expected,`${route} ${physical}`);
  }
}
assert.ok(html.includes('boundaryM:128900,cabChange:true') && html.includes('boundaryM:1000,cabChange:true'),
  'Vyborg must switch at the documented stopped junction in both directions');
assert.ok(html.includes('boundaryM:124400') && html.includes('boundaryM:101000,cabChange:true') &&
  html.includes('boundaryM:75175,trainChange'),
  'Volkhov, Chudovo and Novgorod must use their production junction boundaries');
for (let routeIndex=0;routeIndex<routes.tracks.labels.length;routeIndex++) {
  const label=routes.tracks.labels[routeIndex],points=routes.tracks.segs[routeIndex],axis=routes.chainage[routeIndex];
  assert.equal(points.length,axis.length,`${label}: route and official axis sizes`);
  if (box.exactAxisProfile(label,'tuda')) continue; // Direction-specific memo profiles are checked above.
  for (let pointIndex=0;pointIndex<points.length;pointIndex++) {
    const physical=points[pointIndex][2];
    assert.ok(Math.abs(box.baseOfficialTrackM(physical,label,'tuda')-axis[pointIndex])<0.01,
      `${label}: forward control point ${pointIndex}`);
    assert.ok(Math.abs(box.baseOfficialTrackM(physical,label,'obratno')-axis[pointIndex])<0.01,
      `${label}: reverse control point ${pointIndex}`);
  }
}

const moscowIndex=routes.tracks.labels.indexOf('СпбГл - Москва');
assert.ok(moscowIndex>=0,'Moscow route must exist');
const moscowPhysical=routes.tracks.segs[moscowIndex].map(point=>point[2]);
const moscowAxis=routes.chainage[moscowIndex];
const axisJumpIndex=moscowPhysical.indexOf(204230);
assert.ok(axisJumpIndex>=0,'Moscow 205/210 km axis transition must exist');
assert.equal(moscowAxis[axisJumpIndex],205000);
assert.equal(moscowPhysical[axisJumpIndex+1],204353);
assert.equal(moscowAxis[axisJumpIndex+1],210000,
  'Moscow axis must switch 205 -> 210 in the forward direction and 210 -> 205 in reverse');

const activity=fs.readFileSync('app/src/main/java/net/raiuchi/piket/MainActivity.kt','utf8');
assert.ok(activity.includes('handler.postDelayed(this, 1_000)') && !activity.includes('THERMAL_STATUS_SEVERE) 3_000'),
  'thermal mode must keep speed and kilometer telemetry at one-second cadence');
assert.ok(html.includes('function renderTripTelemetry()') && html.includes('fullRenderEvery=rt.thermalHot?5000:3000') &&
  html.includes('function renderTripAdaptive(force)') &&
  html.includes('rt.posM=currentMeters(); renderTripAdaptive(false); evalAlerts();'),
  'APK and browser thermal modes must throttle only expensive structural redraws');
assert.ok(html.includes('state.settings.screenMode="auto";delete state.settings.wake;persist();syncSw();') &&
  html.includes('Автозатемнение включено, яркость снижена'),
  'accepting the heat warning must persist Auto dimming in settings');

const schedules=JSON.parse(fs.readFileSync('app/src/main/assets/data/schedules.json','utf8')).trains;
const vyborgForward=schedules.filter(train=>train.route==='СПбФин - Выборг'&&train.direction==='tuda').map(train=>train.number);
const vyborgReverse=schedules.filter(train=>train.route==='СПбФин - Выборг'&&train.direction==='obratno').map(train=>train.number);
assert.deepEqual(vyborgForward,['821','823','825'],'Finland Station forward must keep only odd trains');
assert.deepEqual(vyborgReverse,['822','824','826'],'Finland Station reverse must keep only even trains');
assert.ok(html.includes('function scheduleTrainKey(){return scheduleSourceRoute()+"|"+journeyTowards()+"|"+(activeThrough()||"direct");}'),
  'saved train choices must remain isolated by route, direction and through journey');

console.log('Browser route regression: axes, trains, Moscow 205/210 transition, thermal UI and all documented through-route junctions passed');
