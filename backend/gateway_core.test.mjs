import test from 'node:test';
import assert from 'node:assert/strict';
import {
  validateRequest, sanitizePlan, requiredAuthHeaders, validateAppId, safeStatus,
} from './gateway_core.mjs';

test('missing auth and attestation fail closed', () => {
  assert.throws(() => requiredAuthHeaders({}), /Authentication required/);
  assert.throws(() => requiredAuthHeaders({ authorization: 'Bearer user.jwt' }), /Authentication/);
  assert.deepEqual(requiredAuthHeaders({
    authorization: 'Bearer valid.jwt', 'x-firebase-appcheck': 'attestation',
  }), { idToken: 'valid.jwt', appCheck: 'attestation' });
});
test('only registered apps are permitted', () => {
  validateAppId('android-app-a', 'android-app-a,android-app-b');
  assert.throws(() => validateAppId('other-app', 'android-app-a'), /Untrusted/);
  assert.throws(() => validateAppId('other-app', ''), /Untrusted/);
});
test('bounded text and strict action allowlist', () => {
  assert.equal(validateRequest({ text: ' hi ' }), 'hi');
  for (const value of [{ text: '' }, { text: 'x'.repeat(2001) }, { text: 4 }, []]) {
    assert.throws(() => validateRequest(value));
  }
  assert.equal(sanitizePlan({ action: 'DELETE_EVERYTHING' }).action, 'CHAT');
  assert.equal(sanitizePlan({ action: 'PHONE_NAVIGATION', phoneAction: 'unknown' }).action, 'CHAT');
  assert.equal(sanitizePlan({ action: 'PHONE_NAVIGATION', phoneAction: 'home' }).action, 'PHONE_NAVIGATION');
  assert.equal(sanitizePlan({ reply: 'x'.repeat(5000) }).reply.length, 2000);
  assert.equal(sanitizePlan({ settingsTarget: 'danger' }).settingsTarget, 'general');
  assert.throws(() => sanitizePlan([]));
});
test('internal and upstream errors never reach clients', () => {
  assert.equal(safeStatus({ status: 401 }), 401);
  assert.equal(safeStatus({ status: 503 }), 502);
  assert.equal(safeStatus(new Error('SECRET GEMINI KEY')), 502);
});
