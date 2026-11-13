// Jiva Gemini gateway. NEVER ship GEMINI_API_KEY to Android or Flutter apps.
// Cloud Run / trusted server only. Firebase Auth + App Check are mandatory.
import http from 'node:http';
import { initializeApp, applicationDefault } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { getAppCheck } from 'firebase-admin/app-check';
import { getFirestore } from 'firebase-admin/firestore';
import { GoogleGenAI } from '@google/genai';

initializeApp({ credential: applicationDefault() });
const key = process.env.GEMINI_API_KEY;
if (!key || !process.env.GOOGLE_CLOUD_PROJECT) {
  throw new Error('GEMINI_API_KEY and GOOGLE_CLOUD_PROJECT are mandatory.');
}
const ai = new GoogleGenAI({ apiKey: key });
const db = getFirestore();
const model = process.env.GEMINI_TEXT_MODEL || 'gemini-3.8-flash';
const allowed = new Set(['CHAT','OPEN_APP','CALL','SMS','WHATSAPP_SHARE','REMINDER','OPEN_SETTINGS','CAMERA','PHONE_NAVIGATION']);
const system = 'You are Jiva, a multilingual phone assistant. Return JSON keys reply, action, appName, recipientQuery, message, settingsTarget, phoneAction. action must be CHAT, OPEN_APP, CALL, SMS, WHATSAPP_SHARE, REMINDER, OPEN_SETTINGS, CAMERA, or PHONE_NAVIGATION. Never claim an action completed. Do not execute actions. The app independently confirms and validates all actions.';
const LIMIT_PER_MINUTE = 20;
const MAX_REQUEST_BYTES = 8192;

function send(res, status, data) {
  res.writeHead(status, {'content-type':'application/json; charset=utf-8','cache-control':'no-store','x-content-type-options':'nosniff'});
  res.end(JSON.stringify(data));
}

async function authenticate(req) {
  const auth = /^Bearer ([A-Za-z0-9._-]+)$/.exec(req.headers.authorization || '');
  const appCheckToken = req.headers['x-firebase-appcheck'];
  if (!auth || typeof appCheckToken !== 'string' || !appCheckToken) throw Object.assign(new Error('Authentication required'), {status:401});
  const [user, app] = await Promise.all([
    getAuth().verifyIdToken(auth[1], true),
    getAppCheck().verifyToken(appCheckToken)
  ]);
  if (!user.uid || !app.appId) throw Object.assign(new Error('Authentication required'), {status:401});
  return user.uid;
}

async function reserveQuota(uid) {
  const bucket = Math.floor(Date.now()/60000);
  const ref = db.collection('jiva_gateway_quotas').doc(uid).collection('minutes').doc(String(bucket));
  await db.runTransaction(async tx => {
    const doc = await tx.get(ref);
    const count = doc.exists ? (doc.data().count || 0) : 0;
    if (count >= LIMIT_PER_MINUTE) throw Object.assign(new Error('Too many requests'), {status:429});
    tx.set(ref, {count: count+1, expiresAt: new Date((bucket+60)*60000)}, {merge:true});
  });
}

async function readJson(req) {
  let size=0, chunks=[];
  for await(const chunk of req) {
    size += chunk.length;
    if (size > MAX_REQUEST_BYTES) throw Object.assign(new Error('Request too large'), {status:413});
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); }
  catch { throw Object.assign(new Error('Invalid JSON'), {status:400}); }
}

const server = http.createServer(async(req,res)=>{
  if(req.method === 'GET' && req.url === '/health') { send(res,200,{status:'ok'});return; }
  if(req.method !== 'POST' || !['/v1/interpret','/v1/live-token'].includes(req.url)){
    send(res,404,{error:'Not found'});return;
  }
  try {
    const uid = await authenticate(req);
    await reserveQuota(uid);
    if(req.url === '/v1/interpret') {
      const data = await readJson(req);
      if(typeof data.text !== 'string' || !data.text.trim() || data.text.length>2000)
        throw Object.assign(new Error('Text must be 1–2000 characters'),{status:400});
      const result = await ai.models.generateContent({
        model,
        contents: data.text,
        config: { systemInstruction:system, responseMimeType:'application/json' }
      });
      let parsed;
      try { parsed=JSON.parse(result.text || '{}'); }
      catch { throw Object.assign(new Error('Invalid AI response'),{status:502}); }
      if(!parsed || typeof parsed !== 'object' || Array.isArray(parsed))
        throw Object.assign(new Error('Invalid AI response'),{status:502});
      const action=allowed.has(parsed.action)?parsed.action:'CHAT';
      send(res,200,{
        reply:typeof parsed.reply==='string'?parsed.reply.slice(0,2000):'Please clarify your request.',
        action,
        appName:typeof parsed.appName==='string'?parsed.appName.slice(0,100):'',
        recipientQuery:typeof parsed.recipientQuery==='string'?parsed.recipientQuery.slice(0,100):'',
        message:typeof parsed.message==='string'?parsed.message.slice(0,2000):'',
        settingsTarget:['general','wifi','bluetooth','display','sound'].includes(parsed.settingsTarget)?parsed.settingsTarget:'general',
        phoneAction:['home','back','recents','notifications','quick_settings'].includes(parsed.phoneAction)?parsed.phoneAction:''
      });
      return;
    }
    // Live credentials are short-lived and can start only one session.
    const expiry=new Date(Date.now()+30*60*1000).toISOString();
    const entry=await ai.authTokens.create({config:{
      uses:1,
      expireTime:expiry,
      newSessionExpireTime:new Date(Date.now()+60*1000).toISOString(),
      httpOptions:{apiVersion:'v1alpha'}
    }});
    if(!entry.name) throw Object.assign(new Error('Token service unavailable'),{status:502});
    send(res,200,{token:entry.name,expiresAt:expiry});
  } catch(e) {
    // Do not echo upstream errors, requests, tokens or keys to untrusted clients.
    const status=Number(e.status);
    const safe=[400,401,413,429].includes(status)?status:502;
    send(res,safe,{error:safe===502?'Gemini gateway is temporarily unavailable':e.message});
  }
});
server.requestTimeout=30000;
server.headersTimeout=15000;
server.listen(Number(process.env.PORT||8080),'0.0.0.0');
