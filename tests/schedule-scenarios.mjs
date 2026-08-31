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
for (const addition of html.matchAll(/addTimingStation\("([^"]+)","([^"]+)",(\d+)(?:,"([^"]+)")?\);/g)) {
  const [, route, name, meterText, before] = addition;
  const rows = stations[route] || [];
  const at = before ? rows.findIndex(row => row[0] === before) : -1;
  rows.splice(at < 0 ? rows.length : at, 0, [name, Number(meterText)]);
}

function norm(value) {
  return String(value || '').toLowerCase().replace(/ё/g, 'е').replace(/[–—-]/g, '').replace(/[^a-zа-я0-9]/g, '').replace(/станция|московское|московский/g, '');
}
const aliasMatch = html.match(/var scheduleAliases=(\{[^;]+\});/);
if (!aliasMatch) throw new Error('scheduleAliases not found');
const aliases = vm.runInNewContext('(' + aliasMatch[1] + ')');
for (const override of html.matchAll(/scheduleAliases\["([^"]+)"\]="([^"]+)";/g)) aliases[override[1]] = override[2];
function mapped(stop, rows) {
  const n = norm(stop), a = aliases[n] || n;
  return rows.some(row => { const r = norm(row[0]); return r === a || r === n || (a.length > 5 && (r.includes(a) || a.includes(r))); });
}
function mappedRow(stop, rows) {
  const n = norm(stop), a = aliases[n] || n;
  return rows.find(row => { const r = norm(row[0]); return r === a || r === n || (a.length > 5 && (r.includes(a) || a.includes(r))); });
}
function seconds(value) {
  const p = value.split(':').map(Number);
  return p[0] * 3600 + p[1] * 60 + (p[2] || 0);
}

const failures = [];
const unresolved = new Map();
let maxAverage = 0;
if (schedules.trains.length !== 66) failures.push(`expected 66 trains, got ${schedules.trains.length}`);
if (new Set(schedules.trains.map(t => t.number)).size !== schedules.trains.length) failures.push('duplicate train numbers');
if (schedules.trains.reduce((n,t)=>n+t.stops.length,0) !== 2712) failures.push('station passage count differs from parsed PDF tables');
for (const [number,count] of Object.entries({751:49,754:49,804:34,819:61,820:61,841:40,842:39})) {
  const train=schedules.trains.find(t=>t.number===number);
  if (!train || train.stops.length!==count) failures.push(`${number}: expected ${count} PDF rows`);
}
for (const train of schedules.trains) {
  const rows = stations[train.route] || [];
  const count = train.stops.filter(stop => mapped(stop.station, rows)).length;
  // Some trains in the source PDF continue over lines that are not present in
  // the application's route atlas. They remain in the source archive but are
  // deliberately not offered in the UI until at least two stops can be mapped.
  if (count >= 2) {
    const matchedIndexes = train.stops.map((stop, index) => mapped(stop.station, rows) ? index : -1).filter(index => index >= 0);
    const first = matchedIndexes[0], last = matchedIndexes.at(-1);
    for (let i = first; i <= last; i += 1) {
      const stop = train.stops[i];
      if (!mapped(stop.station, rows)) {
        const trains = unresolved.get(stop.station) || [];
        trains.push(train.number);
        unresolved.set(stop.station, trains);
      }
    }
  }
  let previous = null, wraps = 0;
  for (const stop of train.stops) {
    const time = stop.dep || stop.arr;
    if (!time) continue;
    let current = seconds(time);
    if (previous != null && current < previous % 86400) { wraps += 1; current += 86400; }
    if (wraps > 1) failures.push(`${train.number}: non-monotonic schedule near ${stop.station}`);
    previous = current;
  }
  for (let i=0;i<train.stops.length-1;i++) {
    const current=train.stops[i], next=train.stops[i+1], a=mappedRow(current.station,rows), b=mappedRow(next.station,rows);
    const from=current.dep||current.arr, to=next.arr||next.dep;
    if (!a||!b||!from||!to) continue;
    let dt=seconds(to)-seconds(from); if (dt<=0) dt+=86400;
    const average=Math.abs(b[1]-a[1])/1000/(dt/3600);
    maxAverage=Math.max(maxAverage,average);
    // Несогласованность самого исходного PDF и километровой таблицы не скрываем:
    // интерфейс обязан пометить такие участки, а не советовать невозможную скорость.
  }
}
for (const [stationName, trainNumbers] of unresolved) {
  failures.push(`unresolved station ${stationName}: trains ${[...new Set(trainNumbers)].join(', ')}`);
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
console.log(`schedule scenarios: ${schedules.trains.length} source trains, ${selectableTotal} selectable on supported routes, ${schedules.trains.reduce((n,t)=>n+t.stops.length,0)} station passages, max average ${Math.round(maxAverage)} km/h`);
