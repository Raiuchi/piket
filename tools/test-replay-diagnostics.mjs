import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const directory = path.dirname(fileURLToPath(import.meta.url));
const output = execFileSync(process.execPath, [path.join(directory, 'replay-diagnostics.mjs'),
  path.join(directory, 'fixtures', 'diagnostics-black-box.jsonl')], { encoding: 'utf8' });
const report = JSON.parse(output);
const codes = new Set(report.issues.map(issue => issue.code));
for (const code of ['long-gps-outage', 'large-position-reconciliation',
  'transition-next-route-unavailable', 'schedule-card-regression',
  'restriction-warning-too-early', 'high-battery-temperature', 'ui-error',
  'voice-error', 'service-worker-error', 'update-download-error']) assert.ok(codes.has(code), code);
assert.equal(report.tripSessions, 1);
assert.equal(report.gpsOutages, 1);
assert.equal(report.scheduleCardRegressions, 1);
assert.equal(report.uiErrors, 1);
assert.equal(report.audioVoiceErrors, 1);
assert.equal(report.serviceWorkerErrors, 1);
assert.equal(report.updateErrors, 1);
console.log('Diagnostic black-box replay test passed');
