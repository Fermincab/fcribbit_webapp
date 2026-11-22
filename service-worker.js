const CACHE_NAME = 'ribbit-cache-v3';
// Relative paths so the app works from a subpath (e.g. GitHub Pages).
const urlsToCache = [
    './',
    './index.html',
    './ribbit.wasm',
    './ribbit.webmanifest',
    './favicon.ico',
    './ribbiticon-32.png',
    './ribbiticon-64.png',
    './ribbiticon-128.png',
    './ribbiticon-256.png',
    './ribbiticon-512.png'
];

self.addEventListener('install', event => {
    event.waitUntil(
        caches.open(CACHE_NAME)
            .then(cache => cache.addAll(urlsToCache))
            .then(() => self.skipWaiting())
    );
});

// remove caches from older versions so stale files are never served
self.addEventListener('activate', event => {
    event.waitUntil(
        caches.keys()
            .then(keys => Promise.all(keys.filter(key => key !== CACHE_NAME).map(key => caches.delete(key))))
            .then(() => self.clients.claim())
    );
});

// network first, so updates show up right away; fall back to the cache when offline
self.addEventListener('fetch', event => {
    if (event.request.method !== 'GET') {
        return;
    }
    event.respondWith(
        fetch(event.request)
            .then(response => {
                if (response.ok && new URL(event.request.url).origin === self.location.origin) {
                    const copy = response.clone();
                    caches.open(CACHE_NAME).then(cache => cache.put(event.request, copy));
                }
                return response;
            })
            .catch(() => caches.match(event.request))
    );
});
