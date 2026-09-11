import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';

// Execute the shipped browser functions, not a second implementation of them.
const html = fs.readFileSync('app/src/main/assets/index.html', 'utf8');
const routes = JSON.parse(fs.readFileSync('app/src/main/assets/data/routes.json', 'utf8'));
const box = {TRACK: routes.tracks, CHAINAGE: routes.chainage,
  RAILCHAINS: JSON.parse(fs.readFileSync('app/src/main/assets/data/timing.json','utf8')).railChains,
  state:{ctx:{peregon:'Павлово - Горы II путь'}}, rt:{tracking:true,posM:31000,physicalM:0},
  activeThrough:()=> 'dacha'};
vm.createContext(box);
for (const name of ['officialToTrackM','scheduleScale','scheduleLiveM','isDachaLeg','normalizeRestrictionRoutes']) {
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
for (const [route,a,b] of [['Павлово - Горы II путь',29807,33500],['Горы - Павлово I путь',28200,33500]]) {
  box.state.ctx.peregon=route;
  for (const [physical,expected] of [[a,29200],[(a+b)/2,31600],[b,34000]]) {
    box.rt.physicalM=physical;
    assert.equal(box.scheduleLiveM(),expected,`${route} ${physical}`);
  }
}
console.log('Browser route regression: remote restrictions, pre-reset axis, Pavlovo/Gory endpoints and midpoint in both directions passed');
