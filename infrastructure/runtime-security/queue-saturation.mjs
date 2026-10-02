import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {createHash} from 'node:crypto';
import {readFile,writeFile,mkdir,readdir,access} from 'node:fs/promises';
import {delimiter,resolve,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';

// Opt-in manual runner. Compiles the TWO ACTUAL current repositories independently;
// it never uses a source-grep SELECT approximation or changes a Maven test target.
const root=resolve(dirname(fileURLToPath(import.meta.url)),'../..');
const args=process.argv.slice(2),value=name=>{const index=args.indexOf(name);return index<0?undefined:args[index+1];};
assert.ok(args.includes('--confirm-local-synthetic'),'Pass --confirm-local-synthetic to create disposable local MySQL');
const report=resolve(value('--classpath-report')||''),out=resolve(value('--output')||'');
assert.ok(value('--classpath-report')&&value('--output'),'Explicit --classpath-report and fresh --output directory are required');
try{await access(out);throw Error('Output directory already exists; preserve previous evidence');}catch(error){if(error.code!=='ENOENT')throw error;}
const javaHome=value('--java-home')||process.env.JAVA_HOME;assert.ok(javaHome,'A JDK26 --java-home is required');
const binary=name=>resolve(javaHome,'bin',process.platform==='win32'?name+'.exe':name);
const run=(command,parameters,log)=>new Promise((done,reject)=>{
  const child=spawn(command,parameters,{cwd:root,windowsHide:true,stdio:['ignore','pipe','pipe']});let text='';
  child.stdout.on('data',data=>{text+=data;});child.stderr.on('data',data=>{text+=data;});
  child.on('error',reject);child.on('close',async code=>{if(log)await writeFile(log,text);code===0?done(text):reject(Error('Command failed ('+code+'); see '+(log||command)));});
});
const context=(await run('docker',['context','inspect','--format','{{.Endpoints.docker.Host}}'])).trim();
assert.match(context,/^(npipe|unix):\/\//,'Only local Docker is supported');
const version=await run(binary('java'),['-version']);assert.match(version,/version "26\./,'JDK26 is required');
await mkdir(out,{recursive:true});await mkdir(resolve(out,'classes'));
await writeFile(resolve(out,'docker-stats-before.txt'),await run('docker',['stats','--no-stream','--format','{{.Name}} {{.MemUsage}} {{.CPUPerc}}']));
const xml=await readFile(report,'utf8');
const classpath=xml.match(/<property\s+name="java\.class\.path"\s+value="([^"]+)"\s*\/>/)?.[1]
  ?.replaceAll('&quot;','"').replaceAll('&amp;','&').replaceAll('&lt;','<').replaceAll('&gt;','>');
assert.ok(classpath,'Surefire java.class.path missing');
const lombok=classpath.split(delimiter).find(path=>/[/\\]lombok-[^/\\]+\.jar$/.test(path));assert.ok(lombok,'Lombok processor path missing');
const files=[
  'backend/src/main/java/com/hunt/otziv/l_lead/repository/LeadCommandRepository.java',
  'backend/src/main/java/com/hunt/otziv/performers/repository/PerformerNotificationRepository.java',
  'infrastructure/runtime-security/QueueSaturationBenchmark.java',
  'infrastructure/runtime-security/queue-saturation.mjs',
];
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const manifest=[];for(const path of files)manifest.push({path,sha256:hash(await readFile(resolve(root,path)))});
const quote=value=>'"'+value.replaceAll('\\','\\\\').replaceAll('"','\\"')+'"';
const compiled=resolve(out,'classes');
const javacArgs=['--release','26','-classpath',classpath,'-processorpath',lombok,'-processor','lombok.launch.AnnotationProcessorHider$AnnotationProcessor','-d',compiled,...files.slice(0,3).map(path=>resolve(root,path))];
await writeFile(resolve(out,'javac.args'),javacArgs.map(quote).join('\n'));
await run(binary('javac'),['@'+resolve(out,'javac.args')],resolve(out,'compile.log'));
const runtimeArgs=['-Xmx512m','-classpath',compiled+delimiter+classpath,'QueueSaturationBenchmark',root,out,...(args.includes('--quick')?['quick']:[])];
await writeFile(resolve(out,'java.args'),runtimeArgs.map(quote).join('\n'));
const classes=[];
async function scan(path){for(const entry of await readdir(path,{withFileTypes:true})){const file=resolve(path,entry.name);if(entry.isDirectory())await scan(file);else classes.push({path:file.slice(compiled.length+1).replaceAll('\\','/'),sha256:hash(await readFile(file))});}}
await scan(compiled);
await writeFile(resolve(out,'manifest.json'),JSON.stringify({schema:'otziv-queue-saturation-code-v1',createdAt:new Date().toISOString(),java:version.trim(),source:manifest,compiled:classes,
  classpathReport:report,classpathReportSha256:hash(await readFile(report)),profile:args.includes('--quick')?'quick':'standard',production:false},null,2));
await run(binary('java'),['@'+resolve(out,'java.args')],resolve(out,'run.log'));
const result=JSON.parse(await readFile(resolve(out,'result.json'),'utf8'));
assert.equal(result.result,'PASS');assert.equal(result.scenarios.length,6);
const leftovers=(await run('docker',['ps','--all','--filter','id='+result.ownedContainer,'--format','{{.ID}}'])).trim();
assert.equal(leftovers,'','Owned MySQL container was not removed');
result.cleanup='OWNED_MYSQL_REMOVED_VERIFIED';await writeFile(resolve(out,'result.json'),JSON.stringify(result,null,2));
console.log(JSON.stringify({result:result.result,scenarios:result.scenarios.length,output:out}));
