import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const { manualPaymentTaskWorklist } = loadTsModule(
  'src/app/shared/manual-payment-task-visibility.ts'
);

test('manual task worklist hides only canceled tasks and preserves its source', () => {
  const tasks = [
    { id: 1, status: 'ACTIVE' },
    { id: 2, status: 'PAUSED' },
    { id: 3, status: 'COMPLETED' },
    { id: 4, status: 'NEEDS_ATTENTION' },
    { id: 5, status: 'CANCELED' },
    { id: 6, status: 'FUTURE_STATUS' }
  ];

  assert.deepEqual(
    manualPaymentTaskWorklist(tasks).map((task) => task.id),
    [1, 2, 3, 4, 6]
  );
  assert.deepEqual(tasks.map((task) => task.id), [1, 2, 3, 4, 5, 6]);
  assert.equal(manualPaymentTaskWorklist(null).length, 0);
});

test('all web and mobile worklists render the canceled-task filter', () => {
  const mobileBank = fs.readFileSync('src/app/features/tbank.page.ts', 'utf8');
  const mobileHome = fs.readFileSync('src/app/features/home.page.ts', 'utf8');
  const webBank = fs.readFileSync(
    '../frontend/src/app/features/admin/tbank-payments/tbank-payments.component.html',
    'utf8'
  );
  const webHome = fs.readFileSync('../frontend/src/app/features/home/home.component.html', 'utf8');

  assert.match(mobileBank, /@for \(task of visibleManualTasks\(\)/);
  assert.match(mobileHome, /@for \(task of visibleManualPaymentTasks\(\)/);
  assert.match(webBank, /@for \(task of visibleManualTasks\(\)/);
  assert.match(webHome, /@for \(task of visibleManualPaymentTasks\(\)/);
});
