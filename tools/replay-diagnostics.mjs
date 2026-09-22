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
const finite = value => Number.isFinite(Number(value));
const eventTime = event => new Date(Number(event.time)).toISOString();
const issue = (severity, code, message, event, details = {}) => ({
  severity, code, message, time: event && finite(event.time) ? eventTime(event) : null,
  route: event?.route ?? null, tripSessionId: event?.trip_session_id ?? null, ...details
});
const issues = [];

const samples = events.filter(e => e.event === 'gps_sample' || e.event === 'gps_quality_changed');
const moving = samples.filter(e => Number(e.provider_speed_kmh) >= 5);
let frozenSpeed = 0;
let frozenPosition = 0;
let prior = null;
for (const event of moving) {
  if (Number(event.filtered_speed_kmh) === 0) frozenSpeed++;
  if (prior && finite(event.snapped_physical_m) && finite(prior.snapped_physical_m) &&
      Math.abs(event.snapped_physical_m - prior.snapped_physical_m) >= 25 &&
      finite(event.engine_physical_m) && finite(prior.engine_physical_m) &&
      Math.abs(event.engine_physical_m - prior.engine_physical_m) < 2) frozenPosition++;
  prior = event;
}
if (frozenSpeed) issues.push(issue('warning', 'moving-speed-filtered-to-zero',
  `${frozenSpeed} движущихся GPS-отсчётов получили нулевую фильтрованную скорость`, moving.find(e => Number(e.filtered_speed_kmh) === 0)));
if (frozenPosition) issues.push(issue('warning', 'engine-position-frozen',
  `${frozenPosition} раз GPS-проекция двигалась, а расчётная позиция оставалась на месте`, prior));

const configurations = events.filter(e => e.event === 'trip_configured');
let rapidConfigurationReplacements = 0;
for (let index = 1; index < configurations.length; index++) {
  const previous = configurations[index - 1], current = configurations[index];
  const previousKey = [previous.route, previous.direction, previous.train].join('|');
  const currentKey = [current.route, current.direction, current.train].join('|');
  if (Number(current.time) - Number(previous.time) <= 1_500 && previousKey !== currentKey) {
    rapidConfigurationReplacements++;
    issues.push(issue('error', 'rapid-config-replacement',
      `Конфигурация ${previousKey} была заменена на ${currentKey} менее чем за 1,5 секунды`, current));
  }
}
for (const event of events.filter(e => e.event === 'trip_config_error' || e.event === 'ui_config_error'))
  issues.push(issue('error', event.event, `Ошибка конфигурации: ${event.error || 'неизвестно'}`, event,
    { errorMessage: event.message ?? null }));

const completedOutages = [];
const activeOutages = new Map();
for (const event of events) {
  const key = event.trip_session_id || 'legacy';
  if (event.event === 'gps_outage_started') activeOutages.set(key, event);
  if (event.event === 'gps_outage_recovered') {
    const start = activeOutages.get(key);
    const duration = finite(event.duration_ms) ? Number(event.duration_ms) :
      start ? Math.max(0, Number(event.time) - Number(start.time)) : 0;
    completedOutages.push({ start, end: event, duration });
    activeOutages.delete(key);
    if (duration >= 30_000) issues.push(issue('warning', 'long-gps-outage',
      `Точный GPS отсутствовал ${Math.round(duration / 1000)} секунд`, event,
      { durationMs: duration, cause: start?.cause ?? null }));
  }
}
for (const start of activeOutages.values()) issues.push(issue('error', 'gps-outage-not-recovered',
  'До конца журнала точный GPS не восстановился', start, { cause: start.cause ?? null }));

const reconciliations = events.filter(e => e.event === 'position_reconciled');
const corrections = reconciliations.map(e => Math.abs(Number(e.correction_m))).filter(Number.isFinite);
for (const event of reconciliations.filter(e => Math.abs(Number(e.correction_m)) >= 500))
  issues.push(issue('info', 'large-position-reconciliation',
    `После восстановления позиция исправлена на ${Math.round(Number(event.correction_m))} м`, event,
    { correctionM: Number(event.correction_m) }));

const transitionProbes = events.filter(e => e.event === 'route_transition_probe');
const transitionProbeStatuses = Object.fromEntries([...new Set(transitionProbes.map(e => e.status))]
  .sort().map(status => [status, transitionProbes.filter(e => e.status === status).length]));
for (const event of transitionProbes.filter(e => e.status === 'next_projection_unavailable'))
  issues.push(issue('warning', 'transition-next-route-unavailable',
    `У перехода ${event.from || '?'} → ${event.to || '?'} не найдена проекция следующего участка`, event,
    { boundaryM: event.boundary_m ?? null, currentRouteDistanceM: event.current_route_distance_m ?? null }));

