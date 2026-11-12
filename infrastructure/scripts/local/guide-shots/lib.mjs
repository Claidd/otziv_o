import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { openPage, newPage, closeTarget, targets, sleep } from './cdp.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const INPAGE = readFileSync(join(here, 'inpage.js'), 'utf8');
export const STAND = process.env.OTZIV_GUIDE_SHOTS_STAND ?? 'http://localhost:8088';
/** Raw captures show real names of the stand copy: they are kept outside the repository. */
export const WORK = process.env.OTZIV_GUIDE_SHOTS_WORK ?? join(tmpdir(), 'otziv-guide-shots');

export class Studio {
  static async start({ theme = 'light', width = 1440, height = 2600 } = {}) {
    for (const stale of (await targets()).filter(t => t.type === 'page' && t.url.startsWith('file:'))) {
      await closeTarget(stale.id);
    }
    const page = await openPage();
    const composer = await newPage(pathToFileURL(join(here, 'compose.html')).href);
    const studio = new Studio(page, composer, theme, width, height);
    await page.viewport(width, height, 2);
    await composer.viewport(1500, 3200, 2);
    await page.eval(`localStorage.setItem('otziv-theme', ${JSON.stringify(theme === 'dark' ? 'dark' : 'light')})`);
    return studio;
  }

  constructor(page, composer, theme, width, height) {
    this.page = page;
    this.composer = composer;
    this.theme = theme;
    this.width = width;
    this.height = height;
    this.themes = theme === 'both' ? ['light', 'dark'] : [theme];
    this.quality = 86;
    this.rules = [];
    this.sizes = {};
  }

  async open(path, ready = `!!document.querySelector('.invoice-side')`) {
    await this.page.goto(STAND + path, 800);
    await this.page.waitFor(ready, 40000);
    await this.page.eval('document.fonts.ready.then(() => true)');
    await sleep(1500);
    await this.inject();
  }

  async inject() {
    await this.page.eval(INPAGE);
  }

  eval(expression) { return this.page.eval(expression); }
  call(fn, ...args) { return this.page.call(fn, ...args); }

  async click(spec, settle = 900) {
    const ok = await this.page.eval(`window.__shots.click(${JSON.stringify(spec)})`);
    if (!ok) throw new Error('click target not found: ' + JSON.stringify(spec));
    await sleep(settle);
  }

  async openBlock(title, opened = true) {
    const ok = await this.page.eval(`window.__shots.open(${JSON.stringify(title)}, ${opened})`);
    if (!ok) throw new Error('block not found: ' + title);
    await sleep(350);
  }

  async anonymize(extra = []) {
    return this.page.eval(`window.__shots.anonymize(${JSON.stringify([...this.rules, ...extra])})`);
  }

  /** Captures the clip in every theme and writes the framed, numbered pictures. */
  async snap(name, { clip, marks = [], pad = 0, rules = [], quiet = false } = {}) {
    let last;
    // The stand reminds of overdue invoices with a window of its own; it must not cover the picture.
    if (await this.page.eval(`(() => { const close = document.querySelector('.overdue-reminder-modal .lead-edit-close'); close?.click(); return !!close; })()`)) {
      await sleep(400);
    }
    for (const theme of this.themes) {
      await this.page.eval(`document.body.classList.toggle('otziv-dark-theme', ${theme === 'dark'})`);
      await this.anonymize(rules);
      await sleep(260);
      const m = await this.page.eval(`window.__shots.measure(${JSON.stringify(clip)}, ${JSON.stringify(marks)}, ${pad})`);
      if (m.error) throw new Error(`${name}: ${m.error}`);
      const rawDir = join(WORK, 'raw', theme);
      const outDir = join(WORK, 'out', theme);
      mkdirSync(rawDir, { recursive: true });
      mkdirSync(outDir, { recursive: true });
      const raw = join(rawDir, name + '.png');
      await this.page.shot(raw, m.clip, 'png');
      const rect = await this.composer.eval(`render(${JSON.stringify({
        src: pathToFileURL(raw).href, width: m.clip.width, height: m.clip.height, radius: m.radius, theme, marks: m.marks
      })})`);
      await sleep(150);
      await this.composer.shot(join(outDir, name + '.webp'), rect, 'webp', this.quality);
      const size = { width: rect.width, height: rect.height };
      if (this.sizes[name] && theme !== this.themes[0]
          && (this.sizes[name].width !== size.width || this.sizes[name].height !== size.height)) {
        console.log(`   ! ${name}: size differs between themes`, this.sizes[name], size);
      }
      if (theme === this.themes[0]) this.sizes[name] = size;
      writeFileSync(join(outDir, name + '.txt'), m.text + '\n');
      last = m;
    }
    await this.page.eval(`document.body.classList.toggle('otziv-dark-theme', ${this.themes[0] === 'dark'})`);
    if (!quiet) console.log(`${name}: ${this.sizes[name].width}x${this.sizes[name].height}\n   ${last.text.slice(0, 500)}`);
    return last;
  }

  async finish() {
    const file = join(WORK, 'out', 'sizes.json');
    let known = {};
    try { known = JSON.parse(readFileSync(file, 'utf8')); } catch { /* first run */ }
    writeFileSync(file, JSON.stringify({ ...known, ...this.sizes }, null, 2));
    await closeTarget(this.composer.id);
    this.composer.close();
    this.page.close();
  }
}

export { sleep };
