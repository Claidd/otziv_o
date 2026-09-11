# Local disposable replay helpers. No SSH or source capture operations.
import base64, datetime, hashlib, json, pathlib, subprocess, sys, time, uuid

CRITICAL = {
 'realm':['id','name','enabled','ssl_required'],
 'user_entity':['id','username','email','enabled','email_verified','realm_id','first_name','last_name','federation_link','service_account_client_link'],
 'client':['id','client_id','realm_id','enabled','protocol','public_client','secret','service_accounts_enabled','standard_flow_enabled','direct_access_grants_enabled'],
 'credential':['id','user_id','type','created_date','user_label','secret_data','credential_data','priority'],
 'keycloak_role':['id','name','realm_id','client','client_role'],
 'user_role_mapping':['user_id','role_id'],
}

TABLES_SQL = r"""SELECT format('SELECT jsonb_build_object(''table'',%L,''rows'',count(*),''hash'',md5(coalesce(string_agg(md5(to_jsonb(t)::text),'''' ORDER BY md5(to_jsonb(t)::text)),''''))) FROM %I.%I t;',tablename,schemaname,tablename) FROM pg_tables WHERE schemaname='public' ORDER BY tablename;"""

INVENTORY_SQL = "\nSELECT jsonb_build_object(\n  'serverVersion',current_setting('server_version'),\n  'serverVersionNumber',current_setting('server_version_num'),\n  'encoding',current_setting('server_encoding'),\n  'queryReadOnly',current_setting('default_transaction_read_only'),\n  'locale',(SELECT jsonb_build_object('provider',datlocprovider,'collate',datcollate,'ctype',datctype,\n    'recordedVersion',datcollversion,'actualVersion',pg_database_collation_actual_version(oid),\n    'providerLocale',to_jsonb(d)->>'datlocale') FROM pg_database d WHERE datname=current_database()),\n  'databaseBytes',pg_database_size(current_database()),\n  'nonTemplateDatabases',(SELECT jsonb_agg(jsonb_build_object('oid',oid,'encoding',pg_encoding_to_char(encoding),\n    'provider',datlocprovider,'collate',datcollate,'ctype',datctype,'recordedVersion',datcollversion,\n    'actualVersion',pg_database_collation_actual_version(oid),'bytes',pg_database_size(oid)) ORDER BY oid)\n    FROM pg_database WHERE NOT datistemplate),\n  'extensions',(SELECT jsonb_agg(jsonb_build_object('name',extname,'version',extversion) ORDER BY extname) FROM pg_extension),\n  'availableExtensions',(SELECT jsonb_agg(name ORDER BY name) FROM pg_available_extensions),\n  'roleCounts',(SELECT jsonb_build_object('total',count(*),'login',count(*) FILTER(WHERE rolcanlogin),\n    'superuser',count(*) FILTER(WHERE rolsuper),'replication',count(*) FILTER(WHERE rolreplication)) FROM pg_roles),\n  'schemaCounts',(SELECT jsonb_build_object('total',count(*),'application',count(*) FILTER(\n    WHERE nspname NOT LIKE 'pg_%' AND nspname<>'information_schema')) FROM pg_namespace),\n  'applicationObjects',(SELECT jsonb_build_object('tables',count(*) FILTER(WHERE c.relkind IN ('r','p')),\n    'indexes',count(*) FILTER(WHERE c.relkind IN ('i','I')),'sequences',count(*) FILTER(WHERE c.relkind='S'),\n    'views',count(*) FILTER(WHERE c.relkind IN ('v','m')),'foreignTables',count(*) FILTER(WHERE c.relkind='f'),\n    'unloggedTables',count(*) FILTER(WHERE c.relkind IN ('r','p') AND c.relpersistence='u'))\n    FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace\n    WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname<>'information_schema'),\n  'usedCollations',(SELECT jsonb_agg(jsonb_build_object('oid',c.oid,'catalogName',CASE WHEN n.nspname='pg_catalog' THEN c.collname ELSE NULL END,\n    'custom',n.nspname<>'pg_catalog','provider',c.collprovider,'deterministic',c.collisdeterministic,\n    'recordedVersion',c.collversion,'actualVersion',pg_collation_actual_version(c.oid)) ORDER BY c.oid)\n    FROM pg_collation c JOIN pg_namespace n ON n.oid=c.collnamespace WHERE c.oid IN (\n      SELECT a.attcollation FROM pg_attribute a JOIN pg_class t ON t.oid=a.attrelid JOIN pg_namespace ns ON ns.oid=t.relnamespace\n      WHERE a.attnum>0 AND NOT a.attisdropped AND ns.nspname NOT LIKE 'pg_%' AND ns.nspname<>'information_schema'\n      UNION SELECT unnest(i.indcollation::oid[]) FROM pg_index i JOIN pg_class t ON t.oid=i.indrelid JOIN pg_namespace ns ON ns.oid=t.relnamespace\n      WHERE ns.nspname NOT LIKE 'pg_%' AND ns.nspname<>'information_schema')),\n  'settings',(SELECT jsonb_object_agg(name,setting) FROM pg_settings WHERE name IN (\n    'wal_level','archive_mode','max_wal_senders','max_replication_slots','max_connections','wal_log_hints',\n    'data_checksums','lc_messages','lc_monetary','lc_numeric','lc_time','TimeZone','jit','jit_above_cost')),\n  'preloadConfigured',current_setting('shared_preload_libraries')<>'',\n  'localPreloadConfigured',current_setting('local_preload_libraries')<>'',\n  'sessionPreloadConfigured',current_setting('session_preload_libraries')<>'',\n  'archiveCommandConfigured',current_setting('archive_command') NOT IN ('','(disabled)'),\n  'jitAvailable',pg_jit_available(),\n  'inRecovery',pg_is_in_recovery(),\n  'replication',(SELECT jsonb_build_object('slots',count(*),'logicalSlots',count(*) FILTER(WHERE slot_type='logical'),\n    'physicalSlots',count(*) FILTER(WHERE slot_type='physical'),'activeSlots',count(*) FILTER(WHERE active)) FROM pg_replication_slots),\n  'replicationConnections',(SELECT count(*) FROM pg_stat_replication),\n  'subscriptions',(SELECT count(*) FROM pg_subscription),\n  'publications',(SELECT count(*) FROM pg_publication),\n  'customTablespaces',(SELECT count(*) FROM pg_tablespace WHERE spcname NOT IN ('pg_default','pg_global')),\n  'foreignServers',(SELECT count(*) FROM pg_foreign_server),\n  'databaseRoleSettings',(SELECT count(*) FROM pg_db_role_setting),\n  'clusterIdentifier',(SELECT system_identifier::text FROM pg_control_system()),\n  'configSha256',encode(sha256(convert_to(pg_read_file(current_setting('config_file')),'UTF8')),'hex'),\n  'hbaSha256',encode(sha256(convert_to(pg_read_file(current_setting('hba_file')),'UTF8')),'hex'),\n  'identSha256',encode(sha256(convert_to(pg_read_file(current_setting('ident_file')),'UTF8')),'hex')\n);\n"

