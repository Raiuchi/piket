import fs from 'node:fs';
import path from 'node:path';

const input = process.argv[2];
if (!input) {
  console.error('Usage: node tools/replay-diagnostics.mjs <piket-diagnostics.txt>');
  process.exit(2);
}

const text = fs.readFileSync(input, 'utf8');
const events = text.split(/\r?\n/).map(line => line.trim())
  .filter(line => line.startsWith('{')).map((line, index) => {
    try { return JSON.parse(line); }
    catch (error) { throw new Error(`Invalid JSON at diagnostic row ${index + 1}: ${error.message}`); }
  });

const samples = events.filter(e => e.event === 'gps_sample' || e.event === 'gps_quality_changed');
const moving = samples.filter(e => Number(e.provider_speed_kmh) >= 5);
let frozenSpeed = 0;
let frozenPosition = 0;
let prior = null;
for (const e of moving) {
  if (Number(e.filtered_speed_kmh) === 0) frozenSpeed++;
  if (prior && Number.isFinite(e.snapped_physical_m) && Number.isFinite(prior.snapped_physical_m) &&
      Math.abs(e.snapped_physical_m - prior.snapped_physical_m) >= 25 &&
      Number.isFinite(e.engine_physical_m) && Number.isFinite(prior.engine_physical_m) &&
      Math.abs(e.engine_physical_m - prior.engine_physical_m) < 2) frozenPosition++;
  prior = e;
}
const configurations = events.filter(e => e.event === 'trip_configured');
let rapidConfigurationReplacements = 0;
for (let index = 1; index < configurations.length; index++) {
  const previous = configurations[index - 1], current = configurations[index];
  const previousKey = [previous.route, previous.direction, previous.train].join('|');
  const currentKey = [current.route, current.direction, current.train].join('|');
  if (Number(current.time) - Number(previous.time) <= 1_500 && previousKey !== currentKey)
    rapidConfigurationReplacements++;
}
const power = events.filter(e => e.event === 'power_sample');
const temperatures = power.map(e => Number(e.battery_temperature_c)).filter(Number.isFinite);
const reconciliations = events.filter(e => e.event === 'position_reconciled');
const corrections = reconciliations.map(e => Math.abs(Number(e.correction_m))).filter(Number.isFinite);
const transitionProbes = events.filter(e => e.event === 'route_transition_probe');
const transitionProbeStatuses = Object.fromEntries([...new Set(transitionProbes.map(e => e.status))]
  .sort().map(status => [status, transitionProbes.filter(e => e.status === status).length]));
const scheduleCards = events.filter(e => e.event === 'schedule_card_changed');
const report = {
  file: path.basename(input),
  events: events.length,
  gpsSamples: samples.length,
  movingSamples: moving.length,
  movingSamplesWithFilteredZero: frozenSpeed,
  frozenPositionSteps: frozenPosition,
  routeTransitions: events.filter(e => e.event === 'route_transition').length,
  routeTransitionProbes: transitionProbes.length,
  routeTransitionProbeStatuses: transitionProbeStatuses,
  positionReconciliations: reconciliations.length,
  maxPositionCorrectionM: corrections.length ? Math.max(...corrections) : null,
  scheduleCardChanges: scheduleCards.length,
  rapidConfigurationReplacements,
  gpsReserveStarts: events.filter(e => e.event === 'direct_gps_started').length,
  gpsReserveStops: events.filter(e => e.event === 'direct_gps_stopped').length,
  restrictionAlerts: events.filter(e => e.event === 'restriction_alert').length,
  maxBatteryTemperatureC: temperatures.length ? Math.max(...temperatures) : null
};
console.log(JSON.stringify(report, null, 2));

// A historical log may legitimately expose a regression. The replay command reports it
// without failing. CI/synthetic fixtures can request strict expectations explicitly.
if (process.argv.includes('--expect-clean') &&
    (frozenSpeed > 0 || frozenPosition > 0 || rapidConfigurationReplacements > 0)) process.exit(1);
