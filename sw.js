const CACHE="gson-web-v7";
const CORE=["./","./index.html","./styles.css?v=7","./app.js?v=7","./geo-worker.js?v=6","./favicon.svg","./vendor/jszip.min.js","./manifest.webmanifest"];
self.addEventListener("install",e=>{e.waitUntil((async()=>{const c=await caches.open(CACHE);await Promise.all(CORE.map(async p=>{try{const r=await fetch(p,{cache:"reload"});if(r.ok)await c.put(p,r)}catch(_){}}));await self.skipWaiting()})())});
self.addEventListener("activate",e=>e.waitUntil((async()=>{const keys=await caches.keys();await Promise.all(keys.filter(k=>k.startsWith("gson-web-")&&k!==CACHE).map(k=>caches.delete(k)));await self.clients.claim()})()));
self.addEventListener("fetch",e=>{const u=new URL(e.request.url);if(u.origin!==location.origin)return;e.respondWith(caches.match(e.request).then(async r=>{if(r)return r;const res=await fetch(e.request);if(res.ok&&e.request.method==="GET"){const c=await caches.open(CACHE);c.put(e.request,res.clone()).catch(()=>{})}return res}))});
