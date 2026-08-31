import fs from 'node:fs';
import vm from 'node:vm';

const root = new URL('../', import.meta.url);
const html = fs.readFileSync(new URL('app/src/main/assets/index.html', root), 'utf8');
const asset = fs.readFileSync(new URL('app/src/main/assets/assets/piket-schedules.js', root), 'utf8');
const context = { window: {} };
vm.runInNewContext(asset, context);
const schedules = context.window.PIKET_SCHEDULES;
const match = html.match(/var TIMING_STATIONS=(\{[\s\S]*?\n  \});/);
if (!match) throw new Error('TIMING_STATIONS not found');
const stations = vm.runInNewContext('(' + match[1] + ')');

function norm(value) {
  return String(value || '').toLowerCase().replace(/ё/g, 'е').replace(/[–—-]/g, '').replace(/[^a-zа-я0-9]/g, '').replace(/станция|пассажирский|пасс|московское|московский|моск/g, '');
}
const aliases = {спетербурггл:'санктпетербургглавный',спетербургтм:'санктпетербургсортировочныймосковский',спсмпобухово:'обухово',чудово:'чудовомосковское',бологое:'бологоемосковское',москватовокт:'москватоварная',мурманскворота:'мурманскиеворота',оятволховстр:'оят',бп284км:'пост284км',выборг:'выборгпассажирский',спполисть:'спасскаяполисть',предузпавловск:'предузловаяпавловская',новгородпост:'новгородтранспортныйпост'};
function mapped(stop, rows) {
  const n = norm(stop), a = aliases[n] || n;
  return rows.some(row => { const r = norm(row[0]); return r === a || r === n || (a.length > 5 && (r.includes(a) || a.includes(r))); });
}
function seconds(value) {
  const p = value.split(':').map(Number);
  return p[0] * 3600 + p[1] * 60 + (p[2] || 0);
}

const failures = [];
if (schedules.trains.length !== 66) failures.push(`expected 66 trains, got ${schedules.trains.length}`);
if (new Set(schedules.trains.map(t => t.number)).size !== schedules.trains.length) failures.push('duplicate train numbers');
for (const train of schedules.trains) {
  const rows = stations[train.route] || [];
  const count = train.stops.filter(stop => mapped(stop.station, rows)).length;
  // Some trains in the source PDF continue over lines that are not present in
  // the application's route atlas. They remain in the source archive but are
  // deliberately not offered in the UI until at least two stops can be mapped.
  let previous = null, wraps = 0;
  for (const stop of train.stops) {
    const time = stop.dep || stop.arr;
    if (!time) continue;
    let current = seconds(time);
    if (previous != null && current < previous % 86400) { wraps += 1; current += 86400; }
    if (wraps > 1) failures.push(`${train.number}: non-monotonic schedule near ${stop.station}`);
    previous = current;
  }
}
let selectableTotal = 0;
for (const route of ['СпбГл - Москва','Горы - Петрозаводск','Броневая - Луга','Чудово - Новгород','СПбФин - Выборг']) {
  for (const direction of ['tuda','obratno']) {
    const selectable = schedules.trains.filter(train => train.route === route && train.direction === direction && train.stops.filter(stop => mapped(stop.station, stations[route] || [])).length >= 2);
    selectableTotal += selectable.length;
    if (!selectable.length) failures.push(`${route}/${direction}: no selectable trains`);
  }
}
if (failures.length) {
  console.error(failures.join('\n'));
  process.exit(1);
}
console.log(`schedule scenarios: ${schedules.trains.length} source trains, ${selectableTotal} selectable on supported routes, ${schedules.trains.reduce((n,t)=>n+t.stops.length,0)} station passages`);
