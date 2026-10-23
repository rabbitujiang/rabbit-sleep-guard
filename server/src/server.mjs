import { ScreenShareService } from './screen-share.mjs';
import { createServer } from "node:http";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { loadConfig } from "./config.mjs";
import { GuardService, publicState } from "./guard-state.mjs";
import { DeviceStatusService, publicDeviceStatus } from "./device-status.mjs";
import { bearer, json, readJson, readBody, sameSecret } from "./http-utils.mjs";
import { JsonStore } from "./json-store.mjs";
import { handleMcp } from "./mcp.mjs";
import { OAuthService } from "./oauth.mjs";

export async function createSleepGuardServer(config) {
  const store = new JsonStore(config.dataDir);
  await store.init();
  const guard = new GuardService(store, config);
  const deviceStatus = new DeviceStatusService(store);
  const oauth = new OAuthService(store, config);
  const screenShare = new ScreenShareService();

  const server = createServer(async (request, response) => {
    const url = new URL(request.url, config.publicBaseUrl);
    try {
      if (request.method === "GET" && url.pathname === "/health") {
        return json(response, 200, { ok: true, service: "rabbit-sleep-guard", version: "0.3.1" });
      }
      if (["/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp"].includes(url.pathname)) {
        return oauth.protectedResource(response);
      }
      if (url.pathname === "/.well-known/oauth-authorization-server") return oauth.authorizationMetadata(response);
      if (url.pathname === "/oauth/register") return await oauth.register(request, response);
      if (request.method === "GET" && url.pathname === "/oauth/authorize") return await oauth.authorize(url, response);
      if (url.pathname === "/oauth/approve") return await oauth.approve(request, response);
      if (url.pathname === "/oauth/token") return await oauth.exchange(request, response);
      // ChatGPT keeps using the exact connection URL as the MCP transport URL.
      // Accept the origin as a compatibility alias so an existing connection
      // created without the documented `/mcp` suffix can still initialize.
      if (["/", "/mcp"].includes(url.pathname)) return await handleMcp(request, response, guard, deviceStatus, oauth, config, screenShare);

      if (url.pathname.startsWith("/api/device/")) {
        if (!sameSecret(bearer(request), config.androidDeviceToken)) return json(response, 401, { ok: false, error: "unauthorized" });
        if (request.method === "POST" && url.pathname === "/api/device/screen/poll") {
          return json(response, 200, screenShare.poll(await readJson(request)));
        }
        if (request.method === "POST" && url.pathname === "/api/device/screen/result") {
          let body;
          try { body = JSON.parse(await readBody(request, 3 * 1024 * 1024)); }
          catch { return json(response, 413, { ok: false, error: "invalid_or_large_body" }); }
          const result = screenShare.upload(body);
          return json(response, result.ok ? 200 : 422, result);
        }
        if (request.method === "GET" && url.pathname === "/api/device/status") {
          return json(response, 200, {
            ok: true,
            ...publicState(await guard.status()),
            device_status: publicDeviceStatus(await deviceStatus.status()),
          });
        }
        if (request.method === "POST" && url.pathname === "/api/device/report") {
          const payload = await readJson(request);
          const report = await deviceStatus.report(payload);
          if (!report.ok) return json(response, 422, report);
          return json(response, 200, {
            ok: true,
            ...publicState(await guard.status()),
            device_status: publicDeviceStatus(report.status),
          });
        }
        if (request.method === "POST" && url.pathname === "/api/device/event") {
          const payload = await readJson(request);
          const transition = await guard.event(payload, payload.source ?? "android_app");
          if (!transition.ok) return json(response, 422, transition);
          return json(response, 200, {
            ok: true,
            event: payload.event,
            ignored: transition.ignored,
            auto_started: transition.auto_started,
            stage: transition.stage,
            ...publicState(transition.state),
            event_id: transition.event_id,
          });
        }
        return json(response, 404, { ok: false, error: "not_found" });
      }

      if (url.pathname.startsWith("/api/control/")) {
        if (!sameSecret(bearer(request), config.codexControlToken)) return json(response, 401, { ok: false, error: "unauthorized" });
        if (request.method === "GET" && url.pathname === "/api/control/screen/status") return json(response, 200, screenShare.status());
        if (request.method === "POST" && url.pathname === "/api/control/screen/request") {
          const result = screenShare.request((await readJson(request)).viewer);
          return json(response, result.ok ? 200 : 409, result);
        }
        if (request.method === "GET" && url.pathname.startsWith("/api/control/screen/result/")) {
          return json(response, 200, screenShare.result(url.pathname.split('/').pop()));
        }
        if (request.method === "GET" && url.pathname === "/api/control/status") {
          return json(response, 200, { ok: true, ...publicState(await guard.status()) });
        }
        if (request.method === "GET" && url.pathname === "/api/control/device-status") {
          return json(response, 200, { ok: true, device_status: publicDeviceStatus(await deviceStatus.status()) });
        }
        if (request.method === "POST" && url.pathname === "/api/control/activate") {
          const body = await readJson(request);
          const result = await guard.event({ event: "sleep_guard_started", ends_at: body.ends_at }, "server_control_api");
          return json(response, result.ok ? 200 : 422, { ok: result.ok, ...publicState(result.state), error: result.error });
        }
        if (request.method === "POST" && url.pathname === "/api/control/deactivate") {
          const result = await guard.event({ event: "sleep_guard_ended" }, "server_control_api");
          return json(response, result.ok ? 200 : 422, { ok: result.ok, ...publicState(result.state), error: result.error });
        }
        return json(response, 404, { ok: false, error: "not_found" });
      }
      return json(response, 404, { error: "not_found" });
    } catch (error) {
      console.error(new Date().toISOString(), request.method, url.pathname, error);
      if (!response.headersSent) return json(response, 500, { ok: false, error: "internal_error" });
      response.end();
    }
  });
  server.on("close", () => screenShare.close());
  return { server, guard, deviceStatus, oauth, store, screenShare };
}

const isDirectEntry = fileURLToPath(import.meta.url) === resolve(process.argv[1]);
const isPm2Entry = process.env.SLEEP_GUARD_PM2_ENTRY === "1";

if (isDirectEntry || isPm2Entry) {
  const config = loadConfig();
  const { server } = await createSleepGuardServer(config);
  server.listen(config.port, "127.0.0.1", () => {
    console.log(`Rabbit Sleep Guard listening on 127.0.0.1:${config.port}`);
  });
}