def check(name,value):
    if not value: raise RuntimeError(name)
    REPORT['checks'].append({'name':name,'passed':True}); print('PASS '+name,flush=True)

def digest(value): return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':')).encode()).hexdigest()

def run(args,input=None,allow=False,timeout=180):
    p=subprocess.run(args,input=input,capture_output=True,timeout=timeout)
    if p.returncode and not allow:
        (PRIVATE/(stage+'-command-error.log')).write_bytes(p.stderr)
        raise RuntimeError(stage+'_command_failed')
    return p

def docker(args,input=None,allow=False,timeout=180): return run(['docker',*args],input,allow,timeout)

def inspect(name,kind='container'): return json.loads(docker([kind,'inspect',name]).stdout)[0]

def acl(path):
    command="$ErrorActionPreference='Stop'; $a=Get-Acl -LiteralPath '"+str(path).replace("'","''")+"'; $sid=[System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value; $x=@($a.Access | ForEach-Object {$_.IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value}); if(@($x | Where-Object {$_ -ne $sid -and $_ -ne 'S-1-5-18'}).Count -or $x.Count -lt 2){exit 12}; Write-Output 'PASS'"
    p=subprocess.run(['pwsh','-NoProfile','-EncodedCommand',base64.b64encode(command.encode('utf-16le')).decode()],capture_output=True)
    if p.returncode: raise RuntimeError('private_acl_not_exclusive')

def critical_sql():
    statements=[]
    for table,cols in CRITICAL.items():
        projection='jsonb_build_object('+','.join("'"+c+"',to_jsonb(t)->'"+c+"'" for c in cols)+')'
        statements.append("SELECT jsonb_build_object('table','"+table+"','rows',count(*),'hash',encode(sha256(convert_to(coalesce(string_agg(encode(sha256(convert_to("+projection+"::text,'UTF8')),'hex'),'' ORDER BY encode(sha256(convert_to("+projection+"::text,'UTF8')),'hex')),''),'UTF8')),'hex')) FROM public."+table+' t;')
    return '\n'.join(statements)

def local_sql(pg,text,database=None):
    return docker(['exec','-i',pg,'sh','-c','exec psql -X -q -A -t -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "${1:-$POSTGRES_DB}"','sh',database or ''],text.encode()).stdout.decode().strip()

