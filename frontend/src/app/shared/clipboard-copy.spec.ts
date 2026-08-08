// @vitest-environment jsdom

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { copyDeferredTextToClipboard } from './clipboard-copy';

class FakeClipboardItem {
  constructor(readonly data: Record<string, Blob | Promise<Blob>>) {}
}

describe('copyDeferredTextToClipboard', () => {
  let clipboardDescriptor: PropertyDescriptor | undefined;
  let clipboardItemDescriptor: PropertyDescriptor | undefined;
  let secureContextDescriptor: PropertyDescriptor | undefined;

  beforeEach(() => {
    clipboardDescriptor = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
    clipboardItemDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'ClipboardItem');
    secureContextDescriptor = Object.getOwnPropertyDescriptor(window, 'isSecureContext');

    Object.defineProperty(window, 'isSecureContext', {
      configurable: true,
      value: true
    });
    Object.defineProperty(globalThis, 'ClipboardItem', {
      configurable: true,
      value: FakeClipboardItem
    });
  });

  afterEach(() => {
    restoreProperty(navigator, 'clipboard', clipboardDescriptor);
    restoreProperty(globalThis, 'ClipboardItem', clipboardItemDescriptor);
    restoreProperty(window, 'isSecureContext', secureContextDescriptor);
  });

  it('starts clipboard write before deferred text resolves', async () => {
    let resolveText: (value: string) => void = () => undefined;
    const loadText = vi.fn(() => new Promise<string>((resolve) => {
      resolveText = resolve;
    }));
    const write = vi.fn(async (items: FakeClipboardItem[]) => {
      const blob = await items[0].data['text/plain'];
      expect(await blob.text()).toBe('iphone-secret');
    });
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { write }
    });

    const copied = copyDeferredTextToClipboard(loadText);

    expect(loadText).toHaveBeenCalledTimes(1);
    expect(write).toHaveBeenCalledTimes(1);

    resolveText(' iphone-secret ');
    await expect(copied).resolves.toBe(true);
  });

  it('falls back to writeText when deferred clipboard write is unavailable', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText }
    });

    await expect(copyDeferredTextToClipboard(async () => ' copied ')).resolves.toBe(true);

    expect(writeText).toHaveBeenCalledWith('copied');
  });

  it('rejects empty deferred text', async () => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: {}
    });

    await expect(copyDeferredTextToClipboard(async () => ' ')).rejects.toThrow('Cannot copy empty text');
  });
});

function restoreProperty(target: object, property: string, descriptor: PropertyDescriptor | undefined): void {
  if (descriptor) {
    Object.defineProperty(target, property, descriptor);
    return;
  }

  Reflect.deleteProperty(target, property);
}
