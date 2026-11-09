/*
 * Relay core — injected into AI websites (ChatGPT, Claude, Notion AI, Hark Pro, any custom site).
 * Uses the user's own logged-in session: types the prompt into the site's chat box, presses "send",
 * waits for the reply and reads it back. No private APIs, no keys, no traffic interception.
 *
 * Events go to window.RelayNative.postMessage(JSON) (Android) or window.__relayEvents (tests).
 */
(function () {
  'use strict';
  var VERSION = '1.0.0';
  if (window.__relay && window.__relay.version === VERSION) return;

  var cfg = { selectors: {}, submit: 'auto', stableMs: 2200, timeoutMs: 240000 };
  var watchers = {};
  var picker = null;

  // ---------- utils ----------

  function post(evt) {
    try {
      var s = JSON.stringify(evt);
      if (window.RelayNative && window.RelayNative.postMessage) window.RelayNative.postMessage(s);
      else (window.__relayEvents = window.__relayEvents || []).push(evt);
    } catch (e) { /* ignore */ }
  }

  function log(msg) { post({ type: 'log', message: String(msg) }); }

  function qsa(sel, root) {
    if (!sel) return [];
    try { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); } catch (e) { return []; }
  }

  function visible(el) {
    if (!el || !el.getBoundingClientRect) return false;
    var r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) return false;
    var cs = getComputedStyle(el);
    return cs.visibility !== 'hidden' && cs.display !== 'none' && parseFloat(cs.opacity || '1') > 0.05;
  }

  function norm(s) { return (s || '').replace(/\s+/g, ' ').trim(); }

  function outermost(list) {
    return list.filter(function (e) {
      return !list.some(function (o) { return o !== e && o.contains(e); });
    });
  }

  var cssEsc = (window.CSS && CSS.escape) ? CSS.escape : function (s) { return String(s).replace(/[^\w-]/g, '\\$&'); };

  // ---------- input ----------

  var EDITABLE = 'textarea, [contenteditable="true"], [contenteditable=""], [contenteditable="plaintext-only"], input[type="text"], input[type="search"], input:not([type])';
  var INPUT_HINT = /message|ask|prompt|chat|type|write|сообщ|спрос|запрос|вопрос|чат|напиш|введ/i;

  function isEditable(el) {
    if (!el) return false;
    var t = el.tagName;
    if (t === 'TEXTAREA') return !el.readOnly && !el.disabled;
    if (t === 'INPUT') return /^(text|search|)$/i.test(el.getAttribute('type') || '') && !el.readOnly && !el.disabled;
    return !!el.isContentEditable;
  }

  function resolveEditable(el) {
    if (!el) return null;
    if (isEditable(el) && (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT' || el.hasAttribute('contenteditable'))) return el;
    var inner = el.querySelector && el.querySelector(EDITABLE);
    if (inner) return inner;
    var host = el.closest && el.closest('[contenteditable="true"], [contenteditable=""], [contenteditable="plaintext-only"]');
    return host || el;
  }

  function findInput() {
    var s = cfg.selectors.input;
    if (s) {
      var l = qsa(s).filter(visible);
      if (l.length) return resolveEditable(l[l.length - 1]);
    }
    var best = null, bestScore = -1e9;
    qsa(EDITABLE).forEach(function (e) {
      if (!visible(e) || !isEditable(e)) return;
      if (e.closest('[data-relay-ui]')) return;
      var r = e.getBoundingClientRect();
      var label = (e.getAttribute('placeholder') || '') + ' ' + (e.getAttribute('aria-label') || '') + ' ' + (e.getAttribute('data-placeholder') || '') + ' ' + (e.id || '');
      var score = 0;
      if (e.tagName !== 'INPUT') score += 50;
      if (INPUT_HINT.test(label)) score += 40;
      if (e.closest('nav, aside, header, [role="navigation"], [role="search"]')) score -= 80;
      if (/search|поиск/i.test(label)) score -= 40;
      score += (r.bottom / Math.max(1, innerHeight)) * 60;
      score += (r.width / Math.max(1, innerWidth)) * 30;
      if (score > bestScore) { bestScore = score; best = e; }
    });
    return best;
  }

  function inputText(el) {
    if (!el) return '';
    if (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') return el.value || '';
    return el.innerText || el.textContent || '';
  }

  function selectContents(el) {
    try {
      var range = document.createRange();
      range.selectNodeContents(el);
      var sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(range);
    } catch (e) { /* ignore */ }
  }

  function contains(el, text) {
    var want = norm(text).slice(0, 30);
    return norm(inputText(el)).indexOf(want) !== -1;
  }

  function setInput(el, text) {
    el.focus();
    if (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') {
      var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
      setter.call(el, text);
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
      return true;
    }
    // contenteditable (ProseMirror, Lexical, Quill, Slate…): behave like typing so long text stays inline.
    selectContents(el);
    try { document.execCommand('insertText', false, text); } catch (e) { /* ignore */ }
    if (contains(el, text)) return true;
    try {
      selectContents(el);
      var dt = new DataTransfer();
      dt.setData('text/plain', text);
      el.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }));
    } catch (e) { /* ignore */ }
    if (contains(el, text)) return true;
    el.textContent = text;
    el.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: text }));
    return contains(el, text);
  }

  // ---------- send ----------

  var SEND_RX = /send|submit|отправ|ask|спрос/i;
  var NOT_SEND_RX = /stop|voice|mic|dictat|record|attach|upload|file|plus|add|model|search|tool|menu|more|голос|прикреп|диктов|меню|остановить/i;

  function findSend(input) {
    var s = cfg.selectors.send;
    if (s) {
      var l = qsa(s).filter(visible);
      if (l.length) return l[l.length - 1];
    }
    if (!input) return null;
    var ir = input.getBoundingClientRect();
    var node = input, best = null, bestScore = 0;
    for (var depth = 0; node && depth < 7; depth++, node = node.parentElement) {
      qsa('button, [role="button"], input[type="submit"]', node).forEach(function (b) {
        if (!visible(b) || b.disabled || b.getAttribute('aria-disabled') === 'true') return;
        var label = [b.getAttribute('aria-label'), b.getAttribute('data-testid'), b.getAttribute('title'), b.id, b.className && String(b.className), b.innerText, b.getAttribute('type')].join(' ');
        var sc = 0;
        if (SEND_RX.test(label)) sc += 10;
        if (NOT_SEND_RX.test(label)) sc -= 9;
        if (b.getAttribute('type') === 'submit') sc += 4;
        var r = b.getBoundingClientRect();
        if (r.left >= ir.left + ir.width * 0.5) sc += 2;
        if (r.top >= ir.top - 80) sc += 1;
        if (b.querySelector('svg')) sc += 1;
        if (sc > bestScore) { bestScore = sc; best = b; }
      });
      if (bestScore >= 10) break;
    }
    return bestScore >= 4 ? best : null;
  }

  function fire(el) {
    var opts = { bubbles: true, cancelable: true, view: window };
    ['pointerdown', 'mousedown', 'pointerup', 'mouseup'].forEach(function (t) {
      var ev = (t.indexOf('pointer') === 0 && typeof PointerEvent === 'function') ? new PointerEvent(t, opts) : new MouseEvent(t, opts);
      el.dispatchEvent(ev);
    });
    el.click();
  }

  function pressEnter(el) {
    ['keydown', 'keypress', 'keyup'].forEach(function (t) {
      el.dispatchEvent(new KeyboardEvent(t, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true }));
    });
  }

  function submit(input) {
    var btn = cfg.submit === 'enter' ? null : findSend(input);
    if (btn) { fire(btn); return 'button'; }
    if (cfg.submit === 'button') return 'none';
    pressEnter(input);
    return 'enter';
  }

  // ---------- reading the reply ----------

  function responseNodes(sel) {
    return outermost(qsa(sel || cfg.selectors.response)).filter(function (n) { return !n.closest('[data-relay-ui]'); });
  }

  function nodeText(el) { return ((el && (el.innerText || el.textContent)) || '').trim(); }

  function codeOf(el) {
    return qsa('pre', el).map(function (p) {
      var c = p.querySelector('code') || p;
      return c.textContent || '';
    }).filter(Boolean);
  }

  function isBusy() {
    if (cfg.selectors.busy && qsa(cfg.selectors.busy).some(visible)) return true;
    return qsa('button[aria-label], [role="button"][aria-label], button[data-testid]').some(function (b) {
      if (!visible(b)) return false;
      var l = (b.getAttribute('aria-label') || '') + ' ' + (b.getAttribute('data-testid') || '');
      return /(^|[\s-])(stop|остановить)/i.test(l) && !/record|dictat|voice/i.test(l);
    });
  }

  function pageText() {
    var r = document.querySelector('main') || document.body;
    return r ? (r.innerText || '') : '';
  }

  function diffMiddle(a, b) {
    var max = Math.min(a.length, b.length), p = 0, s = 0;
    while (p < max && a.charCodeAt(p) === b.charCodeAt(p)) p++;
    while (s < max - p && a.charCodeAt(a.length - 1 - s) === b.charCodeAt(b.length - 1 - s)) s++;
    if (p > 0) p = b.lastIndexOf('\n', p - 1) + 1;
    return b.slice(p, b.length - s);
  }

  function promptTail(prompt) {
    var lines = String(prompt).split('\n').map(function (x) { return x.trim(); }).filter(Boolean);
    return (lines[lines.length - 1] || '').slice(-60);
  }

  function stripEcho(delta, prompt) {
    var tail = promptTail(prompt);
    var short = tail.slice(-25);
    var i = tail ? delta.lastIndexOf(tail) : -1;
    if (i >= 0) delta = delta.slice(i + tail.length);
    else if (short && (i = delta.lastIndexOf(short)) >= 0) delta = delta.slice(i + short.length);
    return delta.trim();
  }

  function isEcho(text, prompt) {
    var t = norm(text);
    return !t || norm(prompt).indexOf(t) !== -1;
  }

  function candidate(w) {
    var nodes = responseNodes();
    if (!nodes.length) return null;
    var last = nodes[nodes.length - 1];
    if (nodes.length > w.beforeCount) return last;
    if (nodeText(last) !== w.beforeLastText) return last;
    return null;
  }

  function watch(id, prompt, opts) {
    opts = opts || {};
    if (opts.afterReload && watchers[id]) return true;
    stopWatch(id);
    var nodes = cfg.selectors.response ? responseNodes() : [];
    var w = { id: id, prompt: String(prompt || ''), t0: Date.now(), last: '', code: [], lastChange: Date.now(), lastPartial: 0, armed: false, armedAt: 0 };
    if (opts.afterReload) {
      // Our prompt is already on the page: the reply is the last message after it.
      var inPage = pageText().indexOf(promptTail(w.prompt)) >= 0;
      w.beforeCount = inPage ? Math.max(0, nodes.length - 1) : nodes.length;
      w.beforeLastText = inPage ? '\u0000' : (nodes.length ? nodeText(nodes[nodes.length - 1]) : '');
      w.baseText = '';
      w.armed = true;
      w.armedAt = Date.now();
    } else {
      w.beforeCount = nodes.length;
      w.beforeLastText = nodes.length ? nodeText(nodes[nodes.length - 1]) : '';
      w.baseText = pageText();
    }
    watchers[id] = w;
    w.timer = setInterval(function () { tick(w); }, 400);
    return true;
  }

  function tick(w) {
    if (!w.armed || w.done) return;
    var now = Date.now(), text = '', code = [], viaSel = false;
    if (cfg.selectors.response) {
      var c = candidate(w);
      if (c) { text = nodeText(c); code = codeOf(c); viaSel = true; }
    }
    // Generic mode (no selectors, or stale ones): diff the page text against the snapshot taken before sending.
    if (!viaSel && (!cfg.selectors.response || now - w.armedAt > 8000)) {
      text = stripEcho(diffMiddle(w.baseText, pageText()), w.prompt);
      if (isEcho(text, w.prompt)) text = '';
    }
    if (text !== w.last) {
      w.last = text;
      w.code = code;
      w.lastChange = now;
      if (text && now - w.lastPartial > 500) {
        w.lastPartial = now;
        post({ type: 'partial', id: w.id, text: text });
      }
    } else if (code.length) {
      w.code = code;
    }
    var need = viaSel ? cfg.stableMs : cfg.stableMs * 1.6;
    if (text && now - w.lastChange >= need && now - w.armedAt > 1200 && !isBusy()) return finish(w, false);
    if (now - w.t0 > cfg.timeoutMs) return finish(w, true);
  }

  function finish(w, timedOut) {
    stopWatch(w.id);
    w.done = true;
    post({ type: 'response', id: w.id, text: w.last, code: w.code || [], timeout: !!timedOut, url: location.href });
  }

  function stopWatch(id) {
    var w = watchers[id];
    if (w) { clearInterval(w.timer); delete watchers[id]; }
  }

  function send(text, id) {
    try {
      var input = findInput();
      if (!input) {
        post({ type: 'error', id: id, message: 'На сайте не найдено поле ввода. Откройте сайт (🌐) и войдите в аккаунт или настройте селекторы.' });
        return false;
      }
      watch(id, text);
      var w = watchers[id];
      if (!setInput(input, text)) log('text may not have been inserted');
      setTimeout(function () {
        if (!watchers[id]) return;
        submit(input);
        w.armed = true;
        w.armedAt = Date.now();
        // If the box still holds our prompt, the first attempt did not go through: try once more.
        setTimeout(function () {
          if (!watchers[id]) return;
          var cur = findInput();
          var stuck = cur && norm(text) && norm(inputText(cur)).slice(0, 30) === norm(text).slice(0, 30);
          if (stuck && !isBusy() && !candidate(w)) {
            log('retry submit');
            var b = findSend(cur);
            if (b) fire(b);
            else {
              pressEnter(cur);
              var f = cur.closest('form');
              if (f && f.requestSubmit) { try { f.requestSubmit(); } catch (e) { /* ignore */ } }
            }
          }
        }, 1500);
      }, 350);
      return true;
    } catch (e) {
      post({ type: 'error', id: id, message: 'Relay: ' + ((e && e.message) || e) });
      return false;
    }
  }

  function cancel(id, stopGeneration) {
    stopWatch(id);
    if (stopGeneration && isBusy()) {
      var b = (cfg.selectors.busy && qsa(cfg.selectors.busy).filter(visible)[0]) ||
        qsa('button[aria-label], [role="button"][aria-label]').filter(function (x) { return visible(x) && /(^|[\s-])(stop|остановить)/i.test(x.getAttribute('aria-label')); })[0];
      if (b) fire(b);
    }
    return true;
  }

  // ---------- chats & projects ----------

  function titleOf(el) {
    var first = (el.innerText || '').split('\n').map(function (s) { return s.trim(); }).filter(Boolean)[0];
    return (first || el.getAttribute('aria-label') || el.getAttribute('title') || '').slice(0, 90);
  }

  function shortHref(href) {
    try { var u = new URL(href); return decodeURIComponent(u.pathname.split('/').filter(Boolean).pop() || u.host).slice(0, 40); } catch (e) { return href; }
  }

  function linkItems(sel, kind) {
    return qsa(sel).map(function (el) {
      var a = el.closest('a[href]') || (el.querySelector && el.querySelector('a[href]')) || el;
      return { title: titleOf(el), href: a.href || '', kind: kind };
    }).filter(function (i) { return /^https?:/.test(i.href); });
  }

  function guessChats() {
    var groups = {};
    qsa('nav a[href], aside a[href], [role="navigation"] a[href]').forEach(function (a) {
      var u;
      try { u = new URL(a.href); } catch (e) { return; }
      if (u.origin !== location.origin) return;
      var segs = u.pathname.split('/').filter(Boolean);
      if (!segs.length) return;
      var last = segs[segs.length - 1];
      if (!/[a-z0-9_-]{8,}/i.test(last)) return;
      var key = segs.slice(0, -1).join('/');
      (groups[key] = groups[key] || []).push(a);
    });
    var best = Object.keys(groups).sort(function (x, y) { return groups[y].length - groups[x].length; })[0];
    return best === undefined ? [] : groups[best].map(function (a) { return { title: titleOf(a), href: a.href, kind: 'chat', guess: true }; });
  }

  function listChats() {
    var out = [], seen = {};
    function add(items) {
      items.forEach(function (i) {
        if (seen[i.href]) return;
        seen[i.href] = 1;
        if (!i.title) i.title = shortHref(i.href);
        out.push(i);
      });
    }
    if (cfg.selectors.projectLink) add(linkItems(cfg.selectors.projectLink, 'project'));
    if (cfg.selectors.chatLink) add(linkItems(cfg.selectors.chatLink, 'chat'));
    else add(guessChats());
    return out.slice(0, 300);
  }

  // ---------- picker: "tap the element" setup for any site ----------

  var SKIP_ATTR = /^data-(relay|state|headlessui|radix|orientation|side|align|v-|reactroot|start|end|index|key)/;

  function stableClasses(el) {
    return Array.prototype.filter.call(el.classList || [], function (c) {
      return /^[a-zA-Z][\w-]{1,40}$/.test(c) && !/\d{3,}/.test(c) && !/^(css|sc|jsx|emotion|svelte|tw)-/.test(c) &&
        !/^(hover|focus|active|group|peer|dark|sm|md|lg|xl)\b/.test(c) && c.indexOf(':') < 0;
    });
  }

  function attrCandidates(el) {
    var tag = el.tagName.toLowerCase(), out = [];
    if (el.id && !/\d{3,}|:/.test(el.id)) out.push('#' + cssEsc(el.id));
    ['data-testid', 'data-test-id', 'data-qa', 'data-message-author-role', 'aria-label', 'name', 'placeholder', 'role', 'type'].forEach(function (a) {
      var v = el.getAttribute(a);
      if (v && v.length <= 60 && !/["\\\n]/.test(v)) out.push(tag + '[' + a + '="' + v + '"]');
    });
    var cls = stableClasses(el);
    if (cls.length) out.push(tag + '.' + cls.slice(0, 3).map(cssEsc).join('.'));
    return out;
  }

  function uniqueSelector(el) {
    var c = attrCandidates(el);
    for (var i = 0; i < c.length; i++) if (qsa(c[i]).length === 1) return c[i];
    var parts = [], node = el;
    while (node && node.nodeType === 1 && node !== document.body && parts.length < 7) {
      var cand = attrCandidates(node);
      for (var j = 0; j < cand.length; j++) {
        var sel = [cand[j]].concat(parts).join(' > ');
        if (qsa(sel).length === 1) return sel;
      }
      var idx = 1, sib = node;
      while ((sib = sib.previousElementSibling)) if (sib.tagName === node.tagName) idx++;
      parts.unshift(node.tagName.toLowerCase() + ':nth-of-type(' + idx + ')');
      node = node.parentElement;
    }
    return parts.join(' > ');
  }

  function buttonSelector(b) {
    var c = attrCandidates(b);
    for (var i = 0; i < c.length; i++) {
      var m = qsa(c[i]);
      if (m.length >= 1 && m.length <= 3 && m.indexOf(b) >= 0) return c[i];
    }
    return uniqueSelector(b);
  }

  function hrefSelector(a) {
    var raw = a.getAttribute('href') || '', u;
    try { u = new URL(a.href, location.href); } catch (e) { return uniqueSelector(a); }
    var segs = u.pathname.split('/').filter(Boolean), prefix;
    if (segs.length >= 2) prefix = '/' + segs.slice(0, -1).join('/') + '/';
    else if (segs.length === 1 && u.search) prefix = '/' + segs[0] + '?';
    else return uniqueSelector(a);
    return 'a[href' + (/^https?:/.test(raw) ? '*=' : '^=') + '"' + prefix + '"]';
  }

  /** Selector for "all messages like this one": prefers data-* attributes shared by sibling messages. */
  function generalSelector(el) {
    var best = null, bestScore = -1e9, node = el, depth = 0;
    while (node && node.nodeType === 1 && node !== document.body && node !== document.documentElement && depth < 12) {
      var tag = node.tagName.toLowerCase(), cands = [];
      for (var i = 0; i < node.attributes.length; i++) {
        var at = node.attributes[i];
        if (at.name.indexOf('data-') === 0 && !SKIP_ATTR.test(at.name) && !/^\d+$/.test(at.value)) {
          if (at.value && at.value.length <= 40 && !/\d{4,}|["\\\n]/.test(at.value)) cands.push({ s: tag + '[' + at.name + '="' + at.value + '"]', w: 30 });
          cands.push({ s: tag + '[' + at.name + ']', w: 22 });
        }
        if (at.name === 'role' && !/^(presentation|none|button|img|link|generic|textbox)$/.test(at.value)) cands.push({ s: tag + '[role="' + at.value + '"]', w: 12 });
      }
      var cls = stableClasses(node);
      if (cls.length) cands.push({ s: tag + '.' + cls.slice(0, 2).map(cssEsc).join('.'), w: 10 });
      cands.forEach(function (c) {
        var m = qsa(c.s);
        if (!m.length || m.length > 400 || m.indexOf(node) < 0) return;
        var score = c.w + (m.length >= 2 ? 8 : 0) - depth * 1.5 - (m.length > 60 ? 10 : 0);
        if (score > bestScore) { bestScore = score; best = c.s; }
      });
      node = node.parentElement;
      depth++;
    }
    return best || uniqueSelector(el);
  }

  function selectorFor(el, kind) {
    if (kind === 'input') return uniqueSelector(resolveEditable(el.closest(EDITABLE) || el));
    if (kind === 'send' || kind === 'busy') return buttonSelector(el.closest('button, [role="button"], input[type="submit"]') || el);
    if (kind === 'chatLink' || kind === 'projectLink') {
      var a = el.closest('a[href]');
      if (a) return hrefSelector(a);
    }
    return generalSelector(el);
  }

  function pick(kind) {
    unpick();
    var prefilled = null;
    if (kind === 'send') {
      // Many sites only show the send button once there is text in the box.
      var inp = findInput();
      if (inp && !norm(inputText(inp))) { setInput(inp, 'Relay'); prefilled = inp; }
    }
    var box = document.createElement('div');
    box.setAttribute('data-relay-ui', '');
    box.style.cssText = 'position:fixed;z-index:2147483647;pointer-events:none;border:2px solid #8b5cf6;' +
      'background:rgba(139,92,246,.16);border-radius:8px;box-shadow:0 0 0 4000px rgba(10,11,16,.22);' +
      'transition:all .08s ease;left:-10px;top:-10px;width:0;height:0';
    document.documentElement.appendChild(box);
    var cur = null;
    function at(x, y) {
      var el = document.elementFromPoint(x, y);
      return el && !el.closest('[data-relay-ui]') ? el : null;
    }
    function hl(el) {
      if (!el) return;
      cur = el;
      var r = el.getBoundingClientRect();
      box.style.left = (r.left - 2) + 'px';
      box.style.top = (r.top - 2) + 'px';
      box.style.width = (r.width + 4) + 'px';
      box.style.height = (r.height + 4) + 'px';
    }
    function onMove(e) {
      var p = e.touches ? e.touches[0] : e;
      if (p) hl(at(p.clientX, p.clientY));
    }
    function block(e) { e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation(); }
    function onClick(e) {
      block(e);
      var el = at(e.clientX, e.clientY) || cur;
      if (!el) return;
      var sel = selectorFor(el, kind);
      var count = qsa(sel).length;
      var sample = norm(el.innerText || el.getAttribute('aria-label') || el.getAttribute('placeholder') || '').slice(0, 80);
      unpick();
      post({ type: 'picked', kind: kind, selector: sel, count: count, sample: sample });
    }
    var handlers = [['pointermove', onMove], ['mousemove', onMove], ['touchstart', onMove], ['pointerdown', block],
      ['mousedown', block], ['pointerup', block], ['mouseup', block], ['click', onClick]];
    handlers.forEach(function (h) { window.addEventListener(h[0], h[1], { capture: true, passive: false }); });
    picker = { box: box, handlers: handlers, prefilled: prefilled };
    return true;
  }

  function unpick() {
    if (!picker) return false;
    picker.handlers.forEach(function (h) { window.removeEventListener(h[0], h[1], { capture: true }); });
    picker.box.remove();
    if (picker.prefilled) setInput(picker.prefilled, '');
    picker = null;
    return true;
  }

  // ---------- status ----------

  function configure(c) {
    c = c || {};
    var sel = {};
    var src = c.selectors || {};
    Object.keys(src).forEach(function (k) { if (src[k] && String(src[k]).trim()) sel[k] = String(src[k]).trim(); });
    cfg.selectors = sel;
    cfg.submit = c.submit || 'auto';
    cfg.stableMs = c.stableMs || 2200;
    cfg.timeoutMs = c.timeoutMs || 240000;
    return true;
  }

  function status() {
    return {
      url: location.href,
      title: document.title,
      readyState: document.readyState,
      input: !!findInput(),
      password: !!document.querySelector('input[type="password"]'),
      busy: isBusy()
    };
  }

  function diagnose(selectors) {
    var saved = cfg.selectors;
    if (selectors) configure({ selectors: selectors, submit: cfg.submit, stableMs: cfg.stableMs, timeoutMs: cfg.timeoutMs });
    try {
      var input = findInput();
      var counts = {};
      Object.keys(cfg.selectors).forEach(function (k) { counts[k] = qsa(cfg.selectors[k]).length; });
      var nodes = cfg.selectors.response ? responseNodes() : [];
      return {
        url: location.href,
        input: !!input,
        inputVia: input ? (cfg.selectors.input && qsa(cfg.selectors.input).length ? 'selector' : 'auto') : null,
        send: !!findSend(input),
        counts: counts,
        chats: listChats().length,
        lastResponse: nodes.length ? nodeText(nodes[nodes.length - 1]).slice(0, 160) : ''
      };
    } finally {
      cfg.selectors = saved;
    }
  }

  window.__relay = {
    version: VERSION,
    configure: configure,
    status: status,
    diagnose: diagnose,
    listChats: listChats,
    send: send,
    watch: watch,
    cancel: cancel,
    pick: pick,
    unpick: unpick,
    _: { findInput: findInput, findSend: findSend, selectorFor: selectorFor, diffMiddle: diffMiddle, stripEcho: stripEcho, isBusy: isBusy }
  };
})();
