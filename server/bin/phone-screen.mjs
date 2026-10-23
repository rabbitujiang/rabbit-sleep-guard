#!/usr/bin/env node
// Shared local helper for agents whose MCP tool list has not refreshed yet.
// No credentials in arguments or output. Default filename stays in the caller's workspace.
import { loadEnvFile } from 'node:process';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { writeFile } from 'node:fs/promises';
const serverDir = resolve(dirname(fileURLToPath(import.meta.url)), '..');
try { loadEnvFile(resolve(serverDir, '.env')); } catch { /* env may be injected */ }
const viewer = process.argv[2];
if (!['codex','claude','agy'].includes(viewer)) {
  console.error('Usage: node server/bin/phone-screen.mjs codex|claude|agy [output.jpg]');
  process.exit(2);
}
const port = process.env.PORT || '8787';
const token = process.env.CODEX_CONTROL_TOKEN;
if (!token) { console.error('Missing local control credential'); process.exit(2); }
try {
  const response = await fetch(`http://127.0.0.1:${port}/mcp`, {
    method:'POST', signal:AbortSignal.timeout(25000),
    headers:{authorization:`Bearer ${token}`,'content-type':'application/json'},
    body:JSON.stringify({jsonrpc:'2.0',id:1,method:'tools/call',params:{name:'get_phone_screen',arguments:{viewer}}})
  });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  const reply = await response.json();
  const image = reply.result?.content?.find(c=>c.type==='image');
  if (!image) {
    console.error(reply.result?.content?.filter(c=>c.type==='text').map(c=>c.text).join('\n') || 'No screen returned');
    process.exitCode = 1;
  } else {
    const path = resolve(process.argv[3] || `phone-screen-${Date.now()}.jpg`);
    await writeFile(path, Buffer.from(image.data,'base64'),{mode:0o600,flag:'wx'});
    console.log(JSON.stringify({ok:true,path,mimeType:image.mimeType}));
  }
} catch (error) { console.error(error.message); process.exitCode=1; }
