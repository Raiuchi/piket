import fs from 'node:fs';

const root = new URL('../', import.meta.url);
const routes = JSON.parse(fs.readFileSync(new URL('app/src/main/assets/data/routes.json', root), 'utf8'));
const schedules = JSON.parse(fs.readFileSync(new URL('app/src/main/assets/data/schedules.json', root), 'utf8'));
const timing = JSON.parse(fs.readFileSync(new URL('app/src/main/assets/data/timing.json', root), 'utf8'));
const failures = [];

for (let index = 0; index < routes.tracks.labels.length; index += 1) {
  const points = routes.tracks.segs[index];
  const axis = routes.chainage[index];
  if (points.length < 2 || points.length !== axis.length) failures.push(`${routes.tracks.labels[index]}: geometry/axis mismatch`);
  if (points.some(point => point.length !== 3 || point.some(value => !Number.isFinite(value)))) failures.push(`${routes.tracks.labels[index]}: invalid coordinate`);
}
if (new Set(schedules.trains.map(train => train.number)).size !== schedules.trains.length) failures.push('duplicate train numbers');
for (const [number, expected] of Object.entries({ 751: 49, 754: 49, 804: 34, 819: 61, 820: 61, 841: 40, 842: 39 })) {
  const train = schedules.trains.find(item => item.number === number);
  if (!train || train.stops.length !== expected) failures.push(`${number}: expected ${expected} schedule rows`);
}
for (const train of schedules.trains) {
  let previous = null;
  let wraps = 0;
  for (const stop of train.stops) {
    const value = stop.dep || stop.arr;
    if (!value) continue;
    const parts = value.split(':').map(Number);
    const clock = parts[0] * 3600 + parts[1] * 60 + (parts[2] || 0);
    if (previous != null && clock < previous % 86400) wraps += 1;
    const current = clock + wraps * 86400;
    if (previous != null && current < previous) failures.push(`${train.number}: non-monotonic time near ${stop.station}`);
    previous = current;
  }
  if (wraps > 1) failures.push(`${train.number}: more than one midnight transition`);
}
if (timing.railChains.length !== 4) failures.push('expected four through-route chains');
if (failures.length) { console.error(failures.join('\n')); process.exit(1); }
console.log('native JSON scenarios: routes, schedules and through chains are consistent');