const scheduleCards = events.filter(e => e.event === 'schedule_card_changed');
let scheduleCardRegressions = 0;
const lastCardByKey = new Map();
for (const event of scheduleCards) {
  const key = [event.trip_session_id, event.route, event.direction, event.train].join('|');
  const previous = lastCardByKey.get(key);
  if (previous && finite(event.index) && finite(previous.index) && Number(event.index) < Number(previous.index)) {
    scheduleCardRegressions++;
    issues.push(issue('warning', 'schedule-card-regression',
      `Карточка хода вернулась с позиции ${previous.index} на ${event.index}`, event,
      { from: event.from ?? null, to: event.to ?? null }));
  }
  lastCardByKey.set(key, event);
}

const power = events.filter(e => e.event === 'power_sample');
const temperatures = power.map(e => Number(e.battery_temperature_c)).filter(Number.isFinite);
const hottest = power.filter(e => finite(e.battery_temperature_c))
  .sort((a, b) => Number(b.battery_temperature_c) - Number(a.battery_temperature_c))[0];
if (hottest && Number(hottest.battery_temperature_c) >= 45)
  issues.push(issue('warning', 'high-battery-temperature',
    `Температура батареи достигла ${Number(hottest.battery_temperature_c).toFixed(1)} °C`, hottest));

const sessionSummaries = events.filter(e => e.event === 'trip_session_summary');
for (const event of events.filter(e => e.event === 'uncaught_exception'))
  issues.push(issue('error', 'uncaught-exception',
    `Приложение аварийно завершилось: ${event.type || 'ошибка'}${event.message ? ` — ${event.message}` : ''}`, event,
    { thread: event.thread ?? null }));
for (const event of events.filter(e => e.event === 'location_provider_changed' && e.enabled === false))
  issues.push(issue('warning', 'location-provider-disabled',
    `Системный провайдер ${event.provider || 'GPS'} был отключён`, event));
for (const event of events.filter(e => e.event === 'tracking_permission_missing'))
  issues.push(issue('error', 'location-permission-missing', 'Трекинг не стартовал: нет разрешения точной геолокации', event));
const locationRequestRestarts = events.filter(e => e.event === 'location_request_restarted').length;
if (locationRequestRestarts >= 3) {
  const firstRestart = events.find(e => e.event === 'location_request_restarted');
  issues.push(issue('warning', 'repeated-location-restarts',
    `Точный GPS пришлось перезапускать ${locationRequestRestarts} раз`, firstRestart));
}
const leadBySession = new Map();
for (const event of events) {
  if (event.event === 'trip_configured' && finite(event.lead_m))
    leadBySession.set(event.trip_session_id || [event.route, event.direction, event.train].join('|'), Number(event.lead_m));
  if (event.event === 'restriction_alert' && event.in_zone === false && finite(event.distance_m)) {
    const key = event.trip_session_id || [event.route, event.direction, event.train].join('|');
    const lead = leadBySession.get(key);
    if (finite(lead) && Number(event.distance_m) > Number(lead) + 700)
      issues.push(issue('warning', 'restriction-warning-too-early',
        `Предупреждение прозвучало за ${Math.round(Number(event.distance_m))} м при настройке ${Math.round(Number(lead))} м`, event,
        { configuredLeadM: lead, actualDistanceM: Number(event.distance_m), restrictionId: event.id ?? null }));
  }
}
const report = {
  file: path.basename(input), events: events.length,
  firstEventTime: events.length && finite(events[0].time) ? eventTime(events[0]) : null,
  lastEventTime: events.length && finite(events.at(-1).time) ? eventTime(events.at(-1)) : null,
  tripSessions: events.filter(e => e.event === 'trip_session_started').length,
  tripSessionSummaries: sessionSummaries,
  gpsSamples: samples.length, movingSamples: moving.length,
  movingSamplesWithFilteredZero: frozenSpeed, frozenPositionSteps: frozenPosition,
  gpsOutages: completedOutages.length + activeOutages.size,
  longestGpsOutageMs: completedOutages.length ? Math.max(...completedOutages.map(item => item.duration)) : null,
  unrecoveredGpsOutages: activeOutages.size,
  routeTransitions: events.filter(e => e.event === 'route_transition').length,
  routeTransitionProbes: transitionProbes.length, routeTransitionProbeStatuses: transitionProbeStatuses,
  positionReconciliations: reconciliations.length,
  maxPositionCorrectionM: corrections.length ? Math.max(...corrections) : null,
  scheduleCardChanges: scheduleCards.length, scheduleCardRegressions,
  rapidConfigurationReplacements,
  gpsReserveStarts: events.filter(e => e.event === 'direct_gps_started').length,
  gpsReserveStops: events.filter(e => e.event === 'direct_gps_stopped').length,
  locationRequestRestarts,
  restrictionAlerts: events.filter(e => e.event === 'restriction_alert').length,
  manualCalibrationChanges: events.filter(e => e.event === 'manual_calibration_changed').length,
  crashes: events.filter(e => e.event === 'uncaught_exception').length,
  maxBatteryTemperatureC: temperatures.length ? Math.max(...temperatures) : null,
  issues
};
console.log(JSON.stringify(report, null, 2));

if (process.argv.includes('--expect-clean') && issues.some(item => item.severity === 'error')) process.exit(1);