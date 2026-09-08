const { test } = require('node:test');
const assert = require('node:assert/strict');
const { decodeSnappy } = require('./consumer-fixture.cjs');

test('Loki fixture decodes bounded literals and overlapping copy; rejects corrupt blocks', () => {
  assert.equal(decodeSnappy(Buffer.from([5, 16, ...Buffer.from('hello')])).toString(), 'hello');
  assert.equal(decodeSnappy(Buffer.from([8, 0, 97, 26, 1, 0])).toString(), 'aaaaaaaa');
  for (const bytes of [[8, 0, 97, 26, 0, 0], [5, 16, 104], [0x80, 0x80, 0x80, 0x20], [6, 16, ...Buffer.from('hello')]]) {
    assert.throws(() => decodeSnappy(Buffer.from(bytes)));
  }
});
