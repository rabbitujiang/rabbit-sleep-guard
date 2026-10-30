import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {JsonStore} from '../src/json-store.mjs';
import {AppUsageService} from '../src/app-usage.mjs';
import {createSleepGuardServer} from '../src/server.mjs';
const now = Date.parse('2026-10-26T07:00:00Z');
function sample() {
 const report={state:'ready',captured_at:new Date(now).toISOString(),timezone:'Asia/Shanghai',ranges:{}};
 for(const key of ['today','yesterday','week']) report.ranges[key]={start:'2026-10-25T16:00:00Z',end:report.captured_at,first_record_at:'2026-10-25T15:00:00Z',apps:[
  {package_name:'com.example.a',name:'Test A',duration_ms:60000},{package_name:'com.example.b',name:'Test B',duration_ms:120000}]};
 return report;
}
async function fixture(t) {
 const dir=await mkdtemp(join(tmpdir(),'usage-test-')); const store=new JsonStore(dir); await store.init();
 t.after(()=>rm(dir,{recursive:true,force:true})); return {dir,store,svc:new AppUsageService(store)};
}
test('missing reports are unavailable; validation does not change last good snapshot',async t=>{
 const {svc}=await fixture(t);
 assert.equal((await svc.query()).error,'usage_not_reported');
 assert.equal((await svc.report(sample(),now)).ok,true);
 const result=await svc.query('today',now);
 assert.equal(result.total_ms,180000); assert.equal(result.apps[0].package_name,'com.example.b'); assert.equal(result.stale,false);
 assert.equal(result.captured_at_local,'2026-10-26 15:00:00 [Asia/Shanghai]');
 assert.equal(result.start_local,'2026-10-26 00:00:00 [Asia/Shanghai]');
 assert.equal(result.end_local,'2026-10-26 15:00:00 [Asia/Shanghai]');
 assert.equal(result.first_record_at_local,'2026-10-25 23:00:00 [Asia/Shanghai]');
 for(const mutate of [r=>r.ranges.today.apps[0].duration_ms=-1,r=>r.timezone='invalid',r=>r.ranges.today.apps.push(r.ranges.today.apps[0]),r=>r.ranges.today.apps[0].duration_ms=Infinity,r=>r.captured_at='not-a-date',r=>r.ranges.today.apps[0].name=4]) {
  const bad=sample(); mutate(bad); assert.equal((await svc.report(bad,now)).ok,false);
 }
 assert.equal((await svc.query('today',now)).total_ms,180000);
 assert.equal((await svc.query('bad')).error,'invalid_range');
});
test('snapshots persist, mark stale/cross-day, reject older uploads',async t=>{
 const {svc,store}=await fixture(t); await svc.report(sample(),now);
 const restored=new AppUsageService(store);
 assert.equal((await restored.query('week',now+601000)).stale,true);
 assert.equal((await restored.query('today',now+86400000)).date_changed,true);
 const old=sample(); old.captured_at='2026-10-26T06:00:00Z';
 old.ranges.today.end=old.ranges.yesterday.end=old.ranges.week.end=old.captured_at;
 assert.equal((await svc.report(old,now)).error,'older_usage_report');
});
test('revocation and disabling remove prior usage rows',async t=>{
 const {svc,store}=await fixture(t);
 for(const state of ['disabled','permission_required','read_failed']) {
  await svc.report(sample(),now);
  await svc.report({state,captured_at:new Date(now).toISOString()},now);
  assert.equal((await svc.query()).error,state);
  assert.deepEqual((await store.readAppUsage(null)).ranges,{});
 }
});
test('device writes and control/MCP reads are authenticated and leave guard state alone',async t=>{
 const {dir}=await fixture(t);
 const config={publicBaseUrl:'http://127.0.0.1',dataDir:dir,androidDeviceToken:'a'.repeat(32),codexControlToken:'c'.repeat(32),ownerApprovalCode:'o'.repeat(32),wakeHour:6,wakeMinute:30,autoStartHour:1,utcOffsetMinutes:480,accessTokenTtlDays:90};
 const {server,guard}=await createSleepGuardServer(config);
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 t.after(async()=>{server.closeAllConnections(); await new Promise(r=>server.close(r));});
 const url='http://127.0.0.1:'+server.address().port;
 const call=(path,token,body)=>fetch(url+path,{method:body?'POST':'GET',headers:{authorization:'Bearer '+token,'content-type':'application/json'},...(body?{body:JSON.stringify(body)}:{})});
 assert.equal((await call('/api/control/usage','a'.repeat(32))).status,401);
 assert.equal((await call('/api/device/usage','c'.repeat(32),sample())).status,401);
 await guard.event({event:"sleep_guard_started"},"test");
 const before=await guard.status();
 assert.equal((await call('/api/device/usage','a'.repeat(32),sample())).status,200);
 assert.equal((await (await call('/api/control/usage?range=week','c'.repeat(32))).json()).total_ms,180000);
 assert.equal((await call('/api/control/usage?range=nope','c'.repeat(32))).status,422);
 const rpc=await (await call('/mcp','c'.repeat(32),{jsonrpc:'2.0',id:1,method:'tools/call',params:{name:'get_phone_app_usage',arguments:{range:'yesterday'}}})).json();
 assert.equal(rpc.result.structuredContent.total_ms,180000);
 assert.equal(rpc.result.structuredContent.captured_at_local,'2026-10-26 15:00:00 [Asia/Shanghai]');
 assert.equal(rpc.result.isError,false);
 const list=await (await call('/mcp','c'.repeat(32),{jsonrpc:'2.0',id:2,method:'tools/list'})).json();
 const usageTool=list.result.tools.find(t=>t.name==='get_phone_app_usage');
 assert.ok(usageTool); assert.match(usageTool.description,/Z 表示 UTC/); assert.match(usageTool.description,/\*_local/);
 assert.deepEqual(await guard.status(),before);
});

