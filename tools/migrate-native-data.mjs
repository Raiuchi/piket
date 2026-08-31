import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const assets = path.join(root, 'app', 'src', 'main', 'assets');
const out = path.join(assets, 'data');

function balancedValue(source, marker) {
  const markerAt = source.indexOf(marker);
  if (markerAt < 0) throw new Error(`Marker not found: ${marker}`);
  const equalsAt = source.indexOf('=', markerAt + marker.length);
  const start = source.slice(equalsAt + 1).search(/[\[{]/) + equalsAt + 1;
  const open = source[start];
  const close = open === '{' ? '}' : ']';
  let depth = 0;
  let string = false;
  let escaped = false;
  for (let index = start; index < source.length; index += 1) {
    const char = source[index];
    if (string) {
      if (escaped) escaped = false;
      else if (char === '\\') escaped = true;
      else if (char === '"') string = false;
      continue;
    }
    if (char === '"') string = true;
    else if (char === open) depth += 1;
    else if (char === close && --depth === 0) return source.slice(start, index + 1);
  }
  throw new Error(`Unclosed value: ${marker}`);
}

function parse(source, marker) {
  const literal = balancedValue(source, marker);
  try {
    return JSON.parse(literal);
  } catch {
    // Legacy configuration used JavaScript object keys without quotes.
    // Evaluate only the already isolated object/array literal from this repository.
    return Function(`"use strict"; return (${literal});`)();
  }
}

function write(name, value) {
  fs.mkdirSync(out, { recursive: true });
  fs.writeFileSync(path.join(out, name), `${JSON.stringify(value)}\n`, 'utf8');
}

const core = fs.readFileSync(path.join(assets, 'assets', 'piket-core.js'), 'utf8');
const html = fs.readFileSync(path.join(assets, 'index.html'), 'utf8');
const schedules = fs.readFileSync(path.join(assets, 'assets', 'piket-schedules.js'), 'utf8');

write('routes.json', {
  schemaVersion: 1,
  tracks: parse(core, 'var TRACK'),
  chainage: parse(core, 'var CHAINAGE')
});
write('schedules.json', parse(schedules, 'window.PIKET_SCHEDULES'));
write('speed-reference.json', { schemaVersion: 1, routes: parse(html, 'var SPEEDROUTES') });
write('timing.json', {
  schemaVersion: 1,
  stations: parse(html, 'var TIMING_STATIONS'),
  railChains: parse(html, 'var RAILCHAINS'),
  routeLinks: parse(html, 'var ROUTE_LINK')
});

console.log('Native JSON assets generated.');
