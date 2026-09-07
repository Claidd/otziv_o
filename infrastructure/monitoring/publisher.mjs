import https from 'node:https';
import { readFile, stat } from 'node:fs/promises';
import { createHash,timingSafeEqual } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { collectSignals,validateCollector } from './collect.mjs';

export async function createPublisher(config,env=process.env,{collect=collectSignals,now=Date.now}={}) {
  validateCollector(config,env);
  const token=env.MONITOR_SIGNALS_TOKEN;
  if (!token || token.length<32 || token.length>512) throw new Error('publisher_secret_invalid');
  if (!Number.isInteger(config.port) || config.port<1 || config.port>65535 || !['127.0.0.1','0.0.0.0','::1'].includes(config.bindAddress)) throw new Error('publisher_listener_invalid');
  for (const path of [config.tlsKeyFile,config.tlsCertFile]) {
    if (!path) throw new Error('publisher_tls_file_invalid');
    const info=await stat(path);
    if(!info.isFile()||info.size>65536)throw new Error('publisher_tls_file_invalid');
  }
  if (process.platform!=='win32' && ((await stat(config.tlsKeyFile)).mode&0o077)) throw new Error('publisher_tls_key_permissions_invalid');
  const digest=value=>createHash('sha256').update(value).digest(), expected=digest(`Bearer ${token}`);
  let snapshot=null,lastSuccess=0,running=false,stopping=false;
  const sample=async()=>{
    if(running||stopping)return;
    running=true;
    const watchdog=setTimeout(()=>{snapshot=null;},config.sourceTimeoutMs+1000);
    let expired=false;
    const deadline=setTimeout(()=>{expired=true;},config.sourceTimeoutMs+1000);
    try {const value=await collect(config,env);if(!expired){snapshot=value;lastSuccess=now();}}
    catch {snapshot=null;} finally {clearTimeout(watchdog);clearTimeout(deadline);running=false;}
  };
  const server=https.createServer({key:await readFile(config.tlsKeyFile),cert:await readFile(config.tlsCertFile),minVersion:'TLSv1.2',maxHeaderSize:8192},(req,res)=>{
    res.setHeader('Cache-Control','no-store');
    if(req.method!=='GET'||req.url!=='/private/monitor-signals'){res.writeHead(404).end();return;}
    const provided=req.headers.authorization||'';
    if(typeof provided!=='string'||provided.length>520||!timingSafeEqual(expected,digest(provided))){res.writeHead(401).end();return;}
    if(!snapshot||now()-lastSuccess>config.maxSourceAgeSeconds*1000){res.writeHead(503).end();return;}
    const body=JSON.stringify(snapshot);
    if(Buffer.byteLength(body)>65536){res.writeHead(503).end();return;}
    res.writeHead(200,{'Content-Type':'application/json'}).end(body);
  });
  server.requestTimeout=10000;server.headersTimeout=5000;server.keepAliveTimeout=1000;server.maxConnections=32;
  const timer=setInterval(()=>void sample(),config.intervalMs);timer.unref();
  // Listen with 503 during startup. A stuck collection retains its only slot;
  // it never accumulates overlapping collectors or publishes late stale success.
  void sample();
  return {server,sample,close:async()=>{stopping=true;clearInterval(timer);server.closeIdleConnections();await new Promise(resolve=>server.close(resolve));}};
}
if(process.argv[1]===fileURLToPath(import.meta.url)) {
  try {
    if(process.argv.length!==3)throw new Error('publisher_config_path_required');
    const config=JSON.parse(await readFile(process.argv[2],'utf8'));
    const publisher=await createPublisher(config);
    await new Promise((resolve,reject)=>publisher.server.once('error',reject).listen(config.port,config.bindAddress,resolve));
    for(const signal of ['SIGTERM','SIGINT'])process.once(signal,()=>{void publisher.close().then(()=>process.exit(0));setTimeout(()=>process.exit(1),10000).unref();});
    console.log(JSON.stringify({event:'signals_publisher_started',tls:true}));
  }catch{console.error(JSON.stringify({result:'FAIL',code:'signals_publisher_startup_failed'}));process.exitCode=1;}
}
