import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { readReviewedImageSet, reviewedImageSet, reviewedImageSetForComponent } from './reviewed-image-sets.mjs';
import { validateManifest } from './publish-reviewed-images.mjs';
import { C26_POLICY } from './keycloak-runtime-dependencies.mjs';

const root = resolve(import.meta.dirname, '../..');
const dockerfilePath = 'infrastructure/runtime-security/builds/c35-keycloak/Dockerfile';
const parent = 'ghcr.io/claidd/otziv-security@sha256:76ad15fac3c0041798b6b2b87632bbf91ae7c171ebfa813aa1d1633b48f90ff9';
const reviewedJars = new Map(Object.entries({
  'vertx-auth-common':
    '5103b6ce5121aa783707278b4ec08a19' +
    'a0405d87b91dced1d9f6054d7ffb5dc1',
  'vertx-bridge-common': 'a004fef3622ce6db8ba11c502f2c752dd0975eaf7c8e940e71c4c4ca0ccbf4cd',
  'vertx-codegen': 'b82fe8fe7324af7a37fd41940c228af99ea9c400e96ac55d0cfe8016b85b5337',
  'vertx-core': '1a274ba3739dc72506d5e801a8337ab734e07fbfd48ece7e77a518fbf40cea9a',
  'vertx-grpc': '70c91f9d4d9716f130057bc6d286687da9222a7fb96251dd91ac0a7bb55ed143',
  'vertx-grpc-client': '474b83d79944d1b4869452a9712b8cb51c9b6584e48d32ef113720c4de1072f7',
  'vertx-grpc-common': '908557303faea174e8755e6a1fb01771208db2e215dbeaff2549891855b50f8e',
  'vertx-grpc-server': '115d31258beffa4c2205ccd614981653df4a3ff48ad6dfb428fb9bd2a8a46962',
  'vertx-web': 'a8d9d06554e23286edb542d9adf15d057bda0399455a8f04ceb27e39bea1a0ac',
  'vertx-web-common': 'e6938a7c555ae87ccde10af71cfb264483ea7f952e7d7724e4196bef21cec64d',
}));
const reviewedNettyJars = new Map(Object.entries({
  'netty-buffer': '48d805b74e56dc5f92b1f288858185abc86db16d293e8da183542df6243427f2',
  'netty-codec': 'ad43f07219dfb757a87b9c2b4765002be478c5401971379954e3d182f285fe6b',
  'netty-codec-dns': 'e044715cfd72e4717a06e41f4614dea0246ac20059d3aa72f36f16be20bc623c',
  'netty-codec-haproxy': '3109e099f34523799479c70b12b7d3731b9bb891acc8654a145eefd189a63472',
  'netty-codec-http2': '4cb8bc95386112f3a9da935272781d577d9e70ca4c56cf0eef603a45e9526e02',
  'netty-codec-http': '627cfdac2cd5dbd8a33af85977fb81fa6b46f0c00389ab4c8677a415f0f85480',
  'netty-codec-socks': '94d6800d5f717b40db5d0bf42a77b448d472cd9033735e69e7b18311bd902f23',
  'netty-common': '50372d86e27b4b1cf0810d75eb534795966a199049a1e85a0c41bbd2bb97f519',
  'netty-handler': 'ff85f00781b956d618b20f33da076c839a5e43e0084108b07bab0c19737168ec',
  'netty-handler-proxy': '9c92c2407b6fd3c120ab5efb900475a1fe40ce099703d2500c8679d3c75a90cb',
  'netty-resolver': '562a5388c88325b7d6ed05e8168736871ce0e64ab01e0ce52ea7710760b9b457',
  'netty-resolver-dns': '15feeffd0cbc2987ba35b798ca5b05fa1bc10cb880b8aa13e9bd81af3d483525',
  'netty-transport': 'be7bff8b6274e7fae12bbad3c565777539e9b069517e0b3acc06d9ac2cd6ec05',
  'netty-transport-classes-epoll': '99ea06d67e3e2792cc93521b686223c78da2561bfdf98533ebb29fed1729cce4',
  'netty-transport-native-epoll:linux-aarch_64': '039c9ce3bef089dfde34987cac3c928466cb71d54d3df79c0068fd17ee166e51',
  'netty-transport-native-epoll:linux-x86_64': '696fff4fb645d64fec6bc54fae27d9b643d64814e9be3bf42cbd94a608e2bcd0',
  'netty-transport-native-unix-common': '8e660ac8a485fc8d8b47d87d04ea33d19902dc6fc5a113e9353b762188b3f333',
  'netty-tcnative-classes': '7a4437abf98b0cec053af4443fe321177d2cfccfec7bcad4e7d8b75571bbec4d',
}));

