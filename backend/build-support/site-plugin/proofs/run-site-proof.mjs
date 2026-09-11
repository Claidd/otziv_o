/*
 * Copyright 2026 Otziv contributors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
import { spawn } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { access, mkdir, readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { connect } from 'node:net';
import { delimiter, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const VERSION = '3.22.0-otziv-jetty12.0.39-2';
const proofDirectory = dirname(fileURLToPath(import.meta.url));
const [mavenArg, javaArg, outputArg, repositoryArg] = process.argv.slice(2);
if (!mavenArg || !javaArg || !outputArg || ![5, 6].includes(process.argv.length)) {
  throw new Error('usage_maven_home_java_home_new_output_directory_optional_maven_repository');
}
const maven = resolve(mavenArg), javaHome = resolve(javaArg), output = resolve(outputArg);
const repositoryArguments = repositoryArg ? [`-Dmaven.repo.local=${resolve(repositoryArg)}`] : [];
if (repositoryArg) await access(resolve(repositoryArg));
const executable = name => join(javaHome, 'bin', `${name}${process.platform === 'win32' ? '.exe' : ''}`);
await access(executable('java')); await access(executable('javac')); await access(join(maven, 'bin', 'm2.conf'));
await mkdir(output); // Intentionally refuses an existing evidence directory.
const safeEnvironment = Object.fromEntries(['PATH','Path','SystemRoot','WINDIR','TEMP','TMP','HOME','USERPROFILE','APPDATA','LOCALAPPDATA']
  .filter(key => process.env[key] != null).map(key => [key, process.env[key]]));
safeEnvironment.JAVA_HOME = javaHome;
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const commandResults = [];
async function run(label, command, args, cwd, timeoutMs = 180_000) {
  const logPath = join(output, `${label}.log`), log = createWriteStream(logPath, { flags: 'wx' });
  const child = spawn(command, args, { cwd, env: safeEnvironment, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
  child.stdout.pipe(log, { end: false }); child.stderr.pipe(log, { end: false });
  let timeout = false;
  const timer = setTimeout(() => { timeout = true; child.kill(); }, timeoutMs);
  const code = await new Promise((resolve, reject) => { child.once('error', reject); child.once('close', resolve); })
    .finally(() => clearTimeout(timer));
  await new Promise(resolve => log.end(resolve));
  const bytes = await readFile(logPath);
  commandResults.push({ label, exitCode: code, timedOut: timeout, log: `${label}.log`, sha256: sha(bytes) });
  if (timeout || code !== 0) throw new Error(`proof_command_failed_${label}`);
  return bytes.toString('utf8');
}
const settings = '<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"><mirrors><mirror><id>central</id><mirrorOf>*</mirrorOf><url>https://repo.maven.apache.org/maven2</url></mirror></mirrors></settings>';
async function fixture(name, version) {
  const dir = join(output, name); await mkdir(join(dir, 'src', 'site', 'markdown'), { recursive: true });
  await mkdir(join(dir, 'src', 'site', 'resources'), { recursive: true });
  await writeFile(join(dir, 'proof-settings.xml'), settings);
  await writeFile(join(dir, 'pom.xml'), `<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>com.hunt.proof</groupId><artifactId>site-security-fixture</artifactId><version>1</version><name>Site security fixture</name><properties><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties><build><pluginManagement><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-site-plugin</artifactId><version>${version}</version><dependencies><dependency><groupId>org.jsoup</groupId><artifactId>jsoup</artifactId><version>1.23.2</version></dependency><dependency><groupId>commons-beanutils</groupId><artifactId>commons-beanutils</artifactId><version>1.11.0</version></dependency></dependencies></plugin></plugins></pluginManagement></build><reporting><excludeDefaults>true</excludeDefaults></reporting></project>`);
  await writeFile(join(dir, 'src', 'site', 'markdown', 'index.md'), '# Site security fixture\n\nORIGINAL_CONTENT\n\n[Second document](second.html)\n'.replaceAll('\\n','\n'));
  await writeFile(join(dir, 'src', 'site', 'markdown', 'second.md'), '# Second document\n\nINDEPENDENT_SECOND_DOCUMENT\n'.replaceAll('\\n','\n'));
  await writeFile(join(dir, 'src', 'site', 'resources', 'check.txt'), 'STATIC_RESOURCE_CONTENT\n'.replaceAll('\\n','\n'));
  return dir;
}
async function listenerClosed(port) {
  return new Promise(resolve => { const socket = connect({ host: '127.0.0.1', port });
    socket.once('connect', () => { socket.destroy(); resolve(false); });
    socket.once('error', () => { socket.destroy(); resolve(true); });
    socket.setTimeout(1000, () => { socket.destroy(); resolve(false); });
  });
}
let result;
try {
  const baseline = await fixture('baseline', '3.22.0'), candidate = await fixture('candidate', VERSION);
  const classes = join(output, 'classes'); await mkdir(classes);
  await run('compile-proof', executable('javac'), ['-cp', join(maven,'lib','*'), '-d', classes, join(proofDirectory,'SiteGoalProof.java')], output);
  const mavenArgs = [...repositoryArguments, `-Dmaven.home=${maven}`, `-Dmaven.multiModuleProjectDirectory=${candidate}`, `-Dclassworlds.conf=${join(maven,'bin','m2.conf')}`, '-cp', join(maven,'boot','*'), 'org.codehaus.plexus.classworlds.launcher.Launcher'];
  const staticLog = await run('bare-site', executable('java'), [...mavenArgs, '-B','-ntp','-X','-s',join(candidate,'proof-settings.xml'),'-gs',join(candidate,'proof-settings.xml'),'site'], candidate);
  if (!staticLog.includes(`maven-site-plugin:${VERSION}`) || !staticLog.includes('BUILD SUCCESS')) throw new Error('wrong_site_lifecycle_plugin');
  for (const [file, marker] of [['index.html','ORIGINAL_CONTENT'],['second.html','INDEPENDENT_SECOND_DOCUMENT'],['check.txt','STATIC_RESOURCE_CONTENT']]) {
    if (!(await readFile(join(candidate,'target','site',file),'utf8')).includes(marker)) throw new Error('static_site_output_missing');
  }
  const checks = [];
  for (const [name, path, fixed] of [['baseline', baseline, false], ['candidate', candidate, true]]) {
    const text = await run(`${name}-run`, executable('java'), [...repositoryArguments, `-Dmaven.home=${maven}`, '-cp', [classes,join(maven,'lib','*'),join(maven,'boot','*')].join(delimiter), 'SiteGoalProof', path, String(fixed)], output);
    const names = [...text.matchAll(/PROOF_CHECK (.+)/g)].map(match=>match[1]);
    const port = Number([...text.matchAll(/Started Jetty on.*http:\/\/127\.0\.0\.1:(\d+)/g)].at(-1)?.[1]);
    if (names.length !== 7 || !text.includes('PROOF_COMPLETE checks=7') || !Number.isSafeInteger(port) || !(await listenerClosed(port)) || !/Stopped.*Server/.test(text) || /NoClassDefFoundError|ClassNotFoundException|Exception in thread/.test(text.slice(text.indexOf('PROOF_COMPLETE')))) throw new Error('runtime_or_shutdown_proof_incomplete');
    checks.push({ name, checks: names, chunkObservation: [...text.matchAll(/PROOF_CHUNK (.+)/g)].at(-1)?.[1], listenerClosed: true, shutdownLogged: true });
  }
  result = { schema: 'otziv-site-goal-proof-v1', result: 'PASS', staticOutputChecks: 3, liveChecks: 14, checks, commands: commandResults,
    limits: ['Only fresh loopback fixtures are sent HTTP requests.', 'This does not run Dependency-Check or Sonatype.', 'The downstream module must be installed first.'] };
} catch (error) {
  result = { schema: 'otziv-site-goal-proof-v1', result: 'FAIL', code: /^[a-z_]+$/.test(error.message || '') ? error.message : 'proof_failed', commands: commandResults };
  process.exitCode = 1;
}
await writeFile(join(output,'proof.json'), JSON.stringify(result,null,2)+'\n');
console.log(JSON.stringify({result:result.result,staticOutputChecks:result.staticOutputChecks,liveChecks:result.liveChecks,code:result.code}));