test('system totals preserve source, expanded buckets and overlapping application durations',async t=>{
 const {svc}=await fixture(t);
 const report=sample(); report.source='android_usage_stats';
 for(const range of Object.values(report.ranges)) {
  range.requested_start=range.start; range.requested_end=range.end;
  range.start='2026-10-25T15:00:00Z';
  const span=Date.parse(range.end)-Date.parse(range.start);
  range.apps.forEach(app=>app.duration_ms=span);
 }
 assert.equal((await svc.report(report,now)).ok,true);
 const result=await svc.query('today',now);
 assert.equal(result.source,'android_usage_stats'); assert.equal(result.estimate,false);
 assert.equal(result.interval_expanded,true);
 assert.equal(result.requested_start_local,'2026-10-26 00:00:00 [Asia/Shanghai]');
 assert.equal(result.start_local,'2026-10-25 23:00:00 [Asia/Shanghai]');
 assert.equal(result.total_ms,2*(Date.parse(result.end)-Date.parse(result.start)));
 const bad=structuredClone(report); bad.source='unknown';
 assert.equal((await svc.report(bad,now)).ok,false);
 bad.source='android_usage_stats'; bad.ranges.today.requested_end='2026-10-27T07:00:00Z';
 assert.equal((await svc.report(bad,now)).ok,false);
 const legacy=sample(); legacy.ranges.today.apps.forEach(app=>app.duration_ms=15*3600000);
 assert.equal((await svc.report(legacy,now)).ok,false);
});
test('legacy snapshots remain explicitly estimated; empty system totals are not fabricated',async t=>{
 const {svc}=await fixture(t);
 await svc.report(sample(),now);
 assert.equal((await svc.query('today',now)).estimate,true);
 const report=sample(); report.source='android_usage_stats';
 for(const range of Object.values(report.ranges)) {
  range.requested_start=range.start; range.requested_end=range.end;
  range.apps=[]; range.first_record_at=null;
 }
 assert.equal((await svc.report(report,now)).ok,true);
 const result=await svc.query('today',now);
 assert.equal(result.estimate,false); assert.deepEqual(result.apps,[]);
 assert.equal(result.first_record_at,null); assert.equal(result.interval_expanded,false);
});
