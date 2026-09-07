import test from 'node:test';
import assert from 'node:assert/strict';
import { run } from './process.mjs';

test('subprocess text preserves a UTF-8 character split across pipe chunks', async () => {
  const script = "process.stdout.write(Buffer.from([0xd0])); setTimeout(() => process.stdout.write(Buffer.from([0xa2,0xd0,0xb5,0xd1,0x81,0xd1,0x82])), 100)";
  assert.equal(await run(process.execPath, ['-e', script]), 'Тест');
});

test('decoded output remains bounded by UTF-8 bytes', async () => {
  await assert.rejects(run(process.execPath, ['-e', "process.stdout.write('Тест')"], { maxOutput: 7 }), /subprocess_timeout/);
});
