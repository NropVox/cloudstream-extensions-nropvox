// Runtime endpoints for the Cinecat Workers, read by the app (conf() in src/lib/config.ts) and by
// worker-origin-rewrite.js. Served `no-store` straight from /etc/nginx, so changing a value here
// takes effect on the next page load -- no rebuild, no deploy, no Cloudflare cache purge.
// Move a service by editing its line; every VITE_*_URL here wins over the defaults baked into the build.
window.__CONFIG__ = Object.assign(window.__CONFIG__ || {}, {
	// Every Worker is <name>.<WORKER_DOMAIN>. Dulo's prebuilt bundle and its API (WORKER_DOMAIN in /etc/dulo-api/env) read this,
	// so switch_account.py moving the stack to another Cloudflare account needs no rebuild of either.
	WORKER_DOMAIN: 'iamwatchingyou.site',
	// Three identical copies of the zcdn Worker (same tokens, same data): one is picked per page load so the
	// Cloudflare analytics split by Worker name. Any copy can serve any play link.
	VITE_ZCDN_URL: ['zcdn1', 'zcdn2', 'zcdn3'].map((n) => 'https://' + n + '.iamwatchingyou.site')[Math.floor(Math.random() * 3)],
	// Three identical copies of the proxy Worker, one picked per page load (analytics split by Worker name).
	VITE_PROXY_URL: ['zstream-proxy1', 'zstream-proxy2', 'zstream-proxy3'].map((n) => 'https://' + n + '.iamwatchingyou.site')[Math.floor(Math.random() * 3)],
	VITE_ELEVENLABS_URL: 'https://elevenlabs-proxy.iamwatchingyou.site',
	VITE_ACR_API_URL: 'https://skip-intro.iamwatchingyou.site',
	VITE_DISCOVER_API_URL: 'https://websearch.iamwatchingyou.site',
	VITE_RELEASE_API_URL: 'https://zrelease.iamwatchingyou.site',
	VITE_CHAPTERS_API_URL: 'https://zchapters.iamwatchingyou.site',
	VITE_CHAPTERS_PIPELINE_URL: 'https://zchapters-pipeline.iamwatchingyou.site',
	VITE_PARTY_API_URL: 'https://zparty.iamwatchingyou.site',
	// Not migrated yet (still on the old account):
	VITE_MUSIC_API_URL: 'https://zmusic.bluevelvet.space'
});
