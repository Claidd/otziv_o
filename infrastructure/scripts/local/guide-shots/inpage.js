// Helpers evaluated inside the stand page. Nothing here is sent anywhere: they only read the DOM
// and replace real names in the rendered text with invented ones before a screenshot is taken.
window.__shots = (() => {
  const norm = s => (s ?? '').replace(/\s+/g, ' ').trim();

  /** Side-panel block by the exact text of its heading. */
  function block(title) {
    return [...document.querySelectorAll('.side-group, .side-history, .common-billing-settings, details, section')]
      .find(g => norm(g.querySelector(':scope > .side-group-head h4, :scope > summary h4, :scope > header h4, :scope > summary h3, :scope > header h3')?.textContent) === title) ?? null;
  }

  /** First element matching the selector whose text contains (or, with exact, whose strong label equals) the text. */
  function find(selector, text, root = document, exact = false) {
    const list = [...root.querySelectorAll(selector)];
    if (text == null) return list[0] ?? null;
    return list.find(e => exact
      ? norm(e.querySelector('strong')?.textContent ?? e.textContent) === text
      : norm(e.textContent).includes(text)) ?? null;
  }

  function resolve(spec) {
    if (!spec) return null;
    if (typeof spec === 'string') return document.querySelector(spec);
    if (spec.block) {
      const b = block(spec.block);
      if (!b) return null;
      const inner = spec.find ? find(spec.find[0], spec.find[1], b, spec.exact) : spec.head ? b.querySelector(':scope > .side-group-head, :scope > summary') : spec.sub ? b.querySelector(spec.sub) : b;
      return inner && spec.find && spec.sub ? inner.querySelector(spec.sub) : inner;
    }
    if (spec.find) {
      const root = spec.in ? document.querySelector(spec.in) : document;
      const found = root ? find(spec.find[0], spec.find[1], root, spec.exact) : null;
      return found && spec.sub ? found.querySelector(spec.sub) : found;
    }
    if (spec.all) return document.querySelectorAll(spec.all)[spec.nth ?? 0] ?? null;
    return null;
  }

  /** Replaces real names in every text node and form value. rules: [[pattern, flags, replacement], ...]. */
  function anonymize(rules) {
    const compiled = rules.map(([p, f, r]) => [new RegExp(p, f), r]);
    const apply = s => compiled.reduce((t, [re, r]) => t.replace(re, r), s);
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    let changed = 0;
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      const next = apply(node.nodeValue);
      if (next !== node.nodeValue) { node.nodeValue = next; changed++; }
    }
    for (const input of document.querySelectorAll('input, textarea')) {
      if (input.type === 'radio' || input.type === 'checkbox') continue;
      const next = apply(input.value);
      if (next !== input.value) { input.value = next; changed++; }
    }
    for (const e of document.querySelectorAll('[title]')) e.title = apply(e.title);
    return changed;
  }

  /** Names shown in a list are replaced by position, so a name missing from the rules cannot leak. */
  function rename(selector, names) {
    const seen = new Map();
    for (const e of document.querySelectorAll(selector)) {
      const real = norm(e.textContent);
      if (!seen.has(real)) seen.set(real, names[seen.size % names.length]);
      e.textContent = seen.get(real);
    }
    return [...seen.entries()];
  }

  function box(e) {
    const r = e.getBoundingClientRect();
    return { x: r.left + scrollX, y: r.top + scrollY, w: r.width, h: r.height };
  }

  /**
   * Clip rectangle and the marks' rectangles relative to it. The clip is one element, or
   * { union: [specs], frame: spec }: the vertical span of the listed elements at the frame's width.
   */
  function measure(clipSpec, marks, pad = 0) {
    let c, clipEl, text;
    if (clipSpec && clipSpec.union) {
      const parts = clipSpec.union.map(resolve);
      if (parts.some(e => !e)) return { error: 'clip part not found: ' + JSON.stringify(clipSpec.union[parts.findIndex(e => !e)]) };
      const boxes = parts.map(box);
      clipEl = clipSpec.frame ? resolve(clipSpec.frame) : parts[0];
      if (!clipEl) return { error: 'clip frame not found' };
      const f = clipSpec.frame ? box(clipEl) : null;
      const top = Math.min(...boxes.map(b => b.y)) - (clipSpec.above ?? 0);
      const bottom = Math.max(...boxes.map(b => b.y + b.h)) + (clipSpec.below ?? 0);
      const left = f ? f.x : Math.min(...boxes.map(b => b.x));
      const right = f ? f.x + f.w : Math.max(...boxes.map(b => b.x + b.w));
      c = { x: left, y: f && clipSpec.fromTop ? f.y : top, w: right - left, h: 0 };
      c.h = bottom - c.y;
      text = parts.map(e => norm(e.innerText)).join(' | ');
    } else {
      clipEl = resolve(clipSpec);
      if (!clipEl) return { error: 'clip not found: ' + JSON.stringify(clipSpec) };
      c = box(clipEl);
      text = norm(clipEl.innerText);
    }
    const clip = { x: Math.floor(c.x - pad), y: Math.floor(c.y - pad), width: Math.ceil(c.w + 2 * pad), height: Math.ceil(c.h + 2 * pad) };
    const out = [];
    for (const m of marks ?? []) {
      const e = resolve(m.target);
      if (!e) return { error: 'mark not found: ' + JSON.stringify(m.target) };
      const b = box(e);
      out.push({ ...m, target: undefined, x: b.x - clip.x, y: b.y - clip.y, w: b.w, h: b.h });
    }
    const radius = parseFloat(getComputedStyle(clipEl).borderTopLeftRadius) || 0;
    return { clip, marks: out, radius, text: text.slice(0, 6000) };
  }

  function click(spec) {
    const e = resolve(spec);
    if (!e) return false;
    e.scrollIntoView({ block: 'center' });
    e.click();
    return true;
  }

  function open(title, opened = true) {
    const b = block(title);
    if (!b) return false;
    if (b.tagName === 'DETAILS') b.open = opened;
    return true;
  }

  return { block, find, resolve, anonymize, rename, measure, click, open, norm };
})();
'ok';
