const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../main/java/com/zcam/server/ZCamHttpServer.kt'), 'utf8');
const start = source.indexOf("let serverSettingsRevision = '';");
const end = source.indexOf('async function setCameraEnabled(enabled)', start);
assert.ok(start >= 0 && end > start);
class Element {
  constructor() { this.children = []; this.dataset = {}; }
  appendChild(child) { this.children.push(child); }
  replaceChildren() { this.children = []; }
  addEventListener() {}
}
const elements = Object.fromEntries(['serverSettingsState', 'saveServerSettingsBtn', 'reloadServerSettingsBtn',
  'serverSettingsFields', 'serverSettingsDetails'].map(key => [key, new Element()]));
const settings = { revision: 'first', serverPort: '8080', streamWidth: '1280', streamHeight: '720', streamFps: '15',
  rearLens: 'main', eventSensitivity: 'balanced', previewTransport: 'h264', previewWidth: '1280', previewHeight: '720',
  previewFps: '15', previewBitrateKbps: '1200', segmentMinutes: '5', maxStorageGb: '32', minFreeStorageGb: '5',
  mjpegStreaming: 'true', loopRecording: 'true', audioPushToTalk: 'true', audioLive: 'true', audioPlayback: 'true', watchdogRecovery: 'true' };
let sent;
const inputs = () => elements.serverSettingsFields.children.map(label => label.children[0]);
const context = vm.createContext({
  document: { getElementById: key => elements[key], createElement: () => new Element(), querySelectorAll: inputs },
  readAuth: () => ({ token: 'paired-token', deviceId: 'paired-device' }), authHeaders: () => ({}),
  fetch: async () => ({ ok: true, json: async () => ({ ...settings }) }),
  apiPost: async (url, payload) => { sent = { url, payload }; return { response: { ok: true } }; },
  location: { port: '8080', href: 'http://127.0.0.1:8080/' },
});
vm.runInContext(source.slice(start, end), context);
(async () => {
  await context.loadServerSettings();
  assert.equal(inputs().length, 20);
  assert.equal(elements.saveServerSettingsBtn.disabled, false);
  inputs().find(input => input.dataset.setting === 'streamFps').value = '20';
  await context.saveServerSettings();
  assert.equal(sent.url, '/api/settings');
  assert.equal(sent.payload.streamFps, '20');
  assert.equal(sent.payload.revision, 'first');
  assert.equal(sent.payload.apiToken, undefined);
  assert.match(elements.serverSettingsState.textContent, /Settings saved/);
  assert.equal(elements.saveServerSettingsBtn.disabled, true, 'Saved forms need a fresh revision');
  settings.revision = 'second';
  await context.loadServerSettings();
  context.apiPost = async () => ({ response: { ok: false }, parsed: { reason: 'settings_changed_reload_required' } });
  await context.saveServerSettings();
  assert.match(elements.serverSettingsState.textContent, /Reload them before saving/);
  assert.equal(elements.saveServerSettingsBtn.disabled, true);
  context.fetch = async () => ({ ok: false });
  await context.loadServerSettings();
  assert.equal(elements.saveServerSettingsBtn.disabled, true, 'A failed load must not permit saving defaults');
  console.log('Remote server settings panel regression checks passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
