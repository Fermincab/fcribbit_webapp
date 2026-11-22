const CACHE_NAME = 'ribbit-cache-v2';
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
            .then(cache => {
                return cache.addAll(urlsToCache);
            })
    );
});

self.addEventListener('fetch', event => {
    event.respondWith(
        caches.match(event.request)
            .then(response => {
                return response || fetch(event.request);
            })
    );
});