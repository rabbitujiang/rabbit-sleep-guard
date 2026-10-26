import { appendFile, mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";

async function readJson(path, fallback) {
  try {
    return JSON.parse(await readFile(path, "utf8"));
  } catch (error) {
    if (error?.code === "ENOENT") return structuredClone(fallback);
    throw error;
  }
}

export class JsonStore {
  #tail = Promise.resolve();

  constructor(dataDir) {
    this.dataDir = dataDir;
    this.statePath = join(dataDir, "state.json");
    this.deviceStatusPath = join(dataDir, "device-status.json");
    this.appUsagePath = join(dataDir, "app-usage.json");
    this.authPath = join(dataDir, "auth.json");
    this.eventsPath = join(dataDir, "events.jsonl");
  }

  async init() {
    await mkdir(this.dataDir, { recursive: true, mode: 0o700 });
  }

  withLock(operation) {
    const run = this.#tail.then(operation, operation);
    this.#tail = run.catch(() => undefined);
    return run;
  }

  readState(fallback) {
    return readJson(this.statePath, fallback);
  }

  writeState(value) {
    return this.#atomicWrite(this.statePath, value);
  }

  readDeviceStatus(fallback) {
    return readJson(this.deviceStatusPath, fallback);
  }

  writeDeviceStatus(value) {
    return this.#atomicWrite(this.deviceStatusPath, value);
  }

  readAppUsage(fallback) { return readJson(this.appUsagePath, fallback); }

  writeAppUsage(value) { return this.#atomicWrite(this.appUsagePath, value); }

  readAuth(fallback) {
    return readJson(this.authPath, fallback);
  }

  writeAuth(value) {
    return this.#atomicWrite(this.authPath, value);
  }

  async appendEvent(value) {
    await mkdir(dirname(this.eventsPath), { recursive: true, mode: 0o700 });
    await appendFile(this.eventsPath, `${JSON.stringify(value)}\n`, { encoding: "utf8", mode: 0o600 });
  }

  async #atomicWrite(path, value) {
    await mkdir(dirname(path), { recursive: true, mode: 0o700 });
    const temporary = `${path}.${process.pid}.${Date.now()}.tmp`;
    await writeFile(temporary, `${JSON.stringify(value, null, 2)}\n`, { encoding: "utf8", mode: 0o600 });
    await rename(temporary, path);
  }
}
