// Talk→To→Text (By Efroni) — content script.
// Runs recognition in the page, writes transcripts at the caret, and renders a
// single self-contained floating pill (shadow DOM, so page CSS cannot touch it).

(() => {
  'use strict';
  if (window.__talkToText) return;
  window.__talkToText = true;

  /* ------------------------------------------------------------------ */
  /* config                                                              */
  /* ------------------------------------------------------------------ */
  const LANGS = ['he-IL', 'en-US'];
  // Input types that support a caret. Password/number/date etc. are excluded on purpose.
  const FIELD_TYPES = new Set(['text', 'search', 'url', 'tel', 'email']);
  // After these, no separating space is needed.
  const TRAILING_OK = /[.!?,;:…”"’')\]}]$/;
  const HISTORY_LIMIT = 20;
  // How long a just-clicked field stays "pinned" as the dictation target even if
  // document.activeElement briefly disagrees (split layouts / frameworks that
  // reassign focus asynchronously after pointerdown).
  const PIN_TTL_MS = 400;
  // Window after a session restart during which the engine may echo the tail of
  // the previous, already-sealed utterance; used to suppress duplicate text.
  const RESTART_ECHO_MS = 3000;

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
    freshSession: false,
    lastFinalText: '',
    lastFinalAt: 0,
    pinned: null,
    pinnedAt: 0,
    history: [],   // [{ text, ts }], newest first, capped at HISTORY_LIMIT
  };

  const ui = { ready: false, drawerOpen: false };

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

  // Resolve the exact field under a point, independent of focus bookkeeping —
  // the authoritative check for "which field did the user click" in layouts
  // where multiple editable surfaces are visible at once (split panes, embedded
  // frames rendered in the same document, overlapping widgets).
  function editableAtPoint(x, y) {
    let el;
    try {
      el = document.elementFromPoint(x, y);
    } catch (_) {
      return null;
    }
    return isEditable(el) ? el : null;
  }

  function currentEditable() {
    if (state.pinned && state.pinned.isConnected && isEditable(state.pinned)) {
      if (Date.now() - state.pinnedAt < PIN_TTL_MS) return state.pinned;
      // Pin expired: still prefer it over a stale/foreign activeElement, but
      // only while it genuinely still has focus.
      if (document.activeElement === state.pinned) return state.pinned;
    }
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
  /* helpers: pointer → caret pinning                                     */
  /* ------------------------------------------------------------------ */
  function pinCaretAt(el, x, y) {
    if (isFormField(el)) return; // the browser already places the caret correctly
    if (!document.caretRangeFromPoint) return;
    try {
      const range = document.caretRangeFromPoint(x, y);
      if (!range || !el.contains(range.startContainer)) return;
      const sel = window.getSelection();
      if (!sel) return;
      sel.removeAllRanges();
      sel.addRange(range);
    } catch (_) {
      /* ignore */
    }
  }

  function handlePointerDown(e) {
    const el = editableAtPoint(e.clientX, e.clientY);
    state.pinned = el;
    state.pinnedAt = el ? Date.now() : 0;
    if (el) pinCaretAt(el, e.clientX, e.clientY);
  }

  document.addEventListener('pointerdown', handlePointerDown, true);
  document.addEventListener('mousedown', handlePointerDown, true);

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

  // Fired once a segment is sealed (isFinal), on top of whatever native
  // input/beforeinput events execCommand or the setter path already raised.
  // React's controlled inputs key off the patched value-setter + 'input'
  // combo (already covered below); Vue 2/3 and plain <form> validation also
  // listen for 'change' on commit, which neither execCommand nor a manual
  // value-set otherwise raises.
  function fireChange(el) {
    try {
      el.dispatchEvent(new Event('change', { bubbles: true }));
    } catch (_) {
      /* ignore */
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
    return a.replace(/ /g, ' ') === b.replace(/ /g, ' ');
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
  /* helpers: dedup across a restarted recognition session                */
  /* ------------------------------------------------------------------ */
  // Some engines re-emit a bit of the previous utterance's tail right after an
  // automatic reconnect (the audio buffer overlaps the restart boundary). Strip
  // any such overlap against the last phrase we already sealed into the page.
  function stripRestartEcho(text) {
    if (!state.lastFinalText) return text;
    if (Date.now() - state.lastFinalAt > RESTART_ECHO_MS) return text;

    const last = state.lastFinalText.trim();
    if (!last) return text;
    const normLast = last.toLowerCase();
    const normIncoming = text.toLowerCase();

    if (normIncoming === normLast) return '';
    if (normIncoming.startsWith(normLast)) {
      return text.slice(last.length).replace(/^\s+/, '');
    }

    // Partial word-level overlap (engine clipped the boundary slightly
    // differently): compare the last few words of what we sealed against the
    // first few words of what just came in.
    const lastWords = normLast.split(/\s+/);
    const incWords = text.split(/\s+/);
    const maxCheck = Math.min(lastWords.length, incWords.length, 6);
    for (let k = maxCheck; k > 0; k--) {
      const a = lastWords.slice(-k).join(' ');
      const b = incWords.slice(0, k).join(' ').toLowerCase();
      if (a === b) return incWords.slice(k).join(' ');
    }
    return text;
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
      if (isFinal) fireChange(el);
      render();
      return;
    }

    const withSpace = spaceBefore(prevCharAt(seg), text);
    if (seg.kind === 'f') writeField(seg, withSpace);
    else writeEditable(seg, withSpace);

    if (isFinal) {
      state.prov = null;
      fireChange(seg.el);
    }
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
  /* history (clipboard drawer)                                          */
  /* ------------------------------------------------------------------ */
  function addHistory(text) {
    const trimmed = text.trim();
    if (!trimmed) return;
    state.history.unshift({ text: trimmed, ts: Date.now() });
    if (state.history.length > HISTORY_LIMIT) state.history.length = HISTORY_LIMIT;
    renderHistory();
    try {
      chrome.storage.local.set({ history: state.history });
    } catch (_) {
      /* ignore */
    }
  }

  async function copyToClipboard(text) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch (_) {
      try {
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.opacity = '0';
        document.body.appendChild(ta);
        ta.select();
        const ok = document.execCommand('copy');
        ta.remove();
        return ok;
      } catch (_) {
        return false;
      }
    }
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

      if (state.freshSession) {
        state.freshSession = false;
        if (final) final = stripRestartEcho(final);
        else if (interim) interim = stripRestartEcho(interim);
      }

      if (final) {
        putText(final, true);
        state.lastFinalText = final.trim();
        state.lastFinalAt = Date.now();
        addHistory(final);
      }
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
    state.freshSession = true;
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
      const p = chrome.runtime.sendMessage({ type: 'ttt-state', active: state.active });
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
.ttt-root {
  position: fixed;
  right: 16px;
  bottom: 16px;
  direction: ltr;
  font-family: ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
  font-size: 13px;
  color: #e6e8eb;
  -webkit-font-smoothing: antialiased;
}
.ttt-pill {
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
  cursor: grab;
}
.ttt-pill.dragging { cursor: grabbing; }
.ttt-btn {
  all: unset;
  box-sizing: border-box;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: inherit;
}
.ttt-mic {
  width: 30px;
  height: 30px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
}
.ttt-mic:hover { background: rgba(255, 255, 255, 0.16); }
.ttt-mic.on { background: #c9343a; color: #ffffff; }
.ttt-mic.on svg { animation: ttt-blink 1.6s ease-in-out infinite; }
@keyframes ttt-blink { 0%, 100% { opacity: 1; } 50% { opacity: 0.3; } }
.ttt-langs {
  display: flex;
  gap: 2px;
  padding: 2px;
  background: rgba(255, 255, 255, 0.07);
  border-radius: 999px;
}
.ttt-lang {
  padding: 4px 9px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: #9aa1ab;
}
.ttt-lang:hover { color: #e6e8eb; }
.ttt-lang.on { background: #f4f6f7; color: #14171c; }
.ttt-sep {
  width: 1px;
  align-self: stretch;
  margin: 3px 0;
  background: rgba(255, 255, 255, 0.14);
}
.ttt-text {
  display: none;
  max-width: min(48vw, 320px);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 12px;
  line-height: 1.4;
  color: #b9bfc7;
}
.ttt-root.open .ttt-text { display: block; }
.ttt-text.err { color: #ff9b9b; }
.ttt-chevron {
  width: 22px;
  height: 22px;
  border-radius: 999px;
  color: #9aa1ab;
  flex-shrink: 0;
}
.ttt-chevron:hover { background: rgba(255, 255, 255, 0.1); color: #e6e8eb; }
.ttt-chevron svg { transition: transform 0.15s ease; }
.ttt-chevron.open svg { transform: rotate(180deg); }
.ttt-drawer {
  display: none;
  position: absolute;
  right: 0;
  bottom: calc(100% + 10px);
  width: 300px;
  max-height: 320px;
  overflow-y: auto;
  background: #14171c;
  border: 1px solid rgba(255, 255, 255, 0.09);
  border-radius: 14px;
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.34);
  pointer-events: auto;
}
.ttt-root.flip .ttt-drawer { bottom: auto; top: calc(100% + 10px); }
.ttt-drawer.open { display: block; }
.ttt-drawer-empty {
  padding: 18px 14px;
  color: #6b7280;
  font-size: 12px;
  text-align: center;
}
.ttt-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 12px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}
.ttt-item:last-child { border-bottom: none; }
.ttt-item-text {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  line-height: 1.4;
  color: #d7dade;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  dir: auto;
}
.ttt-copy {
  flex-shrink: 0;
  padding: 4px 9px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  background: rgba(255, 255, 255, 0.08);
  color: #d7dade;
}
.ttt-copy:hover { background: rgba(255, 255, 255, 0.16); }
.ttt-copy.copied { background: #1e8e5a; color: #fff; }
`;

  const MIC_SVG = `
<svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor"
     stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
  <rect x="9" y="2" width="6" height="12" rx="3"></rect>
  <path d="M5 11a7 7 0 0 0 14 0"></path>
  <path d="M12 18v3"></path>
  <path d="M8.5 21h7"></path>
</svg>`;

  const CHEVRON_SVG = `
<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
     stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
  <path d="M6 9l6 6 6-6"></path>
</svg>`;

  function buildUI() {
    if (ui.ready) return;
    const host = document.createElement('div');
    host.className = 'ttt-dictation-host';

    const shadow = host.attachShadow({ mode: 'open' });
    shadow.innerHTML = `
      <style>${STYLE}</style>
      <div class="ttt-root">
        <div class="ttt-drawer" role="region" aria-label="Recent transcriptions"></div>
        <div class="ttt-pill" title="Talk→To→Text (By Efroni) — Ctrl+Shift+S start/stop, Ctrl+Shift+L language. Drag to move.">
          <button class="ttt-btn ttt-mic" type="button" aria-pressed="false"
                  aria-label="Start or stop dictation">${MIC_SVG}</button>
          <div class="ttt-langs" role="group" aria-label="Recognition language">
            <button class="ttt-btn ttt-lang" type="button" data-lang="en-US">EN</button>
            <button class="ttt-btn ttt-lang" type="button" data-lang="he-IL">HE</button>
          </div>
          <span class="ttt-sep"></span>
          <span class="ttt-text" dir="auto" role="status" aria-live="polite"></span>
          <button class="ttt-btn ttt-chevron" type="button" aria-label="Show recent transcriptions" aria-expanded="false">${CHEVRON_SVG}</button>
        </div>
      </div>`;

    try {
      (document.documentElement || document.body).appendChild(host);
    } catch (_) {
      return;
    }

    ui.host = host;
    ui.root = shadow.querySelector('.ttt-root');
    ui.pill = shadow.querySelector('.ttt-pill');
    ui.mic = shadow.querySelector('.ttt-mic');
    ui.text = shadow.querySelector('.ttt-text');
    ui.chevron = shadow.querySelector('.ttt-chevron');
    ui.drawer = shadow.querySelector('.ttt-drawer');
    ui.langs = Array.from(shadow.querySelectorAll('.ttt-lang'));
    ui.ready = true;

    wireDrag();

    ui.pill.addEventListener('click', (e) => {
      if (ui.suppressClick) { ui.suppressClick = false; return; }
      const langBtn = e.target.closest && e.target.closest('.ttt-lang');
      if (langBtn) {
        setLang(langBtn.dataset.lang, true);
        return;
      }
      if (e.target.closest && e.target.closest('.ttt-chevron')) {
        toggleDrawer();
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

    ui.drawer.addEventListener('click', (e) => {
      const btn = e.target.closest && e.target.closest('.ttt-copy');
      if (!btn) return;
      e.stopPropagation();
      const idx = Number(btn.dataset.idx);
      const item = state.history[idx];
      if (!item) return;
      copyToClipboard(item.text).then((ok) => {
        if (!ok) return;
        btn.textContent = 'Copied';
        btn.classList.add('copied');
        setTimeout(() => {
          btn.textContent = 'Copy';
          btn.classList.remove('copied');
        }, 1200);
      });
    });

    restorePosition();
    renderHistory();
    render();
  }

  /* ------------------------------------------------------------------ */
  /* drag to reposition, persisted across reloads                        */
  /* ------------------------------------------------------------------ */
  function clamp(v, min, max) { return Math.max(min, Math.min(v, max)); }

  function applyPosition(left, top) {
    ui.root.style.right = 'auto';
    ui.root.style.bottom = 'auto';
    ui.root.style.left = `${left}px`;
    ui.root.style.top = `${top}px`;
    const flip = top < window.innerHeight * 0.4;
    ui.root.classList.toggle('flip', flip);
  }

  function savePosition(left, top) {
    try {
      chrome.storage.local.set({ pillPos: { left, top } });
    } catch (_) {
      /* ignore */
    }
  }

  function restorePosition() {
    try {
      chrome.storage.local.get({ pillPos: null, history: [] }, (res) => {
        if (res && res.pillPos && Number.isFinite(res.pillPos.left) && Number.isFinite(res.pillPos.top)) {
          applyPosition(res.pillPos.left, res.pillPos.top);
        }
        if (res && Array.isArray(res.history)) {
          state.history = res.history.slice(0, HISTORY_LIMIT);
          renderHistory();
        }
      });
    } catch (_) {
      /* ignore */
    }
  }

  function wireDrag() {
    let drag = null;

    ui.pill.addEventListener('mousedown', (e) => {
      if (e.button !== 0) return;
      if (e.target.closest && e.target.closest('.ttt-btn')) return;
      if (currentEditable()) e.preventDefault(); // never steal the caret
      const rect = ui.root.getBoundingClientRect();
      drag = {
        startX: e.clientX,
        startY: e.clientY,
        startLeft: rect.left,
        startTop: rect.top,
        moved: false,
      };
    });

    window.addEventListener('mousemove', (e) => {
      if (!drag) return;
      const dx = e.clientX - drag.startX;
      const dy = e.clientY - drag.startY;
      if (!drag.moved && (Math.abs(dx) > 3 || Math.abs(dy) > 3)) {
        drag.moved = true;
        ui.pill.classList.add('dragging');
      }
      if (!drag.moved) return;
      const rect = ui.root.getBoundingClientRect();
      const maxLeft = Math.max(0, window.innerWidth - rect.width - 4);
      const maxTop = Math.max(0, window.innerHeight - rect.height - 4);
      applyPosition(
        clamp(drag.startLeft + dx, 4, maxLeft),
        clamp(drag.startTop + dy, 4, maxTop)
      );
    });

    window.addEventListener('mouseup', () => {
      if (!drag) return;
      ui.pill.classList.remove('dragging');
      if (drag.moved) {
        ui.suppressClick = true;
        const rect = ui.root.getBoundingClientRect();
        savePosition(rect.left, rect.top);
      }
      drag = null;
    });
  }

  /* ------------------------------------------------------------------ */
  /* history drawer rendering                                            */
  /* ------------------------------------------------------------------ */
  function escapeHtml(s) {
    return s.replace(/[&<>"']/g, (c) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
    }[c]));
  }

  function renderHistory() {
    if (!ui.ready) return;
    if (!state.history.length) {
      ui.drawer.innerHTML = '<div class="ttt-drawer-empty">No transcriptions yet</div>';
      return;
    }
    ui.drawer.innerHTML = state.history.map((item, idx) => `
      <div class="ttt-item">
        <span class="ttt-item-text" dir="auto">${escapeHtml(item.text)}</span>
        <button type="button" class="ttt-btn ttt-copy" data-idx="${idx}">Copy</button>
      </div>`).join('');
  }

  function toggleDrawer() {
    ui.drawerOpen = !ui.drawerOpen;
    ui.drawer.classList.toggle('open', ui.drawerOpen);
    ui.chevron.classList.toggle('open', ui.drawerOpen);
    ui.chevron.setAttribute('aria-expanded', ui.drawerOpen ? 'true' : 'false');
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
      if (area === 'sync' && changes.lang) setLang(changes.lang.newValue, false);
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
