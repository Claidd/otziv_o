import test from 'node:test';
import assert from 'node:assert/strict';
import { dockerEnvironment } from './docker-environment.mjs';
test('fixture Docker credentials leave argv while the real Node runner expression remains executable', () => {
  const expression = 'setInterval(()=>{},100000)';
  const result = dockerEnvironment(['create', '-e', 'MYSQL_PASSWORD=fixture=secret', 'sha256:image', 'node', '-e', expression], { PATH: '/bin' });
  assert.deepEqual(result.args, ['create', '-e', 'MYSQL_PASSWORD', 'sha256:image', 'node', '-e', expression]);
  assert.equal(result.env.MYSQL_PASSWORD, 'fixture=secret');
  assert.ok(!result.args.some(value => value.includes('fixture=secret')));
  assert.equal(result.env.PATH, '/bin');
});
