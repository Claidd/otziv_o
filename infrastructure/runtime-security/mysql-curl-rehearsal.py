"""Isolated same-engine OS refresh and rollback rehearsal. Never contacts production.

Only hashes, counts and image identities are exported. Named resources are uniquely
owned; source dump remains private and untouched. No ports or network are exposed.
"""
import argparse, datetime, gzip, hashlib, json, re, secrets, subprocess, time, uuid
from pathlib import Path

OLD = 'ghcr.io/claidd/otziv-security@sha256:c810a0bd4902a4791824bce7795f95f97743506a4263889a1008076e6e9630b0'
LABEL = 'com.otziv.mysql-refresh.owner'
FLAGS = ['mysqld', '--user=999', '--character-set-server=utf8mb4', '--collation-server=utf8mb4_unicode_ci',
         '--default-time-zone=+08:00', '--restrict-fk-on-non-standard-key=OFF', '--gtid-mode=OFF',
         '--enforce-gtid-consistency=OFF', '--log-bin=mysql-bin', '--binlog-format=ROW',
         '--event-scheduler=OFF', '--innodb-buffer-pool-size=536870912', '--binlog-expire-logs-seconds=604800']
def sha(b): return hashlib.sha256(b).hexdigest()
def call(args, data=None, timeout=180):
    p = subprocess.run(['docker', *args], input=data, capture_output=True, timeout=timeout)
    if p.returncode: raise RuntimeError('docker_' + args[0] + '_failed')
    return p.stdout
def inspect(name): return json.loads(call(['inspect',name]))[0]
def sql(name, text):
    return call(['exec','-i',name,'mysql','-uroot','--batch','--skip-column-names','otziv'],text.encode(),600)
def snapshot(name):
    tables=sql(name,"SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='otziv' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME;").decode().splitlines()
    assert tables and all(re.fullmatch('[A-Za-z0-9_]+',t) for t in tables)
    counts=sql(name,' UNION ALL '.join("SELECT '"+t+"',COUNT(*) FROM `"+t+'`' for t in tables)+';')
    checksums=sql(name,'CHECKSUM TABLE '+','.join('`'+t+'`' for t in tables)+' EXTENDED;')
    assert b'NULL' not in checksums
    return {'tableCount':len(tables),'rows':sum(int(x.split(b'\t')[1]) for x in counts.splitlines()),
            'countsSha256':sha(counts),'checksumsSha256':sha(checksums),
            'historySha256':sha(sql(name,'SELECT * FROM flyway_schema_history ORDER BY installed_rank;')),
            'schemaSha256':sha(sql(name,"SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,IFNULL(COLUMN_DEFAULT,'NULL'),EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='otziv' ORDER BY TABLE_NAME,ORDINAL_POSITION;")),
            'accountsSha256':sha(sql(name,'SELECT User,Host,plugin,authentication_string FROM mysql.user ORDER BY User,Host;')),
            'settingsSha256':sha(sql(name,"SHOW GLOBAL VARIABLES WHERE Variable_name IN ('version','character_set_server','collation_server','sql_mode','time_zone','lower_case_table_names','gtid_mode','enforce_gtid_consistency','log_bin','binlog_format','event_scheduler','restrict_fk_on_non_standard_key');"))}

