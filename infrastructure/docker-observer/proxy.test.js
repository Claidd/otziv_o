'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {authorize, sanitize, createProxy} = require('./proxy');
const http = require('node:http');

test('observer allows only discovery and logs; mutation and path bypasses fail closed', () => {
  const id = 'a'.repeat(64);
  assert.equal(authorize('GET', '/v1.45/containers/json'), '/containers/json');
  for (const path of ['/_ping', '/info', '/version', '/events', '/containers/json', `/containers/${id}/logs?follow=1`]) {
    assert.ok(authorize('GET', path), path);
    assert.equal(authorize('POST', path), null);
    assert.equal(authorize('DELETE', path), null);
  }
  for (const path of ['/containers/create', `/containers/${id}/exec`, `/containers/${id}/archive`, '/build', '/images/json',
    '/secrets', '/volumes', '/%2e%2e/info', '/containers/../info', '//info', '/info%3f', '/info?arbitrary=1', 'http://docker/info']) {
    assert.equal(authorize('GET', path), null, path);
  }
});

test('inspect removes environment, command, mounts and health command output', () => {
  const clean = sanitize('/containers/aaaaaaaaaaaa/json', {Id:'a', Name:'demo', Config:{Env:['TOKEN=secret'], Cmd:['secret'], Image:'demo'},
    Mounts:[{Source:'/secret'}], State:{Running:true, Health:{Status:'healthy',Log:[{Output:'secret'}]}}});
  assert.equal(JSON.stringify(clean).includes('secret'), false);
  assert.equal(clean.State.Health.Status, 'healthy');
});

test('forbidden request cannot reach an unavailable daemon', async t => {
  const server = createProxy('/nonexistent-docker-observer-test.sock').listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  t.after(() => server.close());
  const status = await new Promise((resolve, reject) => {
    const request = http.request({host:'127.0.0.1', port:server.address().port,path:'/containers/create',method:'POST'}, response => {
      response.resume(); response.on('end', () => resolve(response.statusCode));
    }); request.on('error', reject); request.end();
  });
  assert.equal(status, 403);
});
