/* Xs public-post DOM adapter. Packaged code only; no network, native bridge, or hidden state. */
(function () {
  'use strict';
  if (window.__xsObserver) { window.__xsObserver.stop(); }
  var MAX_CARDS = 24, MAX_BODY = 2048, runs = new Map(), serial = 0, stopped = false;
  var dirty = true, pending = false;
  function allowed() {
    if (location.origin !== 'https://x.com' && location.origin !== 'https://www.x.com') return false;
    var path = location.pathname;
    return /^\/(home|search|explore)\/?$/.test(path) || /^\/[A-Za-z0-9_]{1,15}\/status\/[0-9]{1,20}\/?$/.test(path);
  }
  function postUrl(href) {
    try {
      var u = new URL(href, location.href);
      if ((u.origin !== 'https://x.com' && u.origin !== 'https://www.x.com') ||
          !/^\/[A-Za-z0-9_]{1,15}\/status\/[0-9]{1,20}\/?$/.test(u.pathname)) return null;
      return u.origin + u.pathname.replace(/\/$/, '');
    } catch (_) { return null; }
  }
  function visible(el) {
    if (!el.isConnected) return 0;
    var style = getComputedStyle(el);
    if (style.display === 'none' || style.visibility !== 'visible' || Number(style.opacity) < 0.9) return 0;
    var r = el.getBoundingClientRect(), v = window.visualViewport;
    var left = v ? v.offsetLeft : 0, top = v ? v.offsetTop : 0;
    var width = v ? v.width : innerWidth, height = v ? v.height : innerHeight;
    if (r.width < 40 || r.height < 40 || width < 80 || height < 80) return 0;
    var x0 = Math.max(r.left, left), y0 = Math.max(r.top, top);
    var x1 = Math.min(r.right, left + width), y1 = Math.min(r.bottom, top + height);
    if (x1 <= x0 || y1 <= y0) return 0;
    // Long cards normalize to viewport-sized readable area, rather than the entire article.
    var area = Math.min(r.width, width) * Math.min(r.height, height);
    var fraction = (x1 - x0) * (y1 - y0) / area;
    if (fraction < 0.6) return 0;
    // All sampled readable points must belong to the same article. Unknown overlays fail closed.
    for (var yi = 1; yi <= 3; yi++) for (var xi = 1; xi <= 3; xi++) {
      var hit = document.elementFromPoint(x0 + (x1 - x0) * xi / 4, y0 + (y1 - y0) * yi / 4);
      if (!hit || !el.contains(hit)) return 0;
    }
    return Math.min(1, fraction);
  }
  function scan() {
    dirty = false;
    var cards = [], seen = new Set(), bodyBudget = 6000;
    if (stopped || !allowed() || document.visibilityState !== 'visible' ||
        document.querySelector('[role="dialog"], [aria-modal="true"], [data-testid="tweetTextarea_0"]')) {
      runs.clear(); return cards;
    }
    var nodes = document.querySelectorAll('article[data-testid="tweet"]');
    for (var i = 0; i < nodes.length && cards.length < MAX_CARDS; i++) {
      // Bound DOM work even if a broken page contains thousands of attached articles.
      if (i >= 96) break;
      var el = nodes[i], links = el.querySelectorAll('a[href*="/status/"]'), url = null;
      for (var j = 0; j < links.length && j < 24; j++) {
        if (links[j].closest('article') !== el) continue;
        // Prefer the timestamp link so quoted-post URLs cannot become the outer post identity.
        if (links[j].querySelector('time')) { url = postUrl(links[j].href); break; }
      }
      if (!url) continue;
      var id = /\/status\/([0-9]+)/.exec(url)[1];
      if (seen.has(id)) continue;
      var fraction = visible(el);
      if (!fraction) continue;
      seen.add(id);
      if (!runs.has(id)) runs.set(id, String(++serial));
      var body = el.querySelector('[data-testid="tweetText"]');
      var time = el.querySelector('time'), published = time ? Date.parse(time.getAttribute('datetime')) : NaN;
      // innerText returns rendered text, not HTML, form values, script state or hidden textContent.
      var text = body ? body.innerText.replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, '').slice(0, Math.min(MAX_BODY, bodyBudget)) : '';
      bodyBudget -= text.length;
      cards.push({url:url,author:url.split('/')[3],body:text,
        publishedAtMs:Number.isFinite(published) && published > 0 ? published : null,
        readableFraction:fraction,run:runs.get(id)});
    }
    runs.forEach(function (_, id) { if (!seen.has(id)) runs.delete(id); });
    return cards;
  }
  function changed() {
    if (stopped) return;
    dirty = true;
    if (pending) return;
    pending = true;
    requestAnimationFrame(function () { pending = false; if (dirty) scan(); });
  }
  var observer = new MutationObserver(changed);
  observer.observe(document.documentElement, {childList:true,subtree:true,attributes:true,
    attributeFilter:['style','class','hidden','aria-hidden']});
  addEventListener('scroll',changed,true);
  addEventListener('resize',changed,true);
  document.addEventListener('visibilitychange',changed,true);
  var timer = setInterval(function () { if (!stopped) scan(); },100);
  window.__xsObserver = {
    read:function () { return {v:1,url:location.href,cards:scan()}; },
    stop:function () {
      stopped = true; runs.clear(); observer.disconnect(); clearInterval(timer);
      removeEventListener('scroll',changed,true); removeEventListener('resize',changed,true);
      document.removeEventListener('visibilitychange',changed,true);
    }
  };
})();
