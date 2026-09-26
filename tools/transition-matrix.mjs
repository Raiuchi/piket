import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';

// Matrix over the functions and route data shipped in the APK/Web bundle.
// This intentionally exercises production nextInChain/boundary clamping instead
// of maintaining a second transition implementation in the test.
const html = fs.readFileSync('app/src/main/assets/index.html', 'utf8');
const routes = JSON.parse(fs.readFileSync('app/src/main/assets/data/routes.json', 'utf8'));
const timing = JSON.parse(fs.readFileSync('app/src/main/assets/data/timing.json', 'utf8'));
const box = {
  TRACK: routes.tracks,
  RAILCHAINS: timing.railChains,
  state: {ctx: {peregon: '', towards: 'tuda'}},
  through: null,
  journeyDirection: 'tuda'
};
box.activeThrough = () => box.through;
box.journeyTowards = () => box.journeyDirection;
box.dirDown = () => box.state.ctx.towards === 'obratno';
vm.createContext(box);
for (const name of ['nextInChain', 'boundedTrackMetersForTransition']) {
  const start = html.indexOf(`  function ${name}(`);
  assert.ok(start >= 0, `${name} must be shipped`);
  const end = html.indexOf('\n  function ', start + 1);
  vm.runInContext(html.slice(start, end), box);
}

let scenarios = 0;
for (const chain of timing.railChains) {
  for (let index = 0; index < chain.chain.length; index++) {
    const route = chain.chain[index];
    assert.ok(routes.tracks.labels.includes(route), `${route}: geometry exists`);
    box.through = null;
    box.journeyDirection = chain.towards;
    box.state.ctx = {peregon: route, towards: chain.towards};
    const next = box.nextInChain();
    if (index === chain.chain.length - 1) {
      assert.equal(next, null, `${route}: final leg must not transition again`);
      scenarios++;
      continue;
    }
    assert.equal(next?.label, chain.chain[index + 1], `${route}: next leg`);
    const routeIndex = routes.tracks.labels.indexOf(route);
    const segment = routes.tracks.segs[routeIndex];
    const boundary = next.boundaryM ?? (chain.towards === 'obratno' ? segment[0][2] : segment.at(-1)[2]);
    for (const errorM of [50, 100, 200, 300, 2_000]) {
      const beyond = boundary + (chain.towards === 'obratno' ? -errorM : errorM);
      assert.equal(box.boundedTrackMetersForTransition(beyond), boundary,
        `${route}: ${chain.towards} overshoot ${errorM} m is held at junction`);
      scenarios++;
    }
  }
}

// Chudovo/Volkhov duty route is selected by a separate through-journey context.
const chudovo = [
  ['tuda', 'Волховстрой - Чудово', 'obratno', 'Горы - Петрозаводск', null],
  ['obratno', 'Горы - Петрозаводск', 'obratno', 'Волховстрой - Чудово', 124400],
  ['obratno', 'Волховстрой - Чудово', 'tuda', 'Чудово - Новгород', 101000],
  ['obratno', 'Чудово - Новгород', 'tuda', 'Чудово - Новгород', 75175]
];
for (const [duty, route, routeTowards, expected, boundary] of chudovo) {
  box.through = 'chudovo';
  box.journeyDirection = duty;
  box.state.ctx = {peregon: route, towards: routeTowards};
  const next = box.nextInChain();
  assert.equal(next?.label, expected, `${route}: Chudovo duty next leg`);
  if (boundary != null) assert.equal(next?.boundaryM, boundary, `${route}: documented boundary`);
  scenarios++;
}

assert.ok(html.includes('rt.peregonCandCount>=2'), 'every automatic transition needs two fixes');
assert.ok(html.includes('(chainNext.cabChange||chainNext.trainChange)&&rt.speed>5'),
  'cab/train changes must wait until the train is stopped');
assert.ok(html.includes('boundaryToleranceM:1500'),
  'Vyborg must tolerate a stopped platform position offset');

console.log(`Transition matrix: ${scenarios} forward/reverse boundary and GPS-error scenarios passed`);
