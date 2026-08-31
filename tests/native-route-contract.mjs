import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('../app/src/main/assets/assets/piket-core.js', import.meta.url), 'utf8');
const context = {};
vm.createContext(context);
vm.runInContext(source, context);
const { TRACK, CHAINAGE } = context;

let checked = 0;
let transitions = 0;
const officialMeters = (routeIndex, physicalM) => {
  const points = TRACK.segs[routeIndex], axis = CHAINAGE[routeIndex];
  if (physicalM <= points[0][2]) return axis[0] + physicalM - points[0][2];
  for (let i = 0; i < points.length - 1; i++) {
    const a = points[i][2], b = points[i + 1][2];
    if (physicalM > b) continue;
    const physical = b - a, official = axis[i + 1] - axis[i];
    if (official <= 0 || Math.abs(official - physical) > 3000) {
      return physicalM >= b - 1 ? axis[i + 1] : axis[i] + physicalM - a;
    }
    return axis[i] + (physicalM - a) / physical * official;
  }
  return axis.at(-1) + physicalM - points.at(-1)[2];
};

if (TRACK.labels.length !== 10 || CHAINAGE.length !== 10) throw new Error('route count mismatch');
TRACK.segs.forEach((points, routeIndex) => {
  if (points.length !== CHAINAGE[routeIndex].length) throw new Error(`axis length mismatch: ${TRACK.labels[routeIndex]}`);
  points.forEach((point, pointIndex) => {
    const actual = officialMeters(routeIndex, point[2]);
    const expected = CHAINAGE[routeIndex][pointIndex];
    if (Math.abs(actual - expected) > 0.001) throw new Error(`control point mismatch: ${TRACK.labels[routeIndex]} #${pointIndex}`);
    checked++;
  });
  for (let i = 0; i < points.length - 1; i++) {
    const physical = points[i + 1][2] - points[i][2];
    const official = CHAINAGE[routeIndex][i + 1] - CHAINAGE[routeIndex][i];
    if (official <= 0 || Math.abs(official - physical) > 3000) {
      transitions++;
      const before = points[i + 1][2] - 2;
      const expectedBefore = CHAINAGE[routeIndex][i] + before - points[i][2];
      if (Math.abs(officialMeters(routeIndex, before) - expectedBefore) > 0.001) throw new Error('transition stretched');
    }
  }
});

if (checked !== 1558) throw new Error(`expected 1558 points, got ${checked}`);
if (transitions < 5) throw new Error(`expected production transitions, got ${transitions}`);
console.log(`PASS native route contract: ${TRACK.labels.length}/10 routes, ${checked}/1558 points, ${transitions} axis transitions`);