def local_tables(pg):
    return [json.loads(x) for x in local_sql(pg,local_sql(pg,TABLES_SQL)).splitlines() if x.strip()]

def local_critical(pg): return [json.loads(x) for x in local_sql(pg,critical_sql()).splitlines() if x.strip()]

def start_pg(suffix,image,volume,envfile):
    name=OWNER+'-'+suffix
    for other in containers:
        obj=inspect(other)
        if obj['State']['Running'] and any(m.get('Name')==volume for m in obj.get('Mounts',[])):raise RuntimeError('concurrent_volume_writer')
    docker(['run','-d','--name',name,'--label',LABEL+'='+OWNER,'--network',network,'--network-alias','pg','--memory','512m','--cpus','1','--pids-limit','160','--env-file',str(envfile),'--mount','type=volume,source='+volume+',target=/var/lib/postgresql/data',image]);containers.append(name)
    for _ in range(120):
        if not inspect(name)['State']['Running']:raise RuntimeError('postgres_early_exit')
        result=docker(['exec',name,'sh','-c','test "$(cat /proc/1/comm)" = postgres && pg_isready -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],allow=True)
        if result.returncode==0:return name
        time.sleep(.5)
    raise RuntimeError('postgres_readiness_timeout')

def stop(name):
    docker(['stop','--time','30',name]);state=inspect(name)['State']
    allowed=[0,143] if 'kc' in name.removeprefix(OWNER+'-') else [0]
    check('clean_stop_'+name.removeprefix(OWNER+'-'),not state['Running'] and not state['OOMKilled'] and state['ExitCode'] in allowed)
    # Remove stopped network endpoint so alias pg resolves to the sole writer.
    docker(['network','disconnect',network,name])

def restore(pg,dump):
    with dump.open('rb') as source:
        p=subprocess.run(['docker','exec','-i',pg,'sh','-c','exec pg_restore --exit-on-error --single-transaction -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],stdin=source,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=180)
    if p.returncode:
        (PRIVATE/(stage+'-restore.stderr')).write_bytes(p.stderr);raise RuntimeError('restore_failed')

def request(runner,url,options=None):
    bridge="let s='';for await(const c of process.stdin)s+=c;const q=JSON.parse(s);const r=await fetch(q.url,{...q.options,redirect:'manual',signal:AbortSignal.timeout(3000)});console.log(JSON.stringify({status:r.status,text:await r.text()}));"
    p=docker(['exec','-i',runner,'node','--input-type=module','-e',bridge],json.dumps({'url':url,'options':options or {}}).encode(),allow=True,timeout=10)
    if p.returncode: return None
    return json.loads(p.stdout)

def start_kc(suffix,image,envfile,runner,admin):
    name=OWNER+'-'+suffix
    args=['run','-d','--name',name,'--label',LABEL+'='+OWNER,'--network',network,'--memory','1536m','--cpus','1','--pids-limit','320','--env-file',str(envfile),image,'start']
    if image==NEW_KC:args+=['--optimized']
    args+=['--http-enabled=true','--hostname-strict=false']
    docker(args);containers.append(name)
    base='http://'+name+':8080'
    for _ in range(120):
        if not inspect(name)['State']['Running']:raise RuntimeError('keycloak_early_exit_'+suffix)
        r=request(runner,base+'/realms/master/.well-known/openid-configuration')
        if r and r['status']==200:break
        time.sleep(.5)
    else: raise RuntimeError('keycloak_readiness_timeout_'+suffix)
    check(suffix+'_oidc_discovery',True)
    health=request(runner,'http://'+name+':9000/health/ready')
    check(suffix+'_health_ready',health and health['status']==200 and json.loads(health['text']).get('status')=='UP')
    checks={'adminCredentialsAvailable':bool(admin.get('username') and admin.get('password')),'adminRead':False}
    if checks['adminCredentialsAvailable']:
        import urllib.parse
        body=urllib.parse.urlencode({'grant_type':'password','client_id':'admin-cli','username':admin['username'],'password':admin['password']})
        response=request(runner,base+'/realms/master/protocol/openid-connect/token',{'method':'POST','headers':{'Content-Type':'application/x-www-form-urlencoded'},'body':body})
        if response and response['status']==200:
            token=json.loads(response['text'])['access_token']
            realms=request(runner,base+'/admin/realms',{'headers':{'Authorization':'Bearer '+token}})
            check(suffix+'_existing_admin_read',realms and realms['status']==200)
            checks['adminRead']=True;checks['realmCount']=len(json.loads(realms['text']))
        else:checks['adminCredentialStatus']=response['status'] if response else 'unavailable'
    REPORT.setdefault('keycloakChecks',{})[suffix]=checks
    return name