def run(args):
    assert re.fullmatch(r'ghcr.io/claidd/otziv-security@sha256:[a-f0-9]{64}',args.candidate)
    assert args.candidate != OLD
    dump=Path(args.dump).resolve(); assert dump.is_file()
    owner='otziv-mysql-refresh-'+uuid.uuid4().hex
    volumes=[]; containers=[]; checks=[]
    result={'schema':'otziv-mysql-same-engine-refresh-v1','source':OLD,'candidate':args.candidate,
            'production':False,'sourceWrites':False,'publishedPorts':0,'network':'none',
            'sourceDumpSha256':sha(dump.read_bytes()),'executedSourceSha256':sha(Path(__file__).read_bytes()),
            'startedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'checks':checks}
    def check(name,condition):
        assert condition,name
        checks.append({'name':name,'passed':True});print('PASS '+name,flush=True)
    def create_volume(suffix):
        name=owner+'-'+suffix;call(['volume','create','--label',LABEL+'='+owner,name]);volumes.append(name);return name
    def remove(name):
        assert inspect(name)['Config']['Labels'].get(LABEL)==owner
        call(['rm','-f',name]);containers.remove(name)
    def start(image,vol,suffix):
        name=owner+'-'+suffix
        call(['run','-d','--name',name,'--label',LABEL+'='+owner,'--network','none','--user','999:999',
              '--memory','1536m','--cpus','2','--mount','type=volume,src='+vol+',dst=/var/lib/mysql,volume-nocopy',
              '--mount','type=volume,src='+health_volume+',dst=/var/lib/mysql-files,volume-nocopy',
              '--tmpfs','/var/run/mysqld:rw,noexec,nosuid,size=16m,uid=999,gid=999,mode=0755',image,*FLAGS]);containers.append(name)
        for _ in range(120):
            if not inspect(name)['State']['Running']:
                diagnostic=subprocess.run(['docker','logs','--tail','12',name],capture_output=True)
                raise RuntimeError('mysql_start_exited: '+(diagnostic.stdout+diagnostic.stderr).decode())
            try:
                call(['exec',name,'mysql','-uroot','-e','SELECT 1']);break
            except RuntimeError: time.sleep(1)
        else: raise RuntimeError('mysql_start_timeout')
        meta=inspect(name)
        check('native_uid_no_network_'+suffix,meta['Config']['User']=='999:999' and meta['HostConfig']['NetworkMode']=='none' and not meta['HostConfig']['PortBindings'])
        return name
    def initialize(vol,suffix):
        name=owner+'-'+suffix
        call(['run','--name',name,'--label',LABEL+'='+owner,'--network','none','--mount','type=volume,src='+vol+',dst=/var/lib/mysql,volume-nocopy',
              '--entrypoint','sh',OLD,'-c','chown 999:999 /var/lib/mysql && mysqld --initialize-insecure --user=999 && chown -R 999:999 /var/lib/mysql'],timeout=180)
        containers.append(name);remove(name)
    def stop(name):
        call(['stop','--time','120',name],timeout=140)
        state=inspect(name)['State'];check('clean_stop_'+name.rsplit('-',1)[1],state['ExitCode']==0 and not state['OOMKilled'])
        remove(name)
    try:
        for key,image in [('source',OLD),('candidate',args.candidate)]:
            meta=json.loads(call(['image','inspect',image]))[0]
            assert image in meta['RepoDigests'] and meta['Os']=='linux' and meta['Architecture']=='amd64'
            result[key+'LocalId']=meta['Id']
            binary=call(['run','--rm','--network','none','--entrypoint','sh',image,'-c','sha256sum /usr/sbin/mysqld; rpm -q openssl-libs; mysqld --version'])
            lines=binary.decode().splitlines();result[key+'MysqldSha256']=lines[0].split()[0];result[key+'OpenSSL']=lines[1]
            result[key+'Libevent']=call(['run','--rm','--network','none','--entrypoint','rpm',image,'-q','libevent']).decode().strip()
            result[key+'Curl']=call(['run','--rm','--network','none','--entrypoint','rpm',image,'-q','curl','libcurl']).decode().splitlines()
            packages=call(['run','--rm','--network','none','--entrypoint','rpm',image,'-qa']).decode().splitlines()
            result[key+'OtherPackagesSha256']=sha(('\n'.join(sorted(x for x in packages if not x.startswith(('curl-', 'libcurl-'))))).encode())
            check('engine_version_'+key,'Ver 9.7.3' in lines[2])
        check('server_binary_unchanged',result['sourceMysqldSha256']==result['candidateMysqldSha256'])
        check('openssl_fixed',result['candidateOpenSSL']=='openssl-libs-3.5.8-1.0.1.el9_8.x86_64')
        check('libevent_fixed',result['candidateLibevent']=='libevent-2.1.13-1.el9_8.x86_64')
        check('curl_libraries_fixed',sorted(result['candidateCurl'])==['curl-7.76.1-40.el9_8.7.x86_64','libcurl-7.76.1-40.el9_8.7.x86_64'])
        check('only_curl_libraries_changed',result['sourceOtherPackagesSha256']==result['candidateOtherPackagesSha256'])
        health_volume=create_volume('health')
        call(['run','--rm','--network','none','--mount','type=volume,src='+health_volume+',dst=/var/lib/mysql-files,volume-nocopy','--entrypoint','chown',OLD,'999:999','/var/lib/mysql-files'])
        volume=create_volume('data');initialize(volume,'init');name=start(OLD,volume,'source')
        call(['exec',name,'mysql','-uroot','-e','CREATE DATABASE otziv CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;'])
        with gzip.open(dump,'rb') as source:
            proc=subprocess.Popen(['docker','exec','-i',name,'mysql','-uroot','otziv'],stdin=subprocess.PIPE,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
            try:
                while chunk:=source.read(1024*1024): proc.stdin.write(chunk)
                proc.stdin.close();error=proc.stderr.read();assert proc.wait(timeout=600)==0,'private_dump_restore_failed'
            except BaseException: proc.kill();raise
        password=secrets.token_hex(24)
        sql(name,"CREATE USER IF NOT EXISTS 'hunt'@'%' IDENTIFIED BY '"+password+"'; GRANT ALL PRIVILEGES ON otziv.* TO 'hunt'@'%';")
        before=snapshot(name);result['before']=before
        check('restored_nonempty_production_schema',before['tableCount']>100 and before['rows']>0)
        stop(name);name=start(args.candidate,volume,'candidate')
        after=snapshot(name);result['after']=after;check('all_tables_schema_history_accounts_and_settings_exact',after==before)
        call(['exec','-e','MYSQL_PWD='+password,name,'mysql','--protocol=TCP','-h127.0.0.1','-uhunt','otziv','-e','SELECT 1'])
        check('existing_account_login',True)
        sql(name,"CREATE TABLE otziv_refresh_canary(id INT PRIMARY KEY,payload JSON NOT NULL); INSERT INTO otziv_refresh_canary VALUES(1,JSON_OBJECT('value','post-refresh'));")
        written=snapshot(name);stop(name);name=start(args.candidate,volume,'restart')
        check('restart_preserves_new_writes',snapshot(name)==written)
        # A fresh dump is kept in memory; it is never exported into evidence.
        recovery=call(['exec',name,'mysqldump','-uroot','--single-transaction','--routines','--triggers','--events','--hex-blob','--set-gtid-purged=OFF','otziv'],timeout=600)
        stop(name);name=start(OLD,volume,'rollback')
        check('same_volume_rollback_preserves_all_new_writes',snapshot(name)==written);stop(name)
        fresh=create_volume('restore');initialize(fresh,'initrestore');name=start(OLD,fresh,'restored')
        call(['exec',name,'mysql','-uroot','-e','CREATE DATABASE otziv CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;'])
        sql(name,recovery.decode('utf-8'))
        restored=snapshot(name)
        check('fresh_restore_preserves_all_data_and_schema',all(restored[k]==written[k] for k in written if k!='accountsSha256'))
        result['result']='PASS'
    finally:
        for name in list(containers): remove(name)
        for volume in volumes:
            assert json.loads(call(['volume','inspect',volume]))[0]['Labels'].get(LABEL)==owner
            call(['volume','rm',volume])
        result['ownedResourcesRemaining']={'containers':len(containers),'volumes':0}
        result['completedAt']=datetime.datetime.now(datetime.timezone.utc).isoformat()
        Path(args.output).write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8',newline='\n')

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--candidate',required=True);parser.add_argument('--dump',required=True);parser.add_argument('--output',required=True)
    run(parser.parse_args())
