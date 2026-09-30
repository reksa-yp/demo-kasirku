#!/usr/bin/env python3
"""
Siapkan assets aplikasi Android dari versi web (dijalankan sebelum Gradle).

Mengambil ../kasirku/index.html dan ../cuciaja/index.html, lalu mengubah
backend "demo" (data contoh di browser) menjadi backend LOKAL:
  - data mulai kosong (hanya akun admin / admin123 + pengaturan bawaan),
  - tersimpan di file milik aplikasi di HP (lewat jembatan Android),
  - bisa dibackup / dipulihkan dari menu Pengaturan.
Hasilnya ditulis ke app/src/<aplikasi>/assets/index.html.
"""
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent

LOCAL_BACKEND = r"""
/* ---------- backend LOKAL untuk aplikasi Android (tanpa server) ---------- */
function createLocalBackend_(APP, BRIDGE) {
  var N = window[BRIDGE] || null, LKEY = APP + '_local_v1';
  var state = null, backend = null, timer = null, cache = {};
  var platform = {
    db: null,
    sha256: sha256Hex_,
    now: function () { return new Date(); },
    lock: function (fn) { return fn(); },
    secret: function () { return state.secret; },
    cacheGet: function (k) { var e = cache[k]; return (e && e.exp > Date.now()) ? e.v : null; },
    cachePut: function (k, v, sec) { cache[k] = { v: v, exp: Date.now() + sec * 1000 }; }
  };
  function emptyState() {
    var t = {}; Object.keys(SCHEMA).forEach(function (k) { if (k !== 'Settings') { t[k] = []; } });
    return { v: 1, secret: sha256Hex_(APP + Math.random() + Date.now() + Math.random()), tables: t, kv: {} };
  }
  function readRaw() {
    try { return (N && N.readData) ? N.readData() : localStorage.getItem(LKEY); } catch (e) { return null; }
  }
  function persist() {
    if (timer) { clearTimeout(timer); timer = null; }
    if (!state) { return; }
    var txt = JSON.stringify(state), ok = true;
    try {
      if (N && N.writeData) { ok = N.writeData(txt); } else { localStorage.setItem(LKEY, txt); }
      if (ok === false) { throw new Error('penulisan file gagal'); }
    } catch (e) { if (window.ksrStorageError) { window.ksrStorageError(e); } }
  }
  function save() { if (!timer) { timer = setTimeout(persist, 200); } }
  function use(st) {
    Object.keys(SCHEMA).forEach(function (k) { if (k !== 'Settings' && !Array.isArray(st.tables[k])) { st.tables[k] = []; } });
    if (!st.kv) { st.kv = {}; }
    if (!st.secret) { st.secret = emptyState().secret; }
    state = st;
    platform.db = memoryDb_(state, save);
    backend = createBackend_(platform);
    backend.seedBase();
    persist();
  }
  var st = null;
  try { var raw = readRaw(); if (raw) { st = JSON.parse(raw); if (!st || st.v !== 1 || !st.tables) { st = null; } } } catch (e) { st = null; }
  use(st || emptyState());
  document.addEventListener('visibilitychange', function () { if (document.visibilityState === 'hidden') { persist(); } });
  window.addEventListener('pagehide', persist);
  return {
    ready: Promise.resolve(),
    handle: function (action, token, payload) { return backend.handle(action, token, payload); },
    flush: function () { persist(); return Promise.resolve(); },
    stats: function () {
      var T = state.tables;
      return { transactions: (T.Transactions || []).length + (T.Orders || []).length, products: (T.Products || []).length, customers: (T.Customers || []).length };
    },
    exportData: function () { return { app: APP, v: 1, exported_at: new Date().toISOString(), tables: state.tables, kv: state.kv }; },
    restoreData: function (b) {
      if (!b || b.app !== APP || !b.tables) { return Promise.reject(new Error('File backup tidak valid.')); }
      var st2 = emptyState();
      Object.keys(st2.tables).forEach(function (k) { if (Array.isArray(b.tables[k])) { st2.tables[k] = b.tables[k]; } });
      st2.kv = b.kv || {};
      use(st2);
      return Promise.resolve();
    }
  };
}
"""

APPS = {
    'kasirku': ('KASIRKU', 'KasirkuAndroid'),
    'cuciaja': ('CUCIAJA', 'CuciAjaAndroid'),
}


def build(app):
    prefix, bridge = APPS[app]
    src = (ROOT / app / 'index.html').read_text(encoding='utf-8')

    def rep(old, new):
        nonlocal src
        n = src.count(old)
        if n != 1:
            sys.exit(f'[{app}] pola tidak ditemukan tepat 1x ({n}x): {old[:60]!r}')
        src = src.replace(old, new)

    rep(f'window.{prefix}_DEMO = (function () {{', f'window.{prefix}_LOCAL = (function () {{')
    rep('return createDemoBackend(storage, "");\n})();\n\n' + f'window.{prefix}_DEMO.offline = false;',
        LOCAL_BACKEND + f"return createLocalBackend_('{app}', '{bridge}');\n}})();")
    out = HERE / 'app' / 'src' / app / 'assets' / 'index.html'
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(src, encoding='utf-8')
    print(f'OK {app}: {out.relative_to(HERE)} ({len(src):,} karakter)')


if __name__ == '__main__':
    for a in APPS:
        build(a)
