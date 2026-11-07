/* Xs public-post DOM adapter. Packaged code only; no network, native bridge, or hidden state. */
(function () {
  'use strict';
  if (window.__xsObserver) { window.__xsObserver.stop(); }
  var MAX_CARDS = 24, MAX_BODY = 2048, runs = new Map(), serial = 0, stopped = false;
  var pending = null, timer = null, suspended = false;
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
  function shown(el) {
    // Computed opacity is not inherited: an invisible parent can still be hit-tested.
    // Check a bounded ancestor chain before reading any rendered text.
    var ancestor = el, depth = 0;
    while (ancestor && depth++ < 32) {
      var style = getComputedStyle(ancestor);
      if (style.display === 'none' || style.visibility !== 'visible' || Number(style.opacity) < 0.9 ||
          (ancestor.getAttribute && (ancestor.getAttribute('aria-hidden') === 'true' || ancestor.hasAttribute('inert')))) return false;
      ancestor = ancestor.parentElement;
    }
    return !ancestor;
  }
  function visible(el) {
    if (!el.isConnected || !shown(el)) return 0;
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
  // Structural evidence only. Native code decides completeness for a matching detail route.
  // It does not claim server-side全文, an edit timestamp or reading completion.
  function textEvidence(el, body, raw, text) {
    if (!body || typeof body.getBoundingClientRect !== 'function') return null;
    var style = getComputedStyle(body), rect = body.getBoundingClientRect(), v = window.visualViewport;
    var clamp = style.webkitLineClamp;
    var lines = clamp === 'none' || clamp === '0' ? 0 : /^[1-9][0-9]*$/.test(clamp) ? Number(clamp) : -1;
    var left = v ? v.offsetLeft : 0, top = v ? v.offsetTop : 0;
    var width = v ? v.width : innerWidth, height = v ? v.height : innerHeight;
    var ancestor = body, depth = 0, clipped = false;
    while (ancestor && depth++ < 32) {
      var parentStyle = getComputedStyle(ancestor);
      if (parentStyle.textOverflow === 'ellipsis' || /^(hidden|clip)$/.test(parentStyle.overflowX) ||
          /^(hidden|clip)$/.test(parentStyle.overflowY) || /^[1-9][0-9]*$/.test(parentStyle.webkitLineClamp)) clipped = true;
      ancestor = ancestor.parentElement;
    }
    if (ancestor) clipped = true;
    return {rawLength:raw.length,clientHeight:body.clientHeight,scrollHeight:body.scrollHeight,
      lineClamp:lines,overflow:style.overflowY || 'unknown',clipped:clipped,
      hasMore:!!el.querySelector('[data-testid="tweet-text-show-more-link"], [data-testid="tweetTextShowMore"], [data-testid="showMore"]'),
      textVisible:style.display !== 'none' && style.visibility === 'visible' && Number(style.opacity) >= 0.9 &&
        rect.width > 0 && rect.height > 0 && rect.left >= left && rect.top >= top &&
        rect.right <= left + width && rect.bottom <= top + height && raw.length === text.length};
  }
  function scan(withText) {
    var cards = [], seen = new Set(), bodyBudget = 6000;
    if (stopped || suspended || !allowed() || document.visibilityState !== 'visible' ||
        document.querySelector('[role="dialog"], [aria-modal="true"], [data-testid="tweetTextarea_0"], iframe, input[type="password"], input[autocomplete="one-time-code"], [data-testid="dmComposerTextInput"]')) {
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
      if (withText) {
        var body = el.querySelector('[data-testid="tweetText"]');
        if (body && (!shown(body) || (body.closest && body.closest('article') !== el))) body = null;
        var time = el.querySelector('time'), published = time ? Date.parse(time.getAttribute('datetime')) : NaN;
        // Only a native read requests text. Background geometry checks never copy body strings.
        // innerText returns rendered text, not HTML, form values or hidden textContent.
        var raw = body && bodyBudget > 0 ? body.innerText : '';
        var text = raw.replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, '').slice(0, Math.min(MAX_BODY, bodyBudget));
        // A UTF-16 budget must never split a rendered astral character into invalid Unicode.
        var tail = text.charCodeAt(text.length - 1);
        if (tail >= 0xd800 && tail <= 0xdbff) text = text.slice(0, -1);
        bodyBudget -= text.length;
        cards.push({url:url,author:url.split('/')[3],body:text,
          publishedAtMs:Number.isFinite(published) && published > 0 ? published : null,
          readableFraction:fraction,run:runs.get(id),textEvidence:textEvidence(el,body,raw,text)});
      } else cards.push(null);
    }
    runs.forEach(function (_, id) { if (!seen.has(id)) runs.delete(id); });
    return cards;
  }
  function changed() {
    if (stopped || suspended || document.visibilityState !== 'visible' || pending !== null) return;
    pending = requestAnimationFrame(function () { pending = null; scan(false); });
  }
  var observer = new MutationObserver(changed);
  function monitor() {
    if (stopped || suspended || document.visibilityState !== 'visible' || timer !== null) return;
    observer.observe(document.documentElement, {childList:true,subtree:true,attributes:true,characterData:true,
      attributeFilter:['style','class','hidden','aria-hidden','href','data-testid','inert']});
    timer = setInterval(function () { if (!stopped) scan(false); },200);
    changed();
  }
  function unmonitor() {
    observer.disconnect();
    if (timer !== null) clearInterval(timer);
    timer = null;
    if (pending !== null && typeof cancelAnimationFrame === 'function') cancelAnimationFrame(pending);
    pending = null; runs.clear();
  }
  function visibility() {
    if (document.visibilityState !== 'visible') unmonitor();
    else monitor();
  }
  function suspend() { suspended = true; unmonitor(); }
  function resume() { if (stopped) return; suspended = false; monitor(); }
  addEventListener('scroll',changed,true);
  addEventListener('resize',changed,true);
  addEventListener('popstate',changed,true);
  addEventListener('hashchange',changed,true);
  addEventListener('pagehide',suspend,true);
  addEventListener('pageshow',resume,true);
  document.addEventListener('visibilitychange',visibility,true);
  document.addEventListener('freeze',suspend,true);
  document.addEventListener('resume',resume,true);
  monitor();
  window.__xsObserver = {
    read:function () { return {v:1,url:location.href,cards:scan(true)}; },
    stop:function () {
      stopped = true; unmonitor();
      removeEventListener('scroll',changed,true); removeEventListener('resize',changed,true);
      removeEventListener('popstate',changed,true); removeEventListener('hashchange',changed,true);
      removeEventListener('pagehide',suspend,true); removeEventListener('pageshow',resume,true);
      document.removeEventListener('visibilitychange',visibility,true);
      document.removeEventListener('freeze',suspend,true); document.removeEventListener('resume',resume,true);
    }
  };
})();
