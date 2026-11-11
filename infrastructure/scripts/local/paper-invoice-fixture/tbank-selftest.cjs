// Self-test of the local T-Bank sandbox: starts the fixture on a spare port, exercises issue, state, QR,
// payment, cancellation and the control page, then stops it.
// Run: node infrastructure/scripts/local/paper-invoice-fixture/tbank-selftest.cjs
const {spawn} = require('node:child_process');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const port = 18099;
const child = spawn(process.execPath, [require('node:path').join(__dirname, 'server.cjs')], {stdio: 'inherit', env: {...process.env, FIXTURE_PORT: String(port)}});
const base = 'http://127.0.0.1:' + port;
// The sandbox marker that stands in for a terminal password; the fixture signs with the same value.
const marker = 'local-sandbox-only';
const sign = values => crypto.createHash('sha256')
  .update(Object.keys({...values, Password: marker}).sort().map(k => String({...values, Password: marker}[k])).join('')).digest('hex');
async function call(path, payload) {
  const flat = Object.fromEntries(Object.entries(payload).filter(([, v]) => v !== null && typeof v !== 'object'));
  const r = await fetch(base + path, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({...payload, Token: sign(flat)})});
  return r.json();
}
(async () => {
  for (let i = 0; i < 50; i++) { try { if ((await fetch(base + '/health')).ok) break; } catch {} await new Promise(r => setTimeout(r, 100)); }
  const init = await call('/tbank/v2/Init', {TerminalKey: 'LocalSandboxPrimary', Amount: 100000, OrderId: 'order-1', Description: 'Тест', DATA: {Email: 'a@b.c'}});
  assert.equal(init.Success, true); assert.equal(init.Status, 'NEW'); assert.equal(init.PaymentURL, 'https://securepay.tinkoff.ru/otziv-local-fixture/' + init.PaymentId);
  const again = await call('/tbank/v2/Init', {TerminalKey: 'LocalSandboxPrimary', Amount: 100000, OrderId: 'order-1'});
  assert.equal(again.Success, false);
  const foreign = await call('/tbank/v2/Init', {TerminalKey: 'RealTerminal', Amount: 100000, OrderId: 'order-2'});
  assert.equal(foreign.Success, false);
  const unsigned = await (await fetch(base + '/tbank/v2/GetState', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({TerminalKey: 'LocalSandboxPrimary', PaymentId: init.PaymentId, Token: 'bad'})})).json();
  assert.equal(unsigned.Success, false);
  let state = await call('/tbank/v2/GetState', {TerminalKey: 'LocalSandboxPrimary', PaymentId: init.PaymentId});
  assert.equal(state.Status, 'NEW'); assert.equal(state.Amount, 100000);
  const qr = await call('/tbank/v2/GetQr', {TerminalKey: 'LocalSandboxPrimary', PaymentId: init.PaymentId, DataType: 'PAYLOAD'});
  assert.equal(qr.Success, true); assert.match(qr.Data, /^https:\/\/qr\.nspk\.ru\//);
  const paid = await (await fetch(base + '/test/pay-tbank', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({paymentId: init.PaymentId})})).json();
  assert.equal(paid.status, 'CONFIRMED'); assert.equal(paid.webhook.status, 0); // no app outside Docker
  state = await call('/tbank/v2/GetState', {TerminalKey: 'LocalSandboxPrimary', PaymentId: init.PaymentId});
  assert.equal(state.Status, 'CONFIRMED');
  const second = await call('/tbank/v2/Init', {TerminalKey: 'LocalSandboxSecondary', Amount: 5000, OrderId: 'order-3'});
  const cancel = await call('/tbank/v2/Cancel', {TerminalKey: 'LocalSandboxSecondary', PaymentId: second.PaymentId});
  assert.equal(cancel.Status, 'CANCELED'); assert.equal(cancel.Amount, undefined); assert.equal(cancel.NewAmount, 0);
  state = await call('/tbank/v2/GetState', {TerminalKey: 'LocalSandboxSecondary', PaymentId: second.PaymentId});
  assert.equal(state.Status, 'CANCELED'); assert.equal(state.Amount, 0);
  await fetch(base + '/delivery', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({operationId: 'op-1', groupId: 'g-1', caption: 'Счёт <b>на</b> 1 000 ₽', title: 'Компания'})});
  const home = await (await fetch(base + '/')).text();
  assert.ok(home.includes('Песочница оплат') && home.includes(init.PaymentId) && home.includes('Счёт &lt;b&gt;на&lt;/b&gt; 1 000 ₽'));
  const pay = await fetch(base + '/pay/' + second.PaymentId);
  assert.equal(pay.status, 200); assert.ok((await pay.text()).includes('CANCELED'));
  assert.equal((await fetch(base + '/pay/unknown')).status, 404);
  console.log('fixture self-test passed');
})().catch(error => { console.error(error); process.exitCode = 1; }).finally(() => child.kill());
