/*
 * Service worker for the llama.cpp Web UI PWA.
 * Caches the static app shell so the UI is installable and loads offline.
 * API traffic (/api, /v1, streaming, non-GET) is never intercepted.
 */
const CACHE_VERSION = 'llm-webui-v2';
const APP_SHELL = [
	'./',
	'./index.html',
	'./bundle.js',
	'./bundle.css',
	'./manifest.webmanifest',
	'./icon-192.png',
	'./icon-512.png',
	'./icon-512-maskable.png'
];

self.addEventListener('install', (event) => {
	event.waitUntil(
		caches.open(CACHE_VERSION).then((cache) =>
			// Best-effort: don't fail the whole install if one asset 404s.
			Promise.allSettled(APP_SHELL.map((url) => cache.add(url)))
		).then(() => self.skipWaiting())
	);
});

self.addEventListener('activate', (event) => {
	event.waitUntil(
		caches.keys().then((keys) =>
			Promise.all(keys.filter((k) => k !== CACHE_VERSION).map((k) => caches.delete(k)))
		).then(() => self.clients.claim())
	);
});

self.addEventListener('fetch', (event) => {
	const req = event.request;
	if (req.method !== 'GET') {
		return; // never touch POST/PUT (chat, model load, etc.)
	}
	const url = new URL(req.url);
	if (url.origin !== self.location.origin) {
		return; // let cross-origin requests go straight to the network
	}
	// Bypass all API / streaming endpoints.
	const p = url.pathname;
	if (p.startsWith('/api') || p.startsWith('/v1/') || p === '/models' ||
		p.startsWith('/models/') || p === '/props' || p === '/slots' ||
		p === '/health' || p === '/metrics' || p === '/cors-proxy') {
		return;
	}

	// Navigation requests: network-first, fall back to cached index.html.
	if (req.mode === 'navigate') {
		event.respondWith(
			fetch(req).catch(() => caches.match('./index.html'))
		);
		return;
	}

	// Static assets: cache-first, then network (and populate cache).
	event.respondWith(
		caches.match(req).then((cached) => {
			if (cached) {
				return cached;
			}
			return fetch(req).then((res) => {
				if (res && res.ok && res.type === 'basic') {
					const copy = res.clone();
					caches.open(CACHE_VERSION).then((cache) => cache.put(req, copy));
				}
				return res;
			});
		})
	);
});