test('C35 Keycloak retains its reviewed parent and replaces the aligned Vert.x and Netty runtime sets', async () => {
  const selected = await readReviewedImageSet(root, 'c35-keycloak');
  const [image] = validateManifest(selected.manifest);
  const previous = await readReviewedImageSet(root, 'c26-keycloak');
  const dockerfile = (await readFile(resolve(root, dockerfilePath), 'utf8')).replaceAll('\r\n', '\n');
  const workflow = await readFile(resolve(root, '.github/workflows/quality-gates.yml'), 'utf8');
  const pom = await readFile(resolve(root, 'infrastructure/keycloak/security-generation/pom.xml'), 'utf8');

  assert.deepEqual(reviewedImageSetForComponent('keycloak', reviewedImageSet('c35-keycloak').path), reviewedImageSet('c35-keycloak'));
  assert.equal(previous.manifest.images[0].candidateBaseRef, 'ghcr.io/claidd/otziv-security@sha256:5c73f4ab9b91ea48fc11333d4ebd93e702959ea558318cb6d7849deb5387d4f4');
  assert.equal(image.candidateBaseRef, parent);
  assert.equal(image.knownRuntimeDependencyPolicy, C26_POLICY);
  assert.equal(image.dockerfile, dockerfilePath);
  assert.equal(image.dockerfileSha256, createHash('sha256').update(dockerfile).digest('hex'));
  assert.ok(dockerfile.includes(`\nFROM ${parent}\n`));
  assert.match(dockerfile, /^RUN --network=none \/opt\/keycloak\/bin\/kc\.sh build --db=postgres --health-enabled=true --metrics-enabled=true/m);
  assert.match(workflow, /^\s+- c35-keycloak$/m);
  assert.match(pom, /<vertx\.version>4\.5\.35<\/vertx\.version>/);
  assert.match(pom, /<artifactId>netty-bom<\/artifactId>\s*<version>4\.1\.139\.Final<\/version>/);
  assert.match(dockerfile, /otziv\.netty="4\.1\.139\.Final" otziv\.netty-tcnative="2\.0\.84\.Final"/);
  assert.match(dockerfile, /otziv\.netty-handler="4\.1\.139\.Final"/);

  assert.equal((dockerfile.match(/^ADD /gm) || []).length, reviewedJars.size + reviewedNettyJars.size, 'no missing or unreviewed downloads');
  for (const [artifact, checksum] of reviewedJars) {
    const addition = [
      `ADD --chown=1000:0 --chmod=0664 --checksum=sha256:${checksum} \\`,
      `    https://repo.maven.apache.org/maven2/io/vertx/${artifact}/4.5.35/${artifact}-4.5.35.jar \\`,
      `    /opt/keycloak/lib/lib/main/io.vertx.${artifact}-4.5.31.jar`,
    ].join('\n');
    assert.ok(dockerfile.includes(addition), `missing reviewed ${artifact} replacement`);
  }
  for (const [entry, checksum] of reviewedNettyJars) {
    const [artifact, classifier] = entry.split(':');
    const isTcnative = artifact === 'netty-tcnative-classes';
    const version = isTcnative ? '2.0.84.Final' : '4.1.139.Final';
    const oldVersion = isTcnative ? '2.0.78.Final' : '4.1.136.Final';
    const suffix = classifier ? '-' + classifier : '';
    const mode = artifact === 'netty-handler' ? '0644' : '0664';
    const addition = [
      'ADD --chown=1000:0 --chmod=' + mode + ' --checksum=sha256:' + checksum + ' \\',
      '    https://repo.maven.apache.org/maven2/io/netty/' + artifact + '/' + version + '/' + artifact + '-' + version + suffix + '.jar \\',
      '    /opt/keycloak/lib/lib/main/io.netty.' + artifact + '-' + oldVersion + suffix + '.jar',
    ].join('\n');
    assert.ok(dockerfile.includes(addition), 'missing reviewed ' + entry + ' replacement');
  }
});
