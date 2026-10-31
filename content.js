// Quiet Dictation — content script.
// Runs recognition in the page, writes transcripts at the caret, and renders a
// single self-contained floating pill (shadow DOM, so page CSS cannot touch it).

(() => {
  'use strict';
  if (window.__quietDictation) return;
  window.__quietDictation = true;

  /* ------------------------------------------------------------------ */
  /* config                                                              */
  /* ------------------------------------------------------------------ */
  const LANGS = ['he-IL', 'en-US'];
  // Input types that support a caret. Password/number/date etc. are excluded on purpose.
  const FIELD_TYPES = new Set(['text', 'search', 'url', 'tel', 'email']);
  // After these, no separating space is needed.
  const TRAILING_OK = /[.!?,;:…”"’')\]}]$/;

  const state = {
    active: false,
    lang: 'en-US',
    supported: true,
    error: '',     // fatal, stops dictation
    hint: '',      // transient, cleared on the next result
    interim: '',   // live partial transcript
    buffer: '',    // text produced while no field was focused
    prov: null,    // the segment currently being written into the field
    rec: null,
    running: false,
    timer: 0,
    errStreak: 0,
  };

  const ui = { ready: false };

  /* ------------------------------------------------------------------ */
  /* helpers: editing targets                                            */
  /* ------------------------------------------------------------------ */
  function isFormField(el) {
    if (!el || el.nodeType !== 1) return false;
    const tag = el.tagName;
    if (tag === 'TEXTAREA') return !el.disabled && !el.readOnly;
    if (tag !== 'INPUT') return false;
    const type = (el.getAttribute('type') || 'text').toLowerCase();
    return FIELD_TYPES.has(type) && !el.disabled && !el.readOnly;
  }

  function isEditable(el) {
    if (!el || el.nodeType !== 1) return false;
    if (isFormField(el)) return true;
    return !!el.isContentEditable;
  }

  function currentEditable() {
    const el = document.activeElement;
    return isEditable(el) ? el : null;
  }

  function cursorPos(el) {
    try {
      const p = el.selectionStart;
      return typeof p === 'number' ? p : null;
    } catch (_) {
      return null;
    }
  }

  function nativeValueSetter(el) {
    const proto = el.tagName === 'TEXTAREA'
      ? window.HTMLTextAreaElement
      : window.HTMLInputElement;
    const desc = Object.getOwnPropertyDescriptor(proto.prototype, 'value');
    return desc && desc.set ? desc.set : null;
  }

  /* ------------------------------------------------------------------ */
  /* helpers: ranges (contenteditable)                                   */
  /* ------------------------------------------------------------------ */
  function caretIn(el) {
    const sel = window.getSelection();
    if (!sel || sel.rangeCount !== 1) return null;
    const r = sel.getRangeAt(0);
    if (!r.collapsed || !el.contains(r.startContainer)) return null;
    return r;
  }

  function selectRange(r) {
    const sel = window.getSelection();
    if (!sel) return false;
    sel.removeAllRanges();
    sel.addRange(r);
    return true;
  }

  // A live range covering exactly `n` characters starting at a boundary point.
  function forwardRange(el, startContainer, startOffset, n) {
    if (!startContainer || startContainer.nodeType !== 3) return null;
    if (!el.contains(startContainer)) return null;
    if (startOffset < 0 || startOffset > startContainer.data.length) return null;

    const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, null);
    walker.currentNode = startContainer;

    let node = startContainer;
    let off = startOffset;
    let remaining = n;

    while (node) {
      const available = node.data.length - off;
      if (available >= remaining) {
        const r = document.createRange();
        r.setStart(startContainer, startOffset);
        r.setEnd(node, off + remaining);
        return r;
      }
      remaining -= available;
      node = walker.nextNode();
      off = 0;
    }
    return null;
  }

  // Insert text at the current selection, letting the page observe it
  // (execCommand fires beforeinput/input, which editors such as Quill,
  // Slate, Gmail and Notion rely on). Falls back to a plain DOM write.
  function insertTextAt(el, text) {
    try {
      if (document.execCommand('insertText', false, text)) return true;
    } catch (_) {
      /* fall through */
    }
    try {
      const sel = window.getSelection();
      if (!sel || sel.rangeCount !== 1) return false;
      const r = sel.getRangeAt(0);
      r.deleteContents();
      const node = document.createTextNode(text);
      r.insertNode(node);
      const after = document.createRange();
      after.setStartAfter(node);
      after.collapse(true);
      sel.removeAllRanges();
      sel.addRange(after);
      el.dispatchEvent(new InputEvent('input', {
        bubbles: true, data: text, inputType: 'insertText',
      }));
      return true;
    } catch (_) {
      return false;
    }
  }

  /* ------------------------------------------------------------------ */
  /* helpers: spacing                                                    */
  /* ------------------------------------------------------------------ */
  function withTrailingSpace(text) {
    if (/\s$/.test(text) || TRAILING_OK.test(text)) return text;
    return text + ' ';
  }

  function prevChar(el) {
    try {
      if (isFormField(el)) {
        const i = cursorPos(el);
        if (!i) return '';
        return el.value.charAt(i - 1);
      }
      const r = caretIn(el);
      if (!r) return '';
      const node = r.startContainer;
      if (node.nodeType === 3 && r.startOffset > 0) return node.data.charAt(r.startOffset - 1);
    } catch (_) {
      /* ignore */
    }
    return '';
  }

  function withLeadingSpace(el, text) {
    return spaceBefore(prevChar(el), text);
  }

  function spaceBefore(prev, text) {
    if (/^\s/.test(text)) return text;
    if (!prev || /\s/.test(prev)) return text;
    if (/[({\[“"'„]/.test(prev)) return text;
    return ' ' + text;
  }

  // Rich-text editors turn plain spaces into U+00A0, so compare leniently.
  function sameText(a, b) {
    if (a === b) return true;
    return a.replace(/\u00A0/g, ' ') === b.replace(/\u00A0/g, ' ');
  }

  // The character in front of an existing segment — it never moves while the
  // segment lives, so the leading space stays consistent across writes.
  function prevCharAt(seg) {
    try {
      if (seg.kind === 'f') {
        const i = seg.start;
        return i > 0 ? seg.el.value.charAt(i - 1) : '';
      }
      const r = seg.startRange;
      const node = r.startContainer;
      if (node.nodeType === 3 && r.startOffset > 0) return node.data.charAt(r.startOffset - 1);
    } catch (_) {
      /* ignore */
    }
    return '';
  }

  /* ------------------------------------------------------------------ */
  /* writing into the focused field                                      */
  /* ------------------------------------------------------------------ */
  // Segment shapes:
  //   { kind:'f', el, start, len, text }         form field, value slice
  //   { kind:'e', el, startRange, text }         contenteditable, live range
  function beginSegment(el, text) {
    if (isFormField(el)) {
      const start = cursorPos(el);
      if (start === null) return null;
      const seg = { kind: 'f', el, start, len: 0, text: '' };
      return writeField(seg, text) ? seg : null;
    }
    const caret = caretIn(el);
    if (!caret) return null;
    const seg = { kind: 'e', el, startRange: caret.cloneRange(), text: '' };
    if (!insertTextAt(el, text)) return null;
    seg.text = text;
    return seg;
  }

  function writeField(seg, text) {
    const el = seg.el;
    const start = Math.min(seg.start, el.value.length);
    const end = Math.min(start + seg.len, el.value.length);
    const expected = el.value.slice(0, start) + text + el.value.slice(end);

    let wrote = false;
    try {
      el.setSelectionRange(start, end);
      wrote = document.execCommand('insertText', false, text) && el.value === expected;
    } catch (_) {
      wrote = false;
    }
    if (!wrote) {
      const setter = nativeValueSetter(el);
      if (setter) setter.call(el, expected);
      else el.value = expected;
      el.dispatchEvent(new InputEvent('input', {
        bubbles: true, data: text, inputType: 'insertText',
      }));
    }
    try {
      const caret = start + text.length;
      el.setSelectionRange(caret, caret);
    } catch (_) {
      /* selection unsupported */
    }
    seg.start = start;
    seg.len = text.length;
    seg.text = text;
    return true;
  }

  function writeEditable(seg, text) {
    const el = seg.el;
    const start = seg.startRange;
    const range = forwardRange(el, start.startContainer, start.startOffset, seg.text.length);
    if (!range || !sameText(range.toString(), seg.text)) return false;
    if (!selectRange(range)) return false;
    if (!insertTextAt(el, text)) return false;
    seg.text = text;
    return true;
  }

  // Is the segment still exactly where we left it, with the caret right after it?
  function segmentLive(seg) {
    if (!seg.el || !seg.el.isConnected) return false;
    if (seg.kind === 'f') {
      if (document.activeElement !== seg.el) return false;
      const pos = cursorPos(seg.el);
      if (pos === null || pos !== seg.start + seg.text.length) return false;
      return sameText(seg.el.value.slice(seg.start, seg.start + seg.text.length), seg.text);
    }
    const caret = caretIn(seg.el);
    if (!caret) return false;
    const range = forwardRange(
      seg.el, seg.startRange.startContainer, seg.startRange.startOffset, seg.text.length
    );
    if (!range || !sameText(range.toString(), seg.text)) return false;
    return caret.startContainer === range.endContainer
      && caret.startOffset === range.endOffset;
  }

  function addToBuffer(text) {
    state.buffer += text;
    // Never let an unfocused buffer grow without bound.
    if (state.buffer.length > 4000) state.buffer = state.buffer.slice(-4000);
  }

  // Route a piece of text to the caret. `isFinal` seals the current segment.
  function putText(raw, isFinal) {
    const text = isFinal ? withTrailingSpace(raw) : raw;
    if (!text) return;

    // Anything held while no field was focused goes first, so text keeps its order.
    if (state.buffer && currentEditable()) flushBuffer();

    let seg = state.prov;
    if (seg && !seg.dead && !segmentLive(seg)) {
      // The segment is no longer safely replaceable (the page rewrote it, or the
      // caret moved on). Freeze it instead of writing again: text must never
      // appear twice. Its final is dropped; the words already in the field stay.
      seg.dead = true;
    }

    if (seg && seg.dead) {
      if (isFinal) state.prov = null;
      render();
      return;
    }

    if (!seg) {
      const el = currentEditable();
      if (!el) {
        addToBuffer(text);
        render();
        return;
      }
      seg = beginSegment(el, withLeadingSpace(el, text));
      if (!seg) {
        addToBuffer(text);
        render();
        return;
      }
      state.prov = isFinal ? null : seg;
      render();
      return;
    }

    const withSpace = spaceBefore(prevCharAt(seg), text);
    if (seg.kind === 'f') writeField(seg, withSpace);
    else writeEditable(seg, withSpace);

    if (isFinal) state.prov = null;
    render();
  }

  function flushBuffer() {
    if (!state.buffer) return;
    if (!currentEditable()) return;
    const text = state.buffer;
    state.buffer = '';
    putText(text, true);
  }

  /* ------------------------------------------------------------------ */
  /* speech recognition                                                  */
  /* ------------------------------------------------------------------ */
  const SRClass = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!SRClass) state.supported = false;

  function createRecognition() {
    if (!SRClass) return null;
    const rec = new SRClass();
    rec.continuous = true;
    rec.interimResults = true;
    rec.maxAlternatives = 1;
    rec.lang = state.lang;

    rec.onresult = (event) => {
      state.hint = '';
      state.errStreak = 0;
      let interim = '';
      let final = '';
      for (let i = event.resultIndex; i < event.results.length; i++) {
        const item = event.results[i];
        if (!item.length) continue;
        const text = item[0].transcript || '';
        if (item.isFinal) final += text;
        else interim += text;
      }
      if (final) putText(final, true);
      state.interim = interim;
      if (interim) putText(interim, false);
      render();
    };

    rec.onerror = (event) => {
      const code = event.error || '';
      if (code === 'no-speech' || code === 'aborted' || code === 'bad-grammar') return;
      state.errStreak += 1;
      if (code === 'not-allowed' || code === 'service-not-allowed') {
        fail('Microphone permission denied');
      } else if (code === 'language-not-supported') {
        fail('This language is not supported by the speech service');
      } else if (code === 'network') {
        state.hint = 'Network problem — retrying…';
        render();
      } else if (code === 'audio-capture') {
        state.hint = 'No microphone found';
        render();
      } else {
        state.hint = 'Speech service error — retrying…';
        render();
      }
    };

    rec.onend = () => {
      state.running = false;
      if (!state.active) return;
      // Exponential back-off on repeated failures, snappy restart otherwise.
      const delay = state.errStreak
        ? Math.min(4000, 200 * Math.pow(2, state.errStreak))
        : 150;
      clearTimeout(state.timer);
      state.timer = setTimeout(() => {
        if (state.active) startRecognition();
      }, delay);
    };

    return rec;
  }

  function startRecognition() {
    // A frozen segment from the previous session can never be sealed — let it go.
    if (state.prov && state.prov.dead) state.prov = null;
    if (!state.rec) state.rec = createRecognition();
    if (!state.rec) return;
    state.rec.lang = state.lang;
    try {
      state.rec.start();
      state.running = true;
    } catch (_) {
      state.running = true; // already starting/running
    }
  }

  function stopRecognition() {
    clearTimeout(state.timer);
    if (state.rec && state.running) {
      try {
        state.rec.stop(); // lets in-flight final results flush first
      } catch (_) {
        /* ignore */
      }
    }
    state.running = false;
  }

  function fail(message) {
    state.active = false;
    state.error = message;
    state.interim = '';
    state.prov = null;
    stopRecognition();
    render();
    syncBadge();
  }

  /* ------------------------------------------------------------------ */
  /* start / stop / language                                             */
  /* ------------------------------------------------------------------ */
  function start() {
    if (!state.supported) {
      state.error = 'Speech recognition is not available in this browser';
      render();
      return;
    }
    state.active = true;
    state.error = '';
    state.hint = '';
    state.errStreak = 0;
    flushBuffer();
    startRecognition();
    render();
    syncBadge();
  }

  function stop() {
    state.active = false;
    state.interim = '';
    state.prov = null;
    state.errStreak = 0;
    stopRecognition();
    flushBuffer();
    render();
    syncBadge();
  }

  function toggle() {
    if (state.active) stop();
    else start();
  }

  function setLang(lang, persist) {
    if (!LANGS.includes(lang) || lang === state.lang) return;
    state.lang = lang;
    if (state.rec) state.rec.lang = lang;
    if (state.active && state.rec && state.running) {
      try {
        state.rec.stop(); // onend restarts us with the new language
      } catch (_) {
        /* ignore */
      }
    }
    if (persist !== false) {
      try {
        chrome.storage.sync.set({ lang });
      } catch (_) {
        /* ignore */
      }
    }
    render();
  }

  function cycleLang() {
    const next = state.lang === 'he-IL' ? 'en-US' : 'he-IL';
    setLang(next, true);
  }

  function syncBadge() {
    try {
      const p = chrome.runtime.sendMessage({ type: 'qd-state', active: state.active });
      if (p && typeof p.catch === 'function') p.catch(() => {});
    } catch (_) {
      /* ignore */
    }
  }

  /* ------------------------------------------------------------------ */
  /* UI                                                                  */
  /* ------------------------------------------------------------------ */
  const STYLE = `
:host {
  all: initial;
  display: block;
  position: fixed;
  top: 0;
  left: 0;
  width: 0;
  height: 0;
  z-index: 2147483647;
  pointer-events: none;
}
.qd-root {
  position: fixed;
  right: 16px;
  bottom: 16px;
  direction: ltr;
  font-family: ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
  font-size: 13px;
  color: #e6e8eb;
  -webkit-font-smoothing: antialiased;
}
.qd-pill {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px;
  background: #14171c;
  border: 1px solid rgba(255, 255, 255, 0.09);
  border-radius: 999px;
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.34);
  pointer-events: auto;
  user-select: none;
  -webkit-user-select: none;
  cursor: default;
}
.qd-btn {
  all: unset;
  box-sizing: border-box;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: inherit;
}
.qd-mic {
  width: 30px;
  height: 30px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
}
.qd-mic:hover { background: rgba(255, 255, 255, 0.16); }
.qd-mic.on { background: #c9343a; color: #ffffff; }
.qd-mic.on svg { animation: qd-blink 1.6s ease-in-out infinite; }
@keyframes qd-blink { 0%, 100% { opacity: 1; } 50% { opacity: 0.3; } }
.qd-langs {
  display: flex;
  gap: 2px;
  padding: 2px;
  background: rgba(255, 255, 255, 0.07);
  border-radius: 999px;
}
.qd-lang {
  padding: 4px 9px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: #9aa1ab;
}
.qd-lang:hover { color: #e6e8eb; }
.qd-lang.on { background: #f4f6f7; color: #14171c; }
.qd-sep {
  width: 1px;
  align-self: stretch;
  margin: 3px 0;
  background: rgba(255, 255, 255, 0.14);
}
.qd-text {
  display: none;
  max-width: min(48vw, 320px);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 12px;
  line-height: 1.4;
  color: #b9bfc7;
}
.qd-root.open .qd-text { display: block; }
.qd-text.err { color: #ff9b9b; }
`;

  const MIC_SVG = `
<svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor"
     stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
  <rect x="9" y="2" width="6" height="12" rx="3"></rect>
  <path d="M5 11a7 7 0 0 0 14 0"></path>
  <path d="M12 18v3"></path>
  <path d="M8.5 21h7"></path>
</svg>`;

  function buildUI() {
    if (ui.ready) return;
    const host = document.createElement('div');
    host.className = 'qd-dictation-host';

    const shadow = host.attachShadow({ mode: 'open' });
    shadow.innerHTML = `
      <style>${STYLE}</style>
      <div class="qd-root">
        <div class="qd-pill" title="Quiet Dictation — Ctrl+Shift+S start/stop, Ctrl+Shift+L language">
          <button class="qd-btn qd-mic" type="button" aria-pressed="false"
                  aria-label="Start or stop dictation">${MIC_SVG}</button>
          <div class="qd-langs" role="group" aria-label="Recognition language">
            <button class="qd-btn qd-lang" type="button" data-lang="en-US">EN</button>
            <button class="qd-btn qd-lang" type="button" data-lang="he-IL">HE</button>
          </div>
          <span class="qd-sep"></span>
          <span class="qd-text" dir="auto" role="status" aria-live="polite"></span>
        </div>
      </div>`;

    try {
      (document.documentElement || document.body).appendChild(host);
    } catch (_) {
      return;
    }

    ui.host = host;
    ui.root = shadow.querySelector('.qd-root');
    ui.pill = shadow.querySelector('.qd-pill');
    ui.mic = shadow.querySelector('.qd-mic');
    ui.text = shadow.querySelector('.qd-text');
    ui.langs = Array.from(shadow.querySelectorAll('.qd-lang'));
    ui.ready = true;

    // Never steal the caret from the text field the user is typing into.
    ui.pill.addEventListener('mousedown', (e) => {
      if (currentEditable()) e.preventDefault();
    });

    ui.pill.addEventListener('click', (e) => {
      const langBtn = e.target.closest && e.target.closest('.qd-lang');
      if (langBtn) {
        setLang(langBtn.dataset.lang, true);
        return;
      }
      toggle();
    });

    for (const btn of ui.langs) {
      btn.addEventListener('click', (e) => {
        e.stopPropagation();
        setLang(btn.dataset.lang, true);
      });
    }

    render();
  }

  let lastMessage = null;
  let lastOpen = null;

  function render() {
    if (!ui.ready) return;

    ui.mic.classList.toggle('on', state.active);
    ui.mic.setAttribute('aria-pressed', state.active ? 'true' : 'false');
    for (const btn of ui.langs) {
      btn.classList.toggle('on', btn.dataset.lang === state.lang);
    }

    const message =
      state.error ||
      state.hint ||
      (state.buffer ? 'Focus a text field to type…' : '') ||
      state.interim ||
      (state.active ? 'Listening…' : '');

    if (message !== lastMessage) {
      lastMessage = message;
      ui.text.textContent = message;
    }
    const open = message.length > 0;
    if (open !== lastOpen) {
      lastOpen = open;
      ui.root.classList.toggle('open', open);
    }
    ui.text.classList.toggle('err', !!(state.error || state.hint));
  }

  /* ------------------------------------------------------------------ */
  /* wiring                                                              */
  /* ------------------------------------------------------------------ */
  document.addEventListener('focusin', (e) => {
    if (state.buffer && isEditable(e.target)) flushBuffer();
  }, true);

  chrome.runtime.onMessage.addListener((msg, _sender, respond) => {
    if (!msg) return;
    if (msg.type === 'toggle-dictation') {
      toggle();
      respond({ active: state.active });
    } else if (msg.type === 'toggle-language') {
      cycleLang();
      respond({ lang: state.lang });
    }
  });

  if (chrome.storage && chrome.storage.onChanged) {
    chrome.storage.onChanged.addListener((changes, area) => {
      if (area !== 'sync' || !changes.lang) return;
      setLang(changes.lang.newValue, false);
    });
  }

  try {
    chrome.storage.sync.get({ lang: null }, (res) => {
      const saved = res && res.lang;
      if (LANGS.includes(saved)) {
        state.lang = saved;
      } else {
        let detected = 'en-US';
        try {
          const locale = (chrome.i18n && chrome.i18n.getUILanguage()) || '';
          if (/^he/i.test(locale)) detected = 'he-IL';
        } catch (_) {
          /* ignore */
        }
        state.lang = detected;
        chrome.storage.sync.set({ lang: detected });
      }
      if (state.rec) state.rec.lang = state.lang;
      render();
    });
  } catch (_) {
    /* ignore */
  }

  buildUI();
})();
