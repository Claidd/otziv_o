export async function copyTextToClipboard(text: string): Promise<boolean> {
  const value = normalizeClipboardText(text);
  if (!value) {
    return false;
  }

  if (typeof navigator.clipboard?.writeText === 'function' && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(value);
      return true;
    } catch {
      // Safari and in-app browsers can reject the async Clipboard API even on HTTPS.
    }
  }

  return copyTextWithTextarea(value);
}

export async function copyDeferredTextToClipboard(loadText: () => Promise<string>): Promise<boolean> {
  const textPromise = loadText().then((text) => {
    const value = normalizeClipboardText(text);
    if (!value) {
      throw new Error('Cannot copy empty text');
    }
    return value;
  });

  if (typeof navigator.clipboard?.write === 'function' && typeof ClipboardItem !== 'undefined' && window.isSecureContext) {
    try {
      await navigator.clipboard.write([
        new ClipboardItem({
          'text/plain': textPromise.then((value) => new Blob([value], { type: 'text/plain' }))
        })
      ]);
      return true;
    } catch {
      // iOS Safari needs the write call to start in the tap handler, but other
      // browsers may still allow a normal write after the deferred value loads.
    }
  }

  return copyTextToClipboard(await textPromise);
}

function normalizeClipboardText(text: string): string {
  return (text ?? '').trim();
}

function copyTextWithTextarea(text: string): boolean {
  const textarea = document.createElement('textarea');
  textarea.value = text;
  textarea.setAttribute('readonly', '');
  textarea.style.position = 'fixed';
  textarea.style.top = '0';
  textarea.style.left = '0';
  textarea.style.width = '1px';
  textarea.style.height = '1px';
  textarea.style.opacity = '0';
  textarea.style.pointerEvents = 'none';

  document.body.appendChild(textarea);

  try {
    textarea.focus({ preventScroll: true });
    textarea.select();
    textarea.setSelectionRange(0, textarea.value.length);
    return document.execCommand('copy');
  } catch {
    return false;
  } finally {
    textarea.remove();
  }
}
