// Cloudflare Worker: serves the Live News feed from a KV namespace bound as FEED.
//   GET /test/channels.json   GET /prod/channels.json   GET /icons/<name>.jpg
// The push script writes these keys with the Cloudflare API. The TV app only reads them.
// Short install links /get and /livenews send people to the newest release APK (302). Every release must attach the APK named LiveNewsIndia.apk, so this line never needs changing.
// Public read, no keys in the app. Anything else is a 404.

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS",
  "Access-Control-Allow-Headers": "If-None-Match",
  "Access-Control-Expose-Headers": "ETag",
};

const APK_URL = "https://github.com/nishantkumarsinha/live-news-tv/releases/latest/download/LiveNewsIndia.apk";
const SHORT_LINKS = new Set(["get", "livenews"]);

const JSON_RE = /^(test|prod)\/channels\.json$/;
const ICON_RE = /^icons\/[a-z0-9_]+\.jpg$/;

function plain(status, text) {
  return new Response(text, {
    status,
    headers: { ...CORS, "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "no-store" },
  });
}

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
    if (request.method !== "GET" && request.method !== "HEAD") return plain(405, "Method not allowed");

    let key;
    try {
      key = decodeURIComponent(new URL(request.url).pathname).replace(/^\/+/, "");
    } catch (e) {
      return plain(400, "Bad request");
    }
    if (SHORT_LINKS.has(key.toLowerCase().replace(/\/+$/, ""))) {
      return new Response(null, { status: 302, headers: { ...CORS, "Location": APK_URL, "Cache-Control": "no-store" } });
    }
    const isJson = JSON_RE.test(key);
    if (!isJson && !ICON_RE.test(key)) return plain(404, "Not found");

    // cacheTtl keeps a copy at the edge for 60 s, so a TV polling every 5 minutes costs very few KV reads.
    const body = await env.FEED.get(key, { type: "arrayBuffer", cacheTtl: 60 });
    if (body === null) return plain(404, "Not found");

    const digest = await crypto.subtle.digest("SHA-1", body);
    const etag = '"' + [...new Uint8Array(digest)].map(b => b.toString(16).padStart(2, "0")).join("") + '"';
    const headers = {
      ...CORS,
      "ETag": etag,
      "Content-Type": isJson ? "application/json; charset=utf-8" : "image/jpeg",
      // List: browsers and edge may reuse it for 60 s. Logos change rarely: one day.
      "Cache-Control": isJson ? "public, max-age=60" : "public, max-age=86400",
      "X-Content-Type-Options": "nosniff",
    };
    if (request.headers.get("If-None-Match") === etag) {
      return new Response(null, { status: 304, headers });
    }
    return new Response(request.method === "HEAD" ? null : body, { status: 200, headers });
  },
};
