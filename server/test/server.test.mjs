import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtemp } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { createSleepGuardServer } from "../src/server.mjs";

const secret = (character) => character.repeat(32);

async function fixture() {
  const dataDir = await mkdtemp(join(tmpdir(), "sleep-guard-test-"));
  const config = {
    port: 0,
    publicBaseUrl: "http://127.0.0.1",
    dataDir,
    androidDeviceToken: secret("a"),
    codexControlToken: secret("c"),
    ownerApprovalCode: secret("o"),
    wakeHour: 6,
    wakeMinute: 30,
    autoStartHour: 1,
    utcOffsetMinutes: 480,
    accessTokenTtlDays: 90,
  };
  const { server } = await createSleepGuardServer(config);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  return { server, config, url: `http://127.0.0.1:${address.port}` };
}

async function close(server) {
  await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
}

test("device and server control APIs share one state", async () => {
  const context = await fixture();
  try {
    let response = await fetch(`${context.url}/api/control/activate`, {
      method: "POST",
      headers: { authorization: `Bearer ${context.config.codexControlToken}`, "content-type": "application/json" },
      body: "{}",
    });
    assert.equal(response.status, 200);
    assert.equal((await response.json()).active, true);

    response = await fetch(`${context.url}/api/device/event`, {
      method: "POST",
      headers: { authorization: `Bearer ${context.config.androidDeviceToken}`, "content-type": "application/json" },
      body: JSON.stringify({ event: "blocked_app_opened", app_name: "小红书" }),
    });
    const caught = await response.json();
    assert.equal(caught.active, true);
    assert.equal(caught.attempts, 1);
    assert.equal(caught.unlocks_revoked, false);

    response = await fetch(`${context.url}/api/device/event`, {
      method: "POST",
      headers: { authorization: `Bearer ${context.config.androidDeviceToken}`, "content-type": "application/json" },
      body: JSON.stringify({ event: "temporary_unlock_requested", app_name: "小红书" }),
    });
    const requested = await response.json();
    assert.equal(requested.unlock_request_count, 1);
    assert.equal(requested.ignored, false);

    for (let attempt = 2; attempt <= 3; attempt += 1) {
      response = await fetch(`${context.url}/api/device/event`, {
        method: "POST",
        headers: { authorization: `Bearer ${context.config.androidDeviceToken}`, "content-type": "application/json" },
        body: JSON.stringify({ event: "blocked_app_opened", app_name: "小红书" }),
      });
    }
    const revoked = await response.json();
    assert.equal(revoked.attempts, 3);
    assert.equal(revoked.stage, "refused_sleep");
    assert.equal(revoked.unlocks_revoked, false);

    for (let request = 2; request <= 3; request += 1) {
      response = await fetch(`${context.url}/api/device/event`, {
        method: "POST",
        headers: { authorization: `Bearer ${context.config.androidDeviceToken}`, "content-type": "application/json" },
        body: JSON.stringify({ event: "temporary_unlock_requested", app_name: "小红书" }),
      });
    }
    const thirdRequest = await response.json();
    assert.equal(thirdRequest.unlock_request_count, 3);
    assert.equal(thirdRequest.unlocks_revoked, true);

    response = await fetch(`${context.url}/api/control/status`, {
      headers: { authorization: `Bearer ${context.config.codexControlToken}` },
    });
    const status = await response.json();
    assert.equal(status.attempts, 3);
    assert.equal(status.unlock_request_count, 3);
    assert.equal(status.unlocks_revoked, true);
  } finally {
    await close(context.server);
  }
});

test("Android status reports are stored and exposed to control API and MCP", async () => {
  const context = await fixture();
  try {
    const deviceHeaders = { authorization: `Bearer ${context.config.androidDeviceToken}`, "content-type": "application/json" };
    let response = await fetch(`${context.url}/api/device/report`, {
      method: "POST",
      headers: deviceHeaders,
      body: JSON.stringify({
        battery_level: 82,
        charging: true,
        battery_temperature_c: 32.4,
        network_type: "wifi",
        network_connected: true,
        screen_on: true,
      }),
    });
    assert.equal(response.status, 200);
    const report = await response.json();
    assert.equal(report.device_status.online, true);
    assert.equal(report.device_status.battery_level, 82);

    response = await fetch(`${context.url}/api/control/device-status`, {
      headers: { authorization: `Bearer ${context.config.codexControlToken}` },
    });
    const control = await response.json();
    assert.equal(control.device_status.network_type, "wifi");
    assert.ok(control.device_status.last_updated_at);

    response = await fetch(`${context.url}/mcp`, {
      method: "POST",
      headers: { authorization: `Bearer ${context.config.codexControlToken}`, "content-type": "application/json" },
      body: JSON.stringify({ jsonrpc: "2.0", id: 11, method: "tools/call", params: { name: "get_phone_status", arguments: {} } }),
    });
    const tool = await response.json();
    assert.equal(tool.result.structuredContent.battery_level, 82);
    assert.match(tool.result.content[0].text, /手机在线/);
  } finally {
    await close(context.server);
  }
});

