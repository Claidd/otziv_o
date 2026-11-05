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

const {createMetricsServer, renderMetrics} = require('./proxy');
const {tmpdir} = require('node:os');
const {join} = require('node:path');

function fakeDocker(t, routes) {
  const socketPath = process.platform === 'win32'
    ? String.raw`\\.\pipe\otziv-observer-metrics-` + `${process.pid}-${Date.now()}`
    : join(tmpdir(), `otziv-observer-metrics-${process.pid}-${Date.now()}.sock`);
  const seen = [];
  const daemon = http.createServer((req, res) => {
    seen.push(`${req.method} ${req.url}`);
    const body = routes[req.url];
    if (body === undefined) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, {'content-type': 'application/json'}); res.end(JSON.stringify(body));
  }).listen(socketPath);
  t.after(() => daemon.close());
  return {socketPath, seen, ready: new Promise(resolve => daemon.once('listening', resolve))};
}

function get(server, path, method = 'GET') {
  return new Promise((resolve, reject) => {
    const request = http.request({host: '127.0.0.1', port: server.address().port, path, method}, response => {
      let text = ''; response.setEncoding('utf8');
      response.on('data', chunk => { text += chunk; }); response.on('end', () => resolve({status: response.statusCode, text}));
    });
    request.on('error', reject); request.end();
  });
}

test('metrics listener reports compose container restarts and health without secrets', async t => {
  const app = 'a'.repeat(64), mysql = 'b'.repeat(64), probe = 'c'.repeat(64), oneoff = 'd'.repeat(64), other = 'f'.repeat(64);
  const self = 'e'.repeat(12);
  const compose = service => ({'com.docker.compose.project': 'otziv-prod', 'com.docker.compose.service': service});
  const docker = fakeDocker(t, {
    '/containers/json?all=1': [
      {Id: app, Names: ['/otziv-prod-app-1'], Labels: compose('app')},
      {Id: mysql, Names: ['/my-mysql'], Labels: compose('mysql')},
      {Id: probe, Names: ['/otziv-observer-probe-x'], Labels: {'dozzle.name': 'OTZIV_DEPLOY_x'}},
      {Id: oneoff, Names: ['/otziv-prod-app-run-1'], Labels: {...compose('app'), 'com.docker.compose.oneoff': 'True'}},
      {Id: other, Names: ['/other-stack-db-1'], Labels: {'com.docker.compose.project': 'other-stack', 'com.docker.compose.service': 'db'}},
    ],
    [`/containers/${self}/json`]: {Name: '/otziv-prod-docker-observer-1', Config: {Labels: compose('docker-observer')}},
    [`/containers/${app}/json`]: {Name: '/otziv-prod-app-1', RestartCount: 3, Config: {Env: ['TOKEN=secret-value']},
      State: {Running: true, Restarting: false, OOMKilled: false, StartedAt: '2026-11-05T01:02:03Z', Health: {Status: 'unhealthy', Log: [{Output: 'secret-value'}]}}},
    [`/containers/${mysql}/json`]: {Name: '/my-mysql', RestartCount: 0,
      // Stopped: Docker keeps the last health status, which is not a failing check any more.
      State: {Running: false, Restarting: true, OOMKilled: true, StartedAt: '0001-01-01T00:00:00Z', Health: {Status: 'unhealthy'}}},
  });
  await docker.ready;
  const server = createMetricsServer(docker.socketPath, self).listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  t.after(() => server.close());

  const {status, text} = await get(server, '/metrics');
  assert.equal(status, 200);
  const appLabels = 'project="otziv-prod",service="app",container="otziv-prod-app-1"';
  const mysqlLabels = 'project="otziv-prod",service="mysql",container="my-mysql"';
  assert.ok(text.includes(`otziv_container_restarts_total{${appLabels}} 3`));
  assert.ok(text.includes(`otziv_container_restarts_total{${mysqlLabels}} 0`));
  assert.ok(text.includes(`otziv_container_health_status{${appLabels},status="unhealthy"} 1`));
  assert.ok(text.includes(`otziv_container_health_status{${appLabels},status="healthy"} 0`));
  assert.ok(text.includes(`otziv_container_restarting{${mysqlLabels}} 1`));
  assert.ok(text.includes(`otziv_container_oom_killed{${mysqlLabels}} 1`));
  assert.ok(text.includes(`otziv_container_started_time_seconds{${appLabels}} ${Date.parse('2026-11-05T01:02:03Z') / 1000}`));
  assert.ok(!text.includes(`started_time_seconds{${mysqlLabels}}`), 'zero start time is omitted');
  assert.ok(!/health_status\{[^}]*service="mysql"/.test(text), 'a stopped container has no health series');
  assert.ok(!text.includes('secret-value') && !text.includes('probe') && !text.includes('app-run') && !text.includes('other-stack'));
  // Only list and inspect requests reach Docker; nothing for the probe, the one-off run or another project.
  const scrape = ['GET /containers/json?all=1', `GET /containers/${app}/json`, `GET /containers/${mysql}/json`];
  assert.deepEqual(docker.seen, [`GET /containers/${self}/json`, ...scrape]);
  assert.equal((await get(server, '/metrics')).status, 200);
  assert.deepEqual(docker.seen.slice(4), scrape, 'the own project is looked up once');

  assert.equal((await get(server, '/containers/json')).status, 404);
  assert.equal((await get(server, '/metrics?x=1')).status, 404);
  assert.equal((await get(server, '/metrics', 'POST')).status, 405);
  assert.equal(docker.seen.length, 7, 'rejected requests never reach Docker');
});

test('metrics listener fails the scrape when Docker is unavailable and escapes label values', async t => {
  const server = createMetricsServer('/nonexistent-docker-observer-metrics.sock').listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  t.after(() => server.close());
  assert.equal((await get(server, '/metrics')).status, 503);
  const text = renderMetrics([{project: 'p', service: 's"\\' + '\n', container: 'c', restarts: 0, running: true,
    restarting: false, oomKilled: false, health: null, startedAt: null}]);
  assert.ok(text.includes(String.raw`service="s\"\\\n"`));
});
