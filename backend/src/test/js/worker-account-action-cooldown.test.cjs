const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('../../../../frontend/node_modules/jsdom');

const script = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/worker-account-action-cooldown.js'), 'utf8');
const flush = () => new Promise(resolve => setImmediate(resolve));
const baseNow = Date.parse('2026-09-07T00:00:00Z');
const state = (seconds = 0, enabled = true) => ({
    enabled, durationSeconds: enabled ? 60 : 0, remainingSeconds: seconds,
    serverNow: new Date(baseNow).toISOString(),
    availableAt: seconds > 0 ? new Date(baseNow + seconds * 1000).toISOString() : null
});

function setup({ worker = true, initial = state() } = {}) {
    const dom = new JSDOM(`<!doctype html><body>
        ${worker ? '<span hidden data-worker-account-action-cooldown></span>' : ''}
        <nav><a href="/worker/nagul">Другой раздел</a></nav>
        <div id="cards"><form onsubmit="changeBot(event,this)"><button type="submit">смена</button></form>
        <form onsubmit="deActivateBot(event,this)"><button type="submit">блок</button></form>
        <form onsubmit="changeBot(event,this)"><button type="submit" disabled title="Недоступно">смена</button></form></div>
        </body>`, { url: 'https://test.local/worker/publish', runScripts: 'outside-only', pretendToBeVisual: true });
    let nextState = initial;
    let currentNow = baseNow;
    let calls = 0;
    const tickers = new Map();
    let tickerId = 0;
    dom.window.Date.now = () => currentNow;
    dom.window.performance.now = () => currentNow;
    dom.window.setInterval = callback => { tickers.set(++tickerId, callback); return tickerId; };
    dom.window.clearInterval = id => tickers.delete(id);
    dom.window.fetch = async () => { calls++; return { ok: true, json: async () => nextState }; };
    dom.window.eval(script);
    return {
        dom, get api() { return dom.window.workerAccountActionCooldown; },
        get buttons() { return [...dom.window.document.querySelectorAll('button')]; },
        get calls() { return calls; },
        setState(value) { nextState = value; },
        advance(ms) { currentNow += ms; [...tickers.values()].forEach(callback => callback()); },
        response(seconds, enabled = true) {
            const data = state(seconds, enabled);
            return { headers: new Headers({
                'X-Worker-Account-Action-Enabled': String(data.enabled),
                'X-Worker-Account-Action-Duration-Seconds': String(data.durationSeconds),
                'X-Worker-Account-Action-Available-At': data.availableAt || '',
                'X-Worker-Account-Action-Server-Now': data.serverNow
            }) };
        }
    };
}

test('ordinary roles never initialize a timer or request specialist status', async () => {
    const page = setup({ worker: false });
    await flush();
    assert.equal(page.api, undefined);
    assert.equal(page.calls, 0);
    assert.equal(page.buttons[0].disabled, false);
    page.dom.window.close();
});

test('one accepted action locks both buttons, preserves navigation and prior disabled rules', async () => {
    const page = setup();
    await flush();
    assert.equal(page.api.begin(), true);
    assert.equal(page.api.begin(), false);
    assert.ok(page.buttons.every(button => button.disabled));
    assert.equal(page.dom.window.document.querySelector('a').getAttribute('href'), '/worker/nagul');
    page.setState(state(60));
    page.api.response(page.response(60));
    page.api.finish();
    await flush();
    assert.match(page.dom.window.document.querySelector('aside strong').textContent, /01:00/);
    page.advance(60_000);
    assert.equal(page.buttons[0].disabled, false);
    assert.equal(page.buttons[1].disabled, false);
    assert.equal(page.buttons[2].disabled, true);
    assert.equal(page.buttons[2].title, 'Недоступно');
    assert.equal(page.dom.window.document.querySelector('aside').hidden, true);
    page.dom.window.close();
});

test('reload restores remaining server pause and AJAX replacement receives disabled buttons', async () => {
    const page = setup({ initial: state(37) });
    await flush();
    assert.equal(page.api.begin(), false);
    assert.match(page.dom.window.document.querySelector('aside strong').textContent, /00:37/);
    page.dom.window.document.querySelector('#cards').innerHTML = '<form onsubmit="changeBot(event,this)"><button type="submit">смена</button></form>';
    await flush();
    assert.equal(page.buttons[0].disabled, true);
    page.advance(37_000);
    assert.equal(page.buttons[0].disabled, false);
    page.dom.window.close();
});

test('another tab notification reloads authoritative state and configuration zero removes pause', async () => {
    const page = setup();
    await flush();
    page.setState(state(52));
    page.dom.window.dispatchEvent(new page.dom.window.StorageEvent('storage', { key: 'otziv-worker-account-action-changed' }));
    await flush();
    assert.equal(page.api.locked(), true);
    page.setState(state(0, false));
    page.dom.window.dispatchEvent(new page.dom.window.Event('pageshow'));
    await flush();
    assert.equal(page.api.locked(), false);
    page.dom.window.close();
});

test('an old status response cannot erase a newer accepted mutation deadline', async () => {
    const page = setup();
    await flush();
    let resolveOld;
    page.dom.window.fetch = () => new Promise(resolve => { resolveOld = resolve; });
    page.dom.window.dispatchEvent(new page.dom.window.Event('pageshow'));
    assert.equal(page.api.begin(), true);
    page.api.response(page.response(60));
    resolveOld({ ok: true, json: async () => state() });
    await flush();
    assert.equal(page.api.locked(), true);
    page.dom.window.close();
});

test('an open active page notices a disabled switch with preserved duration through coarse refresh', async () => {
    const page = setup({ initial: state(3600) });
    await flush();
    assert.equal(page.api.locked(), true);
    page.setState({ ...state(0, false), durationSeconds: 180 });
    page.advance(30_000);
    await flush();
    assert.equal(page.api.locked(), false);
    assert.equal(page.buttons[0].disabled, false);
    assert.equal(page.dom.window.document.querySelector('aside').hidden, true);
    assert.equal(page.api.begin(), true);
    page.api.finish();
    await flush();
    assert.equal(page.api.locked(), false);
    page.dom.window.close();
});

test('lost mutation and status responses preserve provisional configured pause', async () => {
    const page = setup({ initial: { ...state(), durationSeconds: 120 } });
    await flush();
    page.dom.window.fetch = async () => { throw new Error('offline'); };
    assert.equal(page.api.begin(), true);
    page.api.finish();
    await flush();
    assert.equal(page.api.locked(), true);
    page.advance(119_000);
    assert.equal(page.api.locked(), true);
    page.advance(1_000);
    assert.equal(page.api.locked(), false);
    await flush();
    page.dom.window.close();
});