test("static bearer token gives server Codex access to MCP", async () => {
  const context = await fixture();
  try {
    const headers = { authorization: `Bearer ${context.config.codexControlToken}`, "content-type": "application/json" };
    let response = await fetch(`${context.url}/mcp`, {
      method: "POST",
      headers,
      body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/list", params: {} }),
    });
    const listed = await response.json();
    assert.deepEqual(listed.result.tools.map((tool) => tool.name), [
      "get_phone_screen",
      "activate_sleep_guard",
      "deactivate_sleep_guard",
      "get_sleep_guard_status",
      "get_phone_status",
    ]);

    response = await fetch(`${context.url}/mcp`, {
      method: "POST",
      headers,
      body: JSON.stringify({ jsonrpc: "2.0", id: 2, method: "tools/call", params: { name: "activate_sleep_guard", arguments: {} } }),
    });
    assert.equal((await response.json()).result.structuredContent.active, true);
  } finally {
    await close(context.server);
  }
});

test("origin URL remains a ChatGPT-compatible MCP transport alias", async () => {
  const context = await fixture();
  try {
    const response = await fetch(`${context.url}/`, {
      method: "POST",
      headers: { authorization: `Bearer ${context.config.codexControlToken}`, "content-type": "application/json" },
      body: JSON.stringify({
        jsonrpc: "2.0",
        id: 1,
        method: "initialize",
        params: {
          protocolVersion: "2025-11-25",
          clientInfo: { name: "openai-mcp (ChatGPT)", version: "1.0.0" },
          capabilities: {},
        },
      }),
    });
    assert.equal(response.status, 200);
    const initialized = await response.json();
    assert.equal(initialized.result.protocolVersion, "2025-11-25");
  } finally {
    await close(context.server);
  }
});

test("MCP without a token advertises OAuth resource metadata", async () => {
  const context = await fixture();
  try {
    const response = await fetch(`${context.url}/mcp`, { method: "POST", body: "{}" });
    assert.equal(response.status, 401);
    assert.match(response.headers.get("www-authenticate"), /oauth-protected-resource/);
  } finally {
    await close(context.server);
  }
});

test("OAuth DCR and PKCE authorize an official-client MCP session", async () => {
  const context = await fixture();
  try {
    const redirectUri = "http://127.0.0.1/callback";
    let response = await fetch(`${context.url}/oauth/register`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        client_name: "Official client test",
        redirect_uris: [redirectUri],
        token_endpoint_auth_method: "none",
      }),
    });
    assert.equal(response.status, 201);
    const client = await response.json();

    const verifier = "v".repeat(64);
    const challenge = createHash("sha256").update(verifier).digest("base64url");
    const authorize = new URL(`${context.url}/oauth/authorize`);
    authorize.searchParams.set("response_type", "code");
    authorize.searchParams.set("client_id", client.client_id);
    authorize.searchParams.set("redirect_uri", redirectUri);
    authorize.searchParams.set("scope", "sleep_guard:write");
    authorize.searchParams.set("state", "test-state");
    authorize.searchParams.set("code_challenge", challenge);
    authorize.searchParams.set("code_challenge_method", "S256");
    response = await fetch(authorize);
    assert.equal(response.status, 200);
    const page = await response.text();
    const requestId = page.match(/name="id" value="([^"]+)"/)?.[1];
    assert.ok(requestId);

    response = await fetch(`${context.url}/oauth/approve`, {
      method: "POST",
      redirect: "manual",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ id: requestId, approval_code: context.config.ownerApprovalCode }),
    });
    assert.equal(response.status, 302);
    const callback = new URL(response.headers.get("location"));
    assert.equal(callback.searchParams.get("state"), "test-state");

    response = await fetch(`${context.url}/oauth/token`, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "authorization_code",
        code: callback.searchParams.get("code"),
        client_id: client.client_id,
        redirect_uri: redirectUri,
        code_verifier: verifier,
      }),
    });
    assert.equal(response.status, 200);
    const access = await response.json();
    assert.equal(access.token_type, "Bearer");

    response = await fetch(`${context.url}/mcp`, {
      method: "POST",
      headers: { authorization: `Bearer ${access.access_token}`, "content-type": "application/json" },
      body: JSON.stringify({ jsonrpc: "2.0", id: 10, method: "tools/list", params: {} }),
    });
    assert.equal(response.status, 200);
    const listed = await response.json();
    assert.equal(listed.result.tools.length, 6);
    assert.deepEqual(listed.result.tools[0].securitySchemes, [
      { type: "oauth2", scopes: ["sleep_guard:write"] },
    ]);
    assert.deepEqual(listed.result.tools[0]._meta.securitySchemes, listed.result.tools[0].securitySchemes);
  } finally {
    await close(context.server);
  }
});
