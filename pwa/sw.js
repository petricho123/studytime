const prefix = 'three-day-study-' + self.registration.scope + '-';
const cacheName = prefix + '__CACHE_VERSION__';
const appFiles = __APP_FILES__;
const urls = new Set(appFiles.map(path => new URL(path, self.registration.scope).href));

self.addEventListener('install', event => {
  event.waitUntil(caches.open(cacheName).then(cache => cache.addAll(
    appFiles.map(path => new Request(new URL(path, self.registration.scope), { cache: 'reload' }))
  )));
});

self.addEventListener('activate', event => {
  event.waitUntil(caches.keys().then(keys => Promise.all(
    keys.filter(key => key.startsWith(prefix) && key !== cacheName).map(key => caches.delete(key))
  )).then(() => self.clients.claim()));
});

self.addEventListener('fetch', event => {
  if (event.request.method !== 'GET') return;
  if (event.request.mode === 'navigate') {
    event.respondWith(caches.open(cacheName).then(cache =>
      cache.match(new URL('./index.html', self.registration.scope)).then(response => response || fetch(event.request))
    ));
  } else if (urls.has(event.request.url)) {
    event.respondWith(caches.open(cacheName).then(cache =>
      cache.match(event.request).then(response => response || fetch(event.request))
    ));
  }
});
