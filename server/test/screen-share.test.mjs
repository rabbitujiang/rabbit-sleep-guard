import assert from 'node:assert/strict';
import test from 'node:test';
import { ScreenShareService } from '../src/screen-share.mjs';
import { createSleepGuardServer } from '../src/server.mjs';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const data = Buffer.from([255,216,1,2,255,217]).toString('base64');
test('screen capture needs opt-in fresh unlocked device; claims once, no stale return', () => {
  let now = 100000;
  const s = new ScreenShareService(() => now);
  assert.equal(s.request('codex').ok, false);
  s.poll({enabled:true, available:true});
  assert.equal(s.request('intruder').error, 'invalid_viewer');
  const r = s.request('codex');
  assert.equal(r.ok, true);
  assert.equal(s.request('claude').error, 'capture_busy');
  assert.equal(s.upload({id:r.id,data}).ok, false);
  assert.equal(s.poll({enabled:true,available:true}).request.id, r.id);
  assert.equal(s.poll({enabled:true,available:true}).request, null);
  assert.equal(s.upload({id:r.id,data:'not jpeg'}).ok, false);
  assert.equal(s.upload({id:r.id,data}).ok, true);
  assert.equal(s.upload({id:r.id,data}).ok, false);
  assert.equal(s.result(r.id).data, data);
  now += 16000;
  assert.equal(s.request('codex').ok, false);
  now += 60000;
  assert.equal(s.result(r.id).ok, false);
});
test('disabling or locking cancels requests and uploaded images', () => {
  const s = new ScreenShareService();
  s.poll({enabled:true,available:true});
  let r = s.request('claude');
  s.poll({enabled:true,available:true});
  s.upload({id:r.id,data});
  s.poll({enabled:false,available:false});
  assert.equal(s.result(r.id).ok, false);
  s.poll({enabled:true,available:true});
  r = s.request('agy');
  s.poll({enabled:true,available:false});
  assert.equal(s.result(r.id).ok, false);
});
test('request expiry, image limits and Android errors', () => {
  let now = 1;
  const s = new ScreenShareService(() => now);
  s.poll({enabled:true,available:true});
  const r = s.request('codex');
  s.poll({enabled:true,available:true});
  assert.equal(s.upload({id:r.id,data:'A'.repeat(2800001)}).error, 'invalid_image');
  assert.equal(s.upload({id:r.id,error:'android_screenshot_error_6'}).ok, true);
  assert.equal(s.result(r.id).state,'failed');
  now += 26000;
  assert.equal(s.result(r.id).ok,false);
});
test('HTTP auth separation and real MCP request/poll/upload/image pipeline', async () => {
  const dir = await mkdtemp(join(tmpdir(),'screen-test-'));
  const c = {dataDir:dir, publicBaseUrl:'http://127.0.0.1',androidDeviceToken:'a'.repeat(32),codexControlToken:'c'.repeat(32),ownerApprovalCode:'o'.repeat(32)};
  const {server} = await createSleepGuardServer(c);
  await new Promise(r=>server.listen(0,'127.0.0.1',r));
  const url = 'http://127.0.0.1:'+server.address().port;
  const post = (path,body,token) => fetch(url+path,{method:'POST',headers:{authorization:'Bearer '+token,'content-type':'application/json'},body:JSON.stringify(body)});
  try {
    assert.equal((await post('/api/device/screen/poll',{},c.codexControlToken)).status,401);
    assert.equal((await post('/api/control/screen/request',{},c.androidDeviceToken)).status,401);
    await post('/api/device/screen/poll',{enabled:true,available:true},c.androidDeviceToken);
    const pending = post('/mcp',{jsonrpc:'2.0',id:1,method:'tools/call',params:{name:'get_phone_screen',arguments:{viewer:'codex'}}},c.codexControlToken);
    let request;
    for(let i=0;i<30&&!request;i++) {
      await new Promise(r=>setTimeout(r,20));
      request = (await (await post('/api/device/screen/poll',{enabled:true,available:true},c.androidDeviceToken)).json()).request;
    }
    assert.ok(request);
    const upload = await post('/api/device/screen/result',{id:request.id,data},c.androidDeviceToken);
    assert.equal(upload.status,200);
    const reply = await (await pending).json();
    assert.equal(reply.result.content[1].type,'image');
    assert.equal(reply.result.content[1].data,data);
    const second = await (await fetch(url+'/api/control/screen/result/'+request.id,{headers:{authorization:'Bearer '+c.codexControlToken}})).json();
    assert.equal(second.ok,false);
  } finally { server.closeAllConnections(); await new Promise(r=>server.close(r)); await rm(dir,{recursive:true,force:true}); }
});
