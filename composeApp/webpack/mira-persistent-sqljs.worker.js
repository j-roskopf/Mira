// Persistent sql.js worker for SQLDelight WebWorkerDriver.
// Loads/saves the SQLite file to IndexedDB so auth + recipes survive page refresh.
importScripts("/sql-wasm.js");

const STORAGE_KEY = "mira_web_sqlite";
const SAVE_DEBOUNCE_MS = 500;

let db = null;
let saveTimer = null;
let ready = null;

function openIndexedDb() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open("mira", 1);
    request.onerror = () => reject(request.error);
    request.onupgradeneeded = () => {
      request.result.createObjectStore("kv");
    };
    request.onsuccess = () => resolve(request.result);
  });
}

async function loadDbBlob() {
  const idb = await openIndexedDb();
  return new Promise((resolve, reject) => {
    const tx = idb.transaction("kv", "readonly");
    const req = tx.objectStore("kv").get(STORAGE_KEY);
    req.onerror = () => reject(req.error);
    req.onsuccess = () => {
      idb.close();
      resolve(req.result ?? null);
    };
  });
}

async function saveDbBlob(blob) {
  const idb = await openIndexedDb();
  return new Promise((resolve, reject) => {
    const tx = idb.transaction("kv", "readwrite");
    tx.objectStore("kv").put(blob, STORAGE_KEY);
    tx.oncomplete = () => {
      idb.close();
      resolve();
    };
    tx.onerror = () => reject(tx.error);
  });
}

function schedulePersist() {
  if (saveTimer) clearTimeout(saveTimer);
  // Defer export off the SQL message handler — db.export() is synchronous and large.
  saveTimer = setTimeout(() => {
    saveTimer = null;
    try {
      const blob = db.export();
      saveDbBlob(blob).catch(() => {});
    } catch (_) {}
  }, SAVE_DEBOUNCE_MS);
}

function createDatabase() {
  return initSqlJs({ locateFile: (file) => "/sql-wasm.wasm" }).then((SQL) => {
    return loadDbBlob().then((blob) => {
      db = blob ? new SQL.Database(blob) : new SQL.Database();
    });
  });
}

function onModuleReady() {
  const data = this.data;

  switch (data && data.action) {
    case "exec":
      if (!data.sql) {
        throw new Error("exec: Missing query string");
      }
      const results = db.exec(data.sql, data.params)[0] ?? { values: [] };
      return postMessage({ id: data.id, results });
    case "begin_transaction":
      return postMessage({
        id: data.id,
        results: db.exec("BEGIN TRANSACTION;"),
      });
    case "end_transaction":
      schedulePersist();
      return postMessage({
        id: data.id,
        results: db.exec("END TRANSACTION;"),
      });
    case "rollback_transaction":
      return postMessage({
        id: data.id,
        results: db.exec("ROLLBACK TRANSACTION;"),
      });
    default:
      throw new Error(`Unsupported action: ${data && data.action}`);
  }
}

function onError(err) {
  return postMessage({
    id: this.data.id,
    error: err,
  });
}

if (typeof importScripts === "function") {
  db = null;
  ready = createDatabase();
  self.onmessage = (event) => {
    return ready
      .then(onModuleReady.bind(event))
      .catch(onError.bind(event));
  };
}
