import {mkdtemp,mkdir,writeFile,copyFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {randomBytes} from 'node:crypto';
import {run} from '../recovery/process.mjs';

const root=fileURLToPath(new URL('../../',import.meta.url));
const directory=await mkdtemp(join(tmpdir(),'otziv-publisher-compose-'));
try{
  for(const folder of ['tls','mysql','postgres'])await mkdir(join(directory,folder));
  await copyFile(join(root,'infrastructure/monitoring/publisher-config.example.json'),join(directory,'publisher.json'));
  await writeFile(join(directory,'secrets.env'),'MONITOR_SIGNALS_TOKEN='+randomBytes(32).toString('base64url')+'\n',{mode:0o600});
  const env={...process.env,SIGNALS_PUBLISHER_IMAGE:'otziv-publisher:local-fixture',OTZIV_MONITORING_SHARED_SECRET:randomBytes(32).toString('base64url'),
    MONITOR_PUBLISHER_SECRETS_FILE:join(directory,'secrets.env'),MONITOR_PUBLISHER_CONFIG_FILE:join(directory,'publisher.json'),
    MONITOR_PUBLISHER_TLS_DIRECTORY:join(directory,'tls'),MYSQL_BACKUP_RECEIPTS_DIRECTORY:join(directory,'mysql'),POSTGRES_BACKUP_RECEIPTS_DIRECTORY:join(directory,'postgres')};
  const command=['compose','--env-file',join(root,'.env.prod.example'),'-f',join(root,'docker-compose.yaml'),'-f',join(root,'compose.monitoring.yaml'),'--profile','monitor-signals','config','--quiet'];
  await run('docker',command,{env,timeoutMs:30000});
  let refused=false;
  try{await run('docker',command,{env:{...env,OTZIV_MONITORING_SHARED_SECRET:''},timeoutMs:30000});}catch{refused=true;}
  if(!refused)throw new Error('publisher_missing_config_accepted');
  console.log(JSON.stringify({result:'PASS',explicitFixtureConfiguration:true,missingSecretRefused:true,servicesStarted:false}));
}finally{await rm(directory,{recursive:true,force:true});}
