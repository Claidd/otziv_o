/**
 * Screenshots of the manuals: name → [width, height] in CSS pixels.
 * Files: public/assets/guide/light/<name>.webp and public/assets/guide/dark/<name>.webp (twice the size, for sharp text).
 * Written by the screenshot script (infrastructure/scripts/local/guide-shots); do not edit by hand.
 */
export const GUIDE_SHOTS = {
  'ci-danger': [356, 383],
  'ci-orders-return': [450, 557],
  'ci-panel-folded': [476, 529],
  'ci-panel-top': [476, 1006],
  'ci-paper-flow': [378, 889],
  'ci-paper-pay': [378, 200],
  'ci-paper-start': [450, 360],
  'ci-pay-button': [378, 242],
  'ci-pay-window': [756, 954],
  'ci-return-confirm': [788, 383],
  'ci-return-window': [716, 257],
  'ci-route-bank': [378, 495],
  'ci-route-block': [378, 495],
  'ci-route-result': [404, 699],
  'ci-route-window': [724, 873],
  'ci-send': [450, 332],
  'ci-settings-companies': [449, 534],
  'ci-term': [450, 534],
  'op-bank-button': [830, 253],
  'op-block': [830, 151],
  'op-card-map': [508, 440],
  'op-card': [436, 440],
  'op-context': [902, 253],
  'op-invoice-copy': [364, 440],
  'op-paid-card': [364, 440],
  'op-paid-window': [756, 1069],
  'op-paper-button': [830, 151],
  'op-paper-window': [760, 1035],
  'op-pick-window': [724, 873],
  'op-unpaid-card': [364, 440]
} as const satisfies Record<string, readonly [number, number]>;

export type GuideShot = keyof typeof GUIDE_SHOTS;

/** Changes with every new set of pictures, so browsers do not show a cached old one. */
export const GUIDE_SHOTS_VERSION = '20261111-3';
