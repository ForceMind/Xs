/* Xs reversible fixture cleanup. No network, private-state reads or native bridge.
 * These narrow structural contracts are not yet validated against live X. */
(function () {
  'use strict';
  try { if (window.__xsCleanup) window.__xsCleanup.stop(); } catch (_) {}
  var MAX_TRACKED = 128, MAX_SCAN = 192, hidden = new Map(), stopped = false;
  var options = {ads:false, subscription:false, recommendations:false};
  var timer = null, observing = false, pending = false, scanning = false, capped = false;
  var protectedSelector = 'form,input,textarea,[contenteditable="true"],[role="dialog"],[aria-modal="true"],' +
    '[data-testid="activeSubscription"],[data-testid="premiumEntitlement"],[data-testid="paidFeature"]';
  var sensitiveSelector = 'iframe,input[type="password"],input[autocomplete="one-time-code"],' +
    '[data-testid="tweetTextarea_0"],[data-testid="dmComposerTextInput"],' +
    '[data-testid="LoginForm_Login_Button"],[data-testid="ocfEnterTextTextInput"],' +
    '[data-testid="securityChallenge"],[data-testid="challengeContainer"],[role="dialog"],[aria-modal="true"]';
  function enabled() { return options.ads || options.subscription || options.recommendations; }
  function allowed() {
    if (window.top !== window || document.visibilityState !== 'visible') return false;
    if (location.origin !== 'https://x.com' && location.origin !== 'https://www.x.com') return false;
    if (!/^\/(home|search|explore)\/?$/.test(location.pathname) &&
        !/^\/[A-Za-z0-9_]{1,15}\/status\/[0-9]{1,20}\/?$/.test(location.pathname)) return false;
    return !document.querySelector(sensitiveSelector);
  }
  function protectedNode(node) {
    return node.matches(protectedSelector) || !!node.querySelector(protectedSelector);
  }
  function kind(node) {
    if (!node.isConnected || protectedNode(node)) return null;
    if (node.matches('div[data-testid="placementTracking"]')) {
      if (node.children.length !== 1) return null;
      var article = node.children[0];
      if (!article.matches('article[data-testid="tweet"]') ||
          article.querySelectorAll('article').length ||
          !article.querySelector('[data-testid="promotedIndicator"]')) return null;
      return 'ads';
    }
    if (node.matches('aside[role="complementary"][data-testid="premiumPromo"]')) {
      if (node.querySelector('article') || !node.querySelector('[data-testid="premiumPromoTitle"]')) return null;
      var links = node.querySelectorAll('a[href]');
      if (links.length !== 1) return null;
      try {
        var url = new URL(links[0].getAttribute('href'), location.href);
        if (url.origin !== location.origin || url.search || url.hash ||
            url.pathname !== '/i/premium_sign_up') return null;
      } catch (_) { return null; }
      return 'subscription';
    }
    if (node.matches('aside[role="complementary"][data-testid="whoToFollow"]')) {
      if (node.querySelector('article') || !node.querySelector('[data-testid="whoToFollowHeading"]')) return null;
      var users = node.querySelectorAll('[data-testid="UserCell"]');
      if (users.length < 1 || users.length > 20) return null;
      var profileLinks = node.querySelectorAll('a[href]');
      if (!profileLinks.length || profileLinks.length > 40) return null;
      for (var i = 0; i < profileLinks.length; i++) {
        try {
          var profile = new URL(profileLinks[i].getAttribute('href'), location.href);
          if (profile.origin !== location.origin || profile.search || profile.hash ||
              !/^\/[A-Za-z0-9_]{1,15}$/.test(profile.pathname)) return null;
        } catch (_) { return null; }
      }
      return 'recommendations';
    }
    return null;
  }
  function restoreNode(node, entry) {
    // Restore only our owned display property; retain later site changes to other styles.
    if (node.style.getPropertyValue('display') === 'none' &&
        node.style.getPropertyPriority('display') === 'important') {
      if (entry.display) node.style.setProperty('display', entry.display, entry.priority);
      else node.style.removeProperty('display');
      if (!entry.hadStyle && node.getAttribute('style') === '') node.removeAttribute('style');
    }
  }
  function anchor(excluded) {
    var articles = document.querySelectorAll('article[data-testid="tweet"]');
    for (var i = 0; i < articles.length && i < MAX_SCAN; i++) {
      var node = articles[i], skip = false;
      for (var j = 0; j < excluded.length; j++) if (excluded[j].contains(node)) { skip = true; break; }
      if (skip || getComputedStyle(node).display === 'none') continue;
      var r = node.getBoundingClientRect();
      if (r.width > 0 && r.height > 0 && r.bottom > 0 && r.top < innerHeight) return {node:node, top:r.top};
    }
    return null;
  }
  function keepAnchor(saved) {
    if (!saved || !saved.node.isConnected || typeof scrollBy !== 'function') return;
    var shift = saved.node.getBoundingClientRect().top - saved.top;
    if (Number.isFinite(shift) && Math.abs(shift) > 0.5) scrollBy(0, shift);
  }
  function restoreAll() {
    var excluded = []; hidden.forEach(function (_, node) { excluded.push(node); });
    var saved = anchor(excluded);
    hidden.forEach(function (entry, node) { restoreNode(node, entry); });
    hidden.clear(); keepAnchor(saved);
  }
  function report() { return {v:1,url:location.href,hidden:hidden.size,capped:capped,active:!stopped && enabled() && allowed()}; }
  function scan() {
    if (scanning) return report();
    scanning = true;
    try {
      if (stopped || !enabled() || !allowed()) { restoreAll(); return report(); }
      var candidates = document.querySelectorAll('div[data-testid="placementTracking"],aside[data-testid="premiumPromo"],aside[data-testid="whoToFollow"]');
      var selected = new Map(), count = Math.min(candidates.length, MAX_SCAN);
      capped = candidates.length > MAX_SCAN;
      for (var i = 0; i < count; i++) {
        var node = candidates[i], feature = kind(node);
        if (feature && options[feature]) selected.set(node, feature);
      }
      var excluded = []; selected.forEach(function (_, node) { excluded.push(node); });
      hidden.forEach(function (_, node) { if (excluded.indexOf(node) < 0) excluded.push(node); });
      var saved = anchor(excluded), changed = false;
      hidden.forEach(function (entry, node) {
        if (!selected.has(node)) { restoreNode(node, entry); hidden.delete(node); changed = true; }
      });
      selected.forEach(function (feature, node) {
        var entry = hidden.get(node);
        if (!entry) {
          if (hidden.size >= MAX_TRACKED) { capped = true; return; }
          entry = {display:node.style.getPropertyValue('display'), priority:node.style.getPropertyPriority('display'), hadStyle:node.hasAttribute('style')};
          hidden.set(node, entry);
        } else if (node.style.getPropertyValue('display') !== 'none' || node.style.getPropertyPriority('display') !== 'important') {
          // The site updated its display while hidden. Preserve that new intent on restore.
          entry.display = node.style.getPropertyValue('display'); entry.priority = node.style.getPropertyPriority('display'); entry.hadStyle = node.hasAttribute('style');
        }
        if (node.style.getPropertyValue('display') !== 'none' || node.style.getPropertyPriority('display') !== 'important') {
          node.style.setProperty('display', 'none', 'important'); changed = true;
        }
      });
      if (changed) keepAnchor(saved);
      return report();
    } finally { scanning = false; }
  }
  function changed() {
    if (stopped || !enabled() || pending || document.visibilityState !== 'visible') return;
    pending = true;
    requestAnimationFrame(function () { pending = false; if (!stopped) scan(); });
  }
  var observer = new MutationObserver(changed);
  function watch() {
    if (!observing && enabled() && !stopped && document.visibilityState === 'visible') {
      observer.observe(document.documentElement, {childList:true,subtree:true,attributes:true,
        attributeFilter:['data-testid','role','href','style','class','hidden','aria-hidden','aria-modal','type','autocomplete','contenteditable']});
      observing = true;
    }
    if (timer === null && enabled() && !stopped && document.visibilityState === 'visible') timer = setInterval(scan, 250);
  }
  function unwatch() { observer.disconnect(); observing = false; if (timer !== null) clearInterval(timer); timer = null; }
  function visibility() { if (document.visibilityState !== 'visible') { unwatch(); restoreAll(); } else { watch(); scan(); } }
  function route() { scan(); }
  document.addEventListener('visibilitychange', visibility, true);
  addEventListener('popstate', route, true); addEventListener('hashchange', route, true);
  window.__xsCleanup = {
    apply:function (next) {
      var keys = next && typeof next === 'object' ? Object.keys(next).sort().join(',') : '';
      if (keys !== 'ads,recommendations,subscription' || typeof next.ads !== 'boolean' ||
          typeof next.subscription !== 'boolean' || typeof next.recommendations !== 'boolean') {
        options = {ads:false, subscription:false, recommendations:false}; unwatch(); restoreAll(); return report();
      }
      options = {ads:next.ads,subscription:next.subscription,recommendations:next.recommendations};
      if (!enabled()) unwatch(); else watch();
      return scan();
    },
    restore:function () { options = {ads:false,subscription:false,recommendations:false}; unwatch(); restoreAll(); return report(); },
    status:report,
    stop:function () { stopped = true; unwatch(); restoreAll(); document.removeEventListener('visibilitychange',visibility,true);
      removeEventListener('popstate',route,true); removeEventListener('hashchange',route,true); return report(); }
  };
})();
