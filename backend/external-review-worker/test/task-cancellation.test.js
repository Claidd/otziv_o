import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { TaskLimiter } from '../src/task-limiter.js';
import { TaskCancellation } from '../src/task-cancellation.js';
const deferred = () => { let resolve; const promise = new Promise(done => resolve = done); return { promise, resolve }; };

test('disconnect closes cancellable work, but keeps admission and cleanup age until actual exit', async () => {
  let now = 1000; const limiter = new TaskLimiter(1, { now: () => now });
  const req = new EventEmitter(), res = new EventEmitter(), exit = deferred(), entered = deferred();
  let closes = 0;
  const running = limiter.run(async () => {
    const cancel = new TaskCancellation(req, res, limiter);
    await cancel.own({ close: () => { closes++; return exit.promise; } });
    entered.resolve(cancel); await exit.promise; await cancel.close();
  });
  await entered.promise; res.emit('close'); req.emit('aborted');
  await Promise.resolve(); now = 6000;
  assert.equal(closes, 1);
  assert.deepEqual([limiter.snapshot().running, limiter.snapshot().cleanup, limiter.snapshot().oldestCleanupSeconds], [0, 1, 5]);
  await assert.rejects(limiter.run(() => assert.fail()), /gateway_busy/);
  assert.equal(limiter.snapshot().cancelledTotal, 1); assert.equal(limiter.snapshot().rejectedTotal.busy, 1);
  exit.resolve(); await running; assert.equal(limiter.snapshot().active, 0);
});

test('resource created after cancellation is reaped before ownership returns', async () => {
  const limiter = new TaskLimiter(1), exit = deferred(); let closes = 0;
  await limiter.run(async () => {
    const cancel = new TaskCancellation(new EventEmitter(), new EventEmitter(), limiter);
    cancel.cancel('deadline'); cancel.cancel('deadline');
    const creation = assert.rejects(cancel.own({ close: () => { closes++; return exit.promise; } }), /cancelled/);
    await Promise.resolve(); assert.equal(limiter.active, 1); assert.equal(closes, 1);
    exit.resolve(); await creation; await cancel.close();
  });
  assert.equal(limiter.snapshot().timedOutTotal, 1);
});

test('normal response completion does not cancel, failed cleanup is never reported successful', async () => {
  const limiter = new TaskLimiter(1), res = new EventEmitter(); res.writableFinished = true;
  await limiter.run(async () => {
    const cancel = new TaskCancellation(new EventEmitter(), res, limiter);
    await cancel.own({ close: () => Promise.reject(new Error('fixture failure')) });
    res.emit('close'); cancel.assertActive();
    await assert.rejects(cancel.close(), /cleanup failed/);
  });
  assert.equal(limiter.snapshot().cancelledTotal, 0);
});
