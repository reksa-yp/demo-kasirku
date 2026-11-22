/* PinaPos demo - service worker: halaman tetap terbuka saat tidak ada internet.
   Strategi: jaringan dulu (selalu dapat versi terbaru saat online, memvalidasi ulang ke server
   sehingga tidak tertahan cache HTTP), cadangan dari cache saat offline.
   Hanya file satu domain dengan halaman ini yang disimpan; panggilan ke Supabase tidak disentuh.
   Baris di bawah diubah oleh tools/bump-version.sh supaya browser mendeteksi service worker baru. */
const CACHE = 'pinapos-demo-v1.4.0';

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(['./', './index.html'])).then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', e => {
  const req = e.request, url = new URL(req.url);
  if (req.method !== 'GET' || url.origin !== self.location.origin) { return; }
  if (url.pathname.endsWith('/version.json')) { return; }   // cek versi selalu langsung ke jaringan
  e.respondWith(
    fetch(req.url, { cache: 'no-cache' }).then(res => {
      if (res && res.ok) { const copy = res.clone(); caches.open(CACHE).then(c => c.put(req, copy)); }
      return res;
    }).catch(() => caches.match(req, { ignoreSearch: true }).then(hit => hit || caches.match('./index.html')))
  );
});
