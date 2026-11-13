// Pure, independently testable gateway guards. No Firebase/Gemini credentials here.
export const MAX_REQUEST_BYTES = 8192;
export const ALLOWED_ACTIONS = new Set([
  'CHAT', 'OPEN_APP', 'CALL', 'SMS', 'WHATSAPP_SHARE',
  'REMINDER', 'OPEN_SETTINGS', 'CAMERA', 'PHONE_NAVIGATION',
]);
const SETTINGS = new Set(['general', 'wifi', 'bluetooth', 'display', 'sound']);
const NAVIGATION = new Set(['home', 'back', 'recents', 'notifications', 'quick_settings']);

export function validateRequest(data) {
  if (!data || typeof data !== 'object' || Array.isArray(data) ||
      typeof data.text !== 'string' || !data.text.trim() || data.text.length > 2000) {
    throw Object.assign(new Error('Text must be 1–2000 characters'), { status: 400 });
  }
  return data.text.trim();
}

export function sanitizePlan(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw Object.assign(new Error('Invalid Gemini response'), { status: 502 });
  }
  const limited = (key, max) => typeof value[key] === 'string'
    ? value[key].slice(0, max) : '';
  const action = ALLOWED_ACTIONS.has(value.action) ? value.action : 'CHAT';
  const phoneAction = limited('phoneAction', 30).toLowerCase();
  return {
    reply: limited('reply', 2000) || 'Please clarify your request.',
    action: action === 'PHONE_NAVIGATION' && !NAVIGATION.has(phoneAction) ? 'CHAT' : action,
    appName: limited('appName', 100),
    recipientQuery: limited('recipientQuery', 100),
    message: limited('message', 2000),
    settingsTarget: SETTINGS.has(value.settingsTarget) ? value.settingsTarget : 'general',
    phoneAction: NAVIGATION.has(phoneAction) ? phoneAction : '',
  };
}

export function requiredAuthHeaders(headers) {
  const match = /^Bearer ([A-Za-z0-9._-]+)$/.exec(headers.authorization || '');
  const appCheck = headers['x-firebase-appcheck'];
  if (!match || typeof appCheck !== 'string' || !appCheck) {
    throw Object.assign(new Error('Authentication required'), { status: 401 });
  }
  return { idToken: match[1], appCheck };
}

export function validateAppId(appId, allowedCsv) {
  const allowed = (allowedCsv || '').split(',').map(s => s.trim()).filter(Boolean);
  if (allowed.length === 0 || !allowed.includes(appId)) {
    throw Object.assign(new Error('Untrusted app registration'), { status: 403 });
  }
}

export function safeStatus(error) {
  return [400, 401, 403, 413, 415, 429].includes(Number(error?.status))
    ? Number(error.status) : 502;
}
