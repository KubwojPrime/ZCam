const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

// Exercise the actual embedded handler, including its Refresh button binding.
const source = fs.readFileSync(path.join(__dirname, '../../main/java/com/zcam/server/ZCamHttpServer.kt'), 'utf8');
const start = source.indexOf('async function loadRecordings()');
const end = source.indexOf('function downloadFileNameForRecording', start);
const binding = source.match(/document\.getElementById\('refreshRecordingsBtn'\)\.addEventListener\('click', loadRecordings\);/);
assert.ok(start >= 0 && end > start && binding, 'Recording history needs a usable Refresh button');
let refresh;
let clips = [{ fileName: 'playing.mp4', startedAtEpochMs: 1 }];
let ok = true;
let requests = 0;
let selections = 0;
const context = vm.createContext({
  document: { getElementById: () => ({ addEventListener: (event, handler) => { refresh = handler; } }) },
  recordingsCache: [], recordingEventsCache: [], selectedRecordingName: 'playing.mp4',
  recordingsState: {}, downloadSelectedRecordingBtn: {}, recordingSelection: {}, globalRecordingTimeline: {},
  globalRecordingTimelineHint: {},
  buildAuthorizedUrl: (url) => url, authHeaders: () => ({}),
  fetch: async (url) => {
    requests++;
    return { ok, json: async () => url.includes('/events') ? { events: [] } : { recordings: [...clips] } };
  },
  setRecordingError() {}, renderRecordingMeta() {}, updateRecordingPlaybackState() {}, setRecordingLoadState() {},
  renderGlobalRecordingTimeline() {}, renderRecordings() {}, renderRecordingTimeline() {}, appendLog() {},
  selectRecording() { selections++; },
});
vm.runInContext(source.slice(start, end) + '\n' + binding[0], context);
(async () => {
  await context.loadRecordings();
  clips.push({ fileName: 'new.mp4', startedAtEpochMs: 2 });
  await refresh();
  assert.equal(requests, 4);
  assert.equal(context.recordingsCache.length, 2, 'Refresh must include newly finalized clips');
  assert.equal(context.selectedRecordingName, 'playing.mp4');
  assert.equal(selections, 0, 'Refreshing must not reload the playing clip');
  ok = false;
  await refresh();
  assert.equal(context.recordingsCache.length, 2, 'Failed refresh must preserve the loaded history');
  assert.equal(context.selectedRecordingName, 'playing.mp4');
  const cameraState = {};
  context.document = { getElementById: () => cameraState };
  context.apiPost = async () => ({ response: { ok: false }, parsed: { reason: 'local_camera_activation_required' } });
  context.updatePreviewState = () => {};
  const cameraStart = source.indexOf('async function setCameraEnabled(enabled)');
  const cameraEnd = source.indexOf('async function setNightMode', cameraStart);
  vm.runInContext(source.slice(cameraStart, cameraEnd), context);
  await context.setCameraEnabled(true);
  assert.match(cameraState.textContent, /Open ZCam on the server device/,
    'Local activation instructions must have their own persistent status message');
  context.apiPost = async () => { throw new Error('Server disconnected'); };
  await context.setCameraEnabled(true);
  assert.match(cameraState.textContent, /Camera update failed/);
  console.log('Recording history refresh regression checks passed');
})().catch((error) => { console.error(error); process.exitCode = 1; });
