// Copies the finished pictures into a site tree and writes the size manifest the manual reads.
//   node publish.mjs <path to the frontend folder> [version]
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { WORK } from './lib.mjs';

const here = WORK;
const [, , frontend, version = new Date().toISOString().slice(0, 10)] = process.argv;
if (!frontend || !existsSync(join(frontend, 'angular.json'))) {
  throw new Error('pass the path to the frontend folder');
}
const SHIPPED = /^(ci|op)-[a-z0-9-]+\.webp$/;
const EXCLUDED = new Set(['ci-route-window-person.webp']);
const sizes = JSON.parse(readFileSync(join(here, 'out', 'sizes.json'), 'utf8'));
const names = readdirSync(join(here, 'out', 'light')).filter(f => SHIPPED.test(f) && !EXCLUDED.has(f)).sort();
let bytes = 0;
for (const theme of ['light', 'dark']) {
  const target = join(frontend, 'public', 'assets', 'guide', theme);
  rmSync(target, { recursive: true, force: true });
  mkdirSync(target, { recursive: true });
  for (const file of names) {
    const source = join(here, 'out', theme, file);
    if (!existsSync(source)) throw new Error(`missing ${theme}/${file}`);
    copyFileSync(source, join(target, file));
    bytes += statSync(source).size;
  }
}
const rows = names.map(file => {
  const name = file.replace(/\.webp$/, '');
  const size = sizes[name];
  if (!size) throw new Error('no size for ' + name);
  return `  '${name}': [${size.width}, ${size.height}]`;
});
const manifest = `/**
 * Screenshots of the manuals: name → [width, height] in CSS pixels.
 * Files: public/assets/guide/light/<name>.webp and public/assets/guide/dark/<name>.webp (twice the size, for sharp text).
 * Written by the screenshot script (infrastructure/scripts/local/guide-shots); do not edit by hand.
 */
export const GUIDE_SHOTS = {
${rows.join(',\n')}
} as const satisfies Record<string, readonly [number, number]>;

export type GuideShot = keyof typeof GUIDE_SHOTS;

/** Changes with every new set of pictures, so browsers do not show a cached old one. */
export const GUIDE_SHOTS_VERSION = '${version}';
`;
writeFileSync(join(frontend, 'src', 'app', 'features', 'admin', 'common-billing', 'guide-shots.ts'), manifest);
console.log(`${names.length} pictures x 2 themes, ${(bytes / 1024 / 1024).toFixed(2)} MB`);
