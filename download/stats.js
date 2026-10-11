/* PinaPos & CuciAja — Copyright (c) 2026 ASKER. All rights reserved. Powered by ASKER.
 *
 * Menampilkan jumlah unduhan APK dari halaman Releases GitHub dan mengarahkan
 * tombol download ke file APK terbaru.
 *   <b data-count="pinapos">  → diisi total unduhan (semua versi)
 *   [data-stat="pinapos"]     → ditampilkan setelah angka berhasil dimuat
 *   <a data-dl="pinapos">     → href diganti ke file APK terbaru
 *   [data-ver="pinapos"]      → diisi nomor versi terbaru
 * Jika GitHub tidak bisa dihubungi, halaman tetap berjalan seperti biasa.
 */
(function () {
  var API = 'https://api.github.com/repos/reksa-yp/demo-kasirku/releases?per_page=100';
  var CACHE = 'asker_dl_stats_v1', TTL = 10 * 60 * 1000;
  // Kasirku adalah nama lama PinaPos, jadi ikut dihitung sebagai PinaPos.
  var APPS = { pinapos: /^(pinapos|kasirku)[^/]*\.apk$/i, cuciaja: /^cuciaja[^/]*\.apk$/i };

  function summarize(releases) {
    var out = {};
    Object.keys(APPS).forEach(function (k) { out[k] = { count: 0, url: null, at: '', ver: '' }; });
    (releases || []).forEach(function (r) {
      if (r.draft) { return; }
      (r.assets || []).forEach(function (a) {
        Object.keys(APPS).forEach(function (k) {
          if (!APPS[k].test(a.name)) { return; }
          out[k].count += a.download_count || 0;
          var at = a.updated_at || r.published_at || '';
          if (at > out[k].at) { var v = /(\d+\.\d+\.\d+)/.exec(a.name) || /v?(\d+\.\d+\.\d+)/.exec(r.tag_name || ''); out[k].at = at; out[k].url = a.browser_download_url; out[k].ver = v ? v[1] : ''; }
        });
      });
    });
    return out;
  }

  function apply(stats) {
    var nf = new Intl.NumberFormat('id-ID');
    Object.keys(stats).forEach(function (k) {
      var s = stats[k];
      document.querySelectorAll('[data-count="' + k + '"]').forEach(function (el) { el.textContent = nf.format(s.count); });
      if (s.count > 0) { document.querySelectorAll('[data-stat="' + k + '"]').forEach(function (el) { el.hidden = false; }); }
      if (s.ver) { document.querySelectorAll('[data-ver="' + k + '"]').forEach(function (el) { el.textContent = s.ver; }); }
      if (s.url) { document.querySelectorAll('a[data-dl="' + k + '"]').forEach(function (el) { el.href = s.url; }); }
    });
    var total = Object.keys(stats).reduce(function (n, k) { return n + stats[k].count; }, 0);
    document.querySelectorAll('[data-count="total"]').forEach(function (el) { el.textContent = nf.format(total); });
    if (total > 0) { document.querySelectorAll('[data-stat="total"]').forEach(function (el) { el.hidden = false; }); }
  }

  function readCache() {
    try {
      var c = JSON.parse(sessionStorage.getItem(CACHE) || 'null');
      return c && Date.now() - c.t < TTL ? c.s : null;
    } catch (e) { return null; }
  }
  function writeCache(s) { try { sessionStorage.setItem(CACHE, JSON.stringify({ t: Date.now(), s: s })); } catch (e) {} }

  function run() {
    var cached = readCache();
    if (cached) { apply(cached); return; }
    if (!window.fetch) { return; }
    fetch(API, { headers: { Accept: 'application/vnd.github+json' } })
      .then(function (r) { if (!r.ok) { throw new Error(r.status); } return r.json(); })
      .then(function (data) { var s = summarize(data); writeCache(s); apply(s); })
      .catch(function () { /* angka tidak ditampilkan; tombol tetap memakai link bawaan */ });
  }

  if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', run); } else { run(); }
})();
