// Minimal Chrome DevTools Protocol client for the screenshot browser.
import { writeFileSync } from 'node:fs';

const PORT = Number(process.env.OTZIV_GUIDE_SHOTS_PORT ?? 9333);

export async function targets() {
  return (await fetch(`http://127.0.0.1:${PORT}/json`)).json();
}

export async function openPage(match = t => t.type === 'page' && t.url.startsWith('http://localhost:8088')) {
  const list = await targets();
  const target = list.find(match);
  if (!target) throw new Error('no matching page: ' + list.map(t => t.type + ' ' + t.url).join('; '));
  return connect(target.webSocketDebuggerUrl);
}

export async function newPage(url) {
  const target = await (await fetch(`http://127.0.0.1:${PORT}/json/new?${encodeURIComponent(url)}`, { method: 'PUT' })).json();
  return connect(target.webSocketDebuggerUrl, target.id);
}

export async function closeTarget(id) {
  await fetch(`http://127.0.0.1:${PORT}/json/close/${id}`);
}

function connect(url, id) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
    let next = 1;
    const pending = new Map();
    const listeners = new Set();
    ws.onmessage = event => {
      const message = JSON.parse(event.data);
      if (message.id && pending.has(message.id)) {
        const { ok, fail } = pending.get(message.id);
        pending.delete(message.id);
        message.error ? fail(new Error(message.error.message + ' ' + (message.error.data ?? ''))) : ok(message.result);
      } else {
        for (const listener of listeners) listener(message);
      }
    };
    ws.onerror = () => reject(new Error('websocket error'));
    ws.onopen = () => {
      const send = (method, params = {}, timeout = 45000) => new Promise((ok, fail) => {
        const callId = next++;
        const timer = setTimeout(() => {
          if (pending.delete(callId)) fail(new Error('timeout: ' + method + ' ' + JSON.stringify(params).slice(0, 160)));
        }, timeout);
        pending.set(callId, { ok: v => { clearTimeout(timer); ok(v); }, fail: e => { clearTimeout(timer); fail(e); } });
        ws.send(JSON.stringify({ id: callId, method, params }));
      });
      // Native confirm/alert windows block the page: answer them at once. The answer is «Отмена»
      // unless a scene sets page.acceptDialogs for a step it really means to perform.
      listeners.add(message => {
        if (message.method === 'Page.javascriptDialogOpening') {
          const accept = !!page.acceptDialogs;
          page.dialogs.push({ type: message.params.type, message: message.params.message, accept });
          console.log(`   [dialog ${message.params.type} -> ${accept ? 'OK' : 'Отмена'}] ${message.params.message.slice(0, 200)}`);
          send('Page.handleJavaScriptDialog', { accept }).catch(() => {});
        }
      });
      const page = {
        id,
        send,
        acceptDialogs: false,
        dialogs: [],
        on: listener => { listeners.add(listener); return () => listeners.delete(listener); },
        close: () => ws.close(),
        /** Evaluates an expression (may be a promise) and returns its JSON value. */
        async eval(expression) {
          const result = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true, userGesture: true });
          if (result.exceptionDetails) {
            throw new Error('page error: ' + (result.exceptionDetails.exception?.description ?? result.exceptionDetails.text));
          }
          return result.result.value;
        },
        /** Calls a function source with JSON arguments in the page. */
        call(fn, ...args) {
          return page.eval(`(${fn})(${args.map(a => JSON.stringify(a)).join(',')})`);
        },
        async viewport(width, height, scale = 2) {
          await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: scale, mobile: false });
        },
        async goto(target, settle = 1500) {
          await send('Page.navigate', { url: target });
          await sleep(settle);
        },
        async shot(file, clip, format = 'png', quality) {
          // A background tab is throttled and its screenshot can hang: the captured tab goes in front first.
          await send('Page.bringToFront');
          const params = { format, captureBeyondViewport: true };
          if (quality) params.quality = quality;
          if (clip) params.clip = { ...clip, scale: 1 };
          const { data } = await send('Page.captureScreenshot', params);
          writeFileSync(file, Buffer.from(data, 'base64'));
        },
        async waitFor(expression, timeout = 15000, step = 200) {
          const until = Date.now() + timeout;
          for (;;) {
            const value = await page.eval(expression);
            if (value) return value;
            if (Date.now() > until) throw new Error('timeout waiting for: ' + expression);
            await sleep(step);
          }
        }
      };
      send('Page.enable').then(() => resolve(page), reject);
    };
  });
}

export const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
