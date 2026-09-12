"""Read-only VPS capture and isolated same-engine PostgreSQL library-upgrade rehearsal.

Private credentials/dumps/logs never enter the repository or the public receipt.
The replay opens no host ports and modifies only labelled disposable resources.
"""
import argparse, base64, datetime, hashlib, json, pathlib, subprocess, sys, uuid

parser = argparse.ArgumentParser()
parser.add_argument('action', choices=['capture', 'replay'])
parser.add_argument('--private-directory', required=True, type=pathlib.Path)
parser.add_argument('--output', required=True, type=pathlib.Path)
parser.add_argument('--candidate')
args = parser.parse_args()
ROOT = pathlib.Path(__file__).resolve().parents[2]
PRIVATE = args.private_directory.resolve()
if PRIVATE == ROOT or ROOT in PRIVATE.parents:
    raise SystemExit('private_directory_must_be_outside_repository')
PUBLIC = args.output.resolve()
PUBLIC.mkdir(parents=True, exist_ok=True)
OWNER = 'otziv-pg-c16-' + uuid.uuid4().hex[:12]
LABEL = 'com.otziv.pg-c16-rehearsal.owner'
OLD_PG = 'ghcr.io/claidd/otziv-security@sha256:07834abbfd80ed7183afa9096db99afc4d93b99fd51aae726e550d07ade65dbf'
PG_CONFIG = 'sha256:d257683e1d6febdfb869b77c60683bc598b272788d60e65893a109adaf0a2db4'
NEW_KC = 'ghcr.io/claidd/otziv-security@sha256:bd5687843becb0cdb232c2b864cc3786fafb8fc91f30f531fa2db7fc4d1fc59d'
KC_CONFIG = 'sha256:34e7587586d4d0a8d8330845f43cc58db9196d4dc6c4f120ac70519541453e5e'
NEW_PG = args.candidate
containers, volumes, network, stage = [], [], None, 'setup'
REPORT = {'schema': 'otziv-postgres-c16-rehearsal-v1', 'result': 'FAIL', 'checks': [],
          'startedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
          'productionWrites': False, 'privateRowsPublished': False, 'publishedPorts': 0,
          'sourcePostgres': OLD_PG, 'sourcePostgresConfig': PG_CONFIG,
          'keycloak': NEW_KC, 'keycloakConfig': KC_CONFIG,
          'executedScriptSha256': hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest()}
exec(compile((ROOT / 'infrastructure/keycloak/security-generation/c14-migration-fix/private-runtime.py').read_text(),
             'private-runtime.py', 'exec'))

SSH = ['ssh', '-T', '-p', '22022', '-i', 'E:/Works/Projects/.ssh/otziv_vps_ed25519',
       '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes',
       '-o', 'UserKnownHostsFile=E:/Works/Projects/.ssh/known_hosts', '-o', 'ConnectTimeout=15',
       'hunt@95.213.248.152', 'python3 -B -']
REMOTE = '''
import base64,json,subprocess,sys
def command(arguments,data=None):
 p=subprocess.run(arguments,input=data,capture_output=True,timeout=150)
 if p.returncode: raise RuntimeError('readonly_capture_command_failed')
 return p.stdout
def find(service):
 ids=command(['docker','ps','-aq','--filter','label=com.docker.compose.project=otziv-prod',
              '--filter','label=com.docker.compose.service='+service]).decode().splitlines()
 assert len(ids)==1
 return json.loads(command(['docker','inspect',ids[0]]))[0]
pg,kc=find('keycloak-postgres'),find('keycloak')
assert pg['Image']==%r and kc['Image']==%r
def sql(query):
 shell='export PGPASSWORD="$POSTGRES_PASSWORD"; export PGOPTIONS="-c default_transaction_read_only=on -c statement_timeout=15000 -c lock_timeout=2000"; exec psql -X -q -A -t -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
 return command(['docker','exec','-i',pg['Id'],'sh','-c',shell],query.encode()).decode()
''' % (PG_CONFIG, KC_CONFIG)

def save_json(path, value):
    path.write_bytes((json.dumps(value, indent=2) + '\n').encode())

def private_remote(script, name):
    path = PRIVATE / name
    with path.open('xb') as output, (PRIVATE / (name + '.stderr')).open('xb') as error:
        result = subprocess.run(SSH, input=script.encode(), stdout=output, stderr=error, timeout=180)
    if result.returncode:
        raise RuntimeError('readonly_capture_failed')
    return path

def capture_metadata(name):
    queries = {'inventory': INVENTORY_SQL, 'tables': TABLES_SQL, 'critical': critical_sql()}
    source = REMOTE + '\nqueries=json.loads(base64.b64decode(%r))\n' % base64.b64encode(json.dumps(queries).encode()).decode()
    source += '''
kenv=dict(x.split('=',1) for x in kc['Config']['Env'] if '=' in x)
penv=dict(x.split('=',1) for x in pg['Config']['Env'] if '=' in x)
data={'observedAt':__import__('datetime').datetime.now(__import__('datetime').timezone.utc).isoformat(),
 'postgres':json.loads(sql(queries['inventory'])),
 'critical':[json.loads(x) for x in sql(queries['critical']).splitlines() if x.strip()],
 'tables':[json.loads(x) for x in sql(sql(queries['tables'])).splitlines() if x.strip()],
 'connection':{'user':penv['POSTGRES_USER'],'database':penv['POSTGRES_DB']},
 'admin':{'username':kenv.get('KC_BOOTSTRAP_ADMIN_USERNAME',kenv.get('KEYCLOAK_ADMIN')),
          'password':kenv.get('KC_BOOTSTRAP_ADMIN_PASSWORD',kenv.get('KEYCLOAK_ADMIN_PASSWORD'))},
 'containers':{name:{'id':obj['Id'],'imageId':obj['Image'],'startedAt':obj['State']['StartedAt'],
                   'restarts':obj['RestartCount']} for name,obj in [('postgres',pg),('keycloak',kc)]}}
print(json.dumps(data))
'''
    return json.loads(private_remote(source, name).read_bytes())

def public_inventory(metadata):
    return {key: metadata[key] for key in ['observedAt', 'postgres', 'critical', 'containers']}

def volume(suffix):
    name = OWNER + '-' + suffix
    docker(['volume', 'create', '--label', LABEL + '=' + OWNER, name])
    volumes.append(name)
    return name

def assert_no_ports():
    for name in containers:
        obj = inspect(name)
        check('no_published_ports_' + name.removeprefix(OWNER + '-'), not obj['HostConfig'].get('PortBindings'))

try:
    if args.action == 'capture':
        if PRIVATE.exists():
            raise RuntimeError('capture_directory_already_exists')
        PRIVATE.mkdir()
        # The directory is empty until its ACL is restricted to this user and SYSTEM.
        ps = "$ErrorActionPreference='Stop'; $p='" + str(PRIVATE).replace("'", "''") + "'; $sid=[System.Security.Principal.WindowsIdentity]::GetCurrent().User; $a=New-Object System.Security.AccessControl.DirectorySecurity; $a.SetAccessRuleProtection($true,$false); foreach($id in @($sid,(New-Object System.Security.Principal.SecurityIdentifier('S-1-5-18')))){ $r=New-Object System.Security.AccessControl.FileSystemAccessRule($id,'FullControl','ContainerInherit,ObjectInherit','None','Allow'); $a.AddAccessRule($r) }; Set-Acl -LiteralPath $p -AclObject $a"
        subprocess.run(['pwsh', '-NoProfile', '-EncodedCommand', base64.b64encode(ps.encode('utf-16le')).decode()], check=True, capture_output=True)
        acl(PRIVATE)
        stage = 'capture_before'
        before = capture_metadata('before.json')
        stage = 'capture_dump'
        source = REMOTE + '''
shell='export PGPASSWORD="$POSTGRES_PASSWORD"; export PGOPTIONS="-c default_transaction_read_only=on -c lock_timeout=5000"; exec pg_dump --format=custom --lock-wait-timeout=5s -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
sys.stdout.buffer.write(command(['docker','exec',pg['Id'],'sh','-c',shell]))
'''
        dump = private_remote(source, 'database.dump')
        check('complete_custom_dump', dump.stat().st_size > 1024 and dump.read_bytes()[:5] == b'PGDMP')
        stage = 'capture_after'
        after = capture_metadata('after.json')
        check('source_runtime_unchanged', before['containers'] == after['containers'])
        check('source_critical_identity_stable_during_capture', before['critical'] == after['critical'])
        for key in ['clusterIdentifier', 'locale', 'encoding', 'extensions', 'configSha256', 'hbaSha256', 'identSha256']:
            check('source_' + key + '_unchanged', before['postgres'][key] == after['postgres'][key])
        user, database = before['connection']['user'], before['connection']['database']
        password = uuid.uuid4().hex + uuid.uuid4().hex
        for value in [user, database, password]:
            if any(x in value for x in '\r\n\0'):
                raise RuntimeError('invalid_local_connection_identity')
        (PRIVATE / 'postgres.env').write_bytes(('POSTGRES_USER=' + user + '\nPOSTGRES_DB=' + database + '\nPOSTGRES_PASSWORD=' + password + '\nTZ=Asia/Irkutsk\n').encode())
        (PRIVATE / 'keycloak.env').write_bytes(('KC_DB=postgres\nKC_DB_URL=jdbc:postgresql://pg:5432/' + database + '\nKC_DB_USERNAME=' + user + '\nKC_DB_PASSWORD=' + password + '\nKC_HEALTH_ENABLED=true\nKC_HTTP_ENABLED=true\nKC_HOSTNAME_STRICT=false\nJAVA_OPTS_KC_HEAP=-Xms256m -Xmx1024m\nTZ=Asia/Irkutsk\n').encode())
        for path in PRIVATE.iterdir(): acl(path)
        REPORT.update(result='PASS', dumpBytes=dump.stat().st_size, dumpSha256=hashlib.sha256(dump.read_bytes()).hexdigest(),
                      sourceBefore=public_inventory(before), sourceAfter=public_inventory(after),
                      stableTableCount=sum(1 for row in before['tables'] if row in after['tables']))
    else:
        import re
        if not re.fullmatch(r'ghcr\.io/claidd/otziv-security@sha256:[a-f0-9]{64}', NEW_PG or ''):
            raise RuntimeError('published_candidate_required')
        acl(PRIVATE)
        for name in ['before.json', 'after.json', 'database.dump', 'postgres.env', 'keycloak.env']: acl(PRIVATE / name)
        endpoint = docker(['context', 'inspect', '--format', '{{.Endpoints.docker.Host}}']).stdout.decode().strip()
        check('local_docker_only', endpoint.startswith(('npipe://', 'unix://')))
        before = json.loads((PRIVATE / 'before.json').read_bytes())
        dump = PRIVATE / 'database.dump'
        pgenv, kcenv = PRIVATE / 'postgres.env', PRIVATE / 'keycloak.env'
        REPORT.update(candidate=NEW_PG, candidateLocalId=inspect(NEW_PG, 'image')['Id'],
                      dumpSha256=hashlib.sha256(dump.read_bytes()).hexdigest())
        network = OWNER + '-net'
        docker(['network', 'create', '--internal', '--label', LABEL + '=' + OWNER, network])
        check('internal_network', inspect(network, 'network')['Internal'])
        runner = OWNER + '-http'
        # Current project Node runtime is used only as the local HTTP client.
        docker(['run', '-d', '--name', runner, '--label', LABEL + '=' + OWNER, '--network', network,
                '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true', '--memory', '256m',
                '--cpus', '.25', '--pids-limit', '64', '--entrypoint', 'node', 'node:22-bookworm-slim',
                '-e', 'setInterval(()=>{},100000)'])
        containers.append(runner)
        data = volume('data')
        stage = 'source_restore'
        old = start_pg('source-pg', OLD_PG, data, pgenv)
        restore(old, dump)
        original = local_tables(old)
        check('restored_critical_records_exact', local_critical(old) == before['critical'])
        stable = [row for row in before['tables'] if row in json.loads((PRIVATE / 'after.json').read_bytes())['tables']]
        check('capture_stable_tables_restored_exact', all(row in original for row in stable))
        source_inventory = json.loads(local_sql(old, INVENTORY_SQL))
        stop(old)
        stage = 'same_volume_upgrade'
        current = start_pg('candidate-pg', NEW_PG, data, pgenv)
        check('same_volume_all_tables_exact', local_tables(current) == original)
        candidate_inventory = json.loads(local_sql(current, INVENTORY_SQL))
        for key in ['serverVersion', 'encoding', 'locale', 'clusterIdentifier', 'extensions', 'configSha256', 'hbaSha256', 'identSha256']:
            check('same_volume_' + key, source_inventory[key] == candidate_inventory[key])
        local_sql(current, "CREATE TABLE otziv_c16_fixture(id bigint PRIMARY KEY,value text); INSERT INTO otziv_c16_fixture VALUES(1,'Иркутск—Unicode—ß—東京');")
        stage = 'existing_issuer'
        issuer = start_kc('candidate-kc', NEW_KC, kcenv, runner, before['admin'])
        check('existing_admin_password_login', REPORT['keycloakChecks']['candidate-kc']['adminRead'])
        check('existing_roles_and_credentials_preserved', local_critical(current) == before['critical'])
        response = request(runner, 'http://' + issuer + ':8080/realms/master/otziv-security/generation/00000000-0000-0000-0000-000000000000')
        check('unauthenticated_provider_denied', response and response['status'] == 401)
        stop(issuer)
        after_writes = local_tables(current)
        after_dump = PRIVATE / 'post-upgrade.dump'
        after_dump.write_bytes(docker(['exec', current, 'sh', '-c', 'exec pg_dump --format=custom -U "$POSTGRES_USER" -d "$POSTGRES_DB"']).stdout)
        stop(current)
        stage = 'same_volume_rollback'
        rolled_back = start_pg('rollback-pg', OLD_PG, data, pgenv)
        check('same_volume_rollback_preserves_all_post_upgrade_writes', local_tables(rolled_back) == after_writes)
        stop(rolled_back)
        stage = 'fresh_post_upgrade_restore'
        restored = start_pg('restored-pg', OLD_PG, volume('restored'), pgenv)
        restore(restored, after_dump)
        check('fresh_rollback_restore_preserves_all_post_upgrade_writes', local_tables(restored) == after_writes)
        restored_issuer = start_kc('restored-kc', NEW_KC, kcenv, runner, before['admin'])
        check('restored_existing_password_login', REPORT['keycloakChecks']['restored-kc']['adminRead'])
        stop(restored_issuer)
        check('restored_roles_and_credentials_preserved', local_critical(restored) == before['critical'])
        stop(restored)
        assert_no_ports()
        REPORT.update(result='PASS', tableCount=len(original), stableTableCount=len(stable),
                      sourceDatabase=source_inventory, candidateDatabase=candidate_inventory)
except Exception as error:
    REPORT['errorCode'] = str(error) if isinstance(error, RuntimeError) else stage + '_unexpected_error'
    if PRIVATE.exists(): (PRIVATE / (args.action + '-exception.txt')).write_text(repr(error), encoding='utf8')
finally:
    for name in reversed(containers):
        try:
            obj = inspect(name)
            if obj['Config'].get('Labels', {}).get(LABEL) != OWNER: raise RuntimeError('ownership_changed')
            log = docker(['logs', name], allow=True)
            (PRIVATE / (name + '.log')).write_bytes(log.stdout + log.stderr)
            docker(['rm', '-f', '-v', name])
        except Exception: REPORT.setdefault('cleanupErrors', []).append('container_cleanup_failed')
    for name in reversed(volumes):
        try:
            if inspect(name, 'volume').get('Labels', {}).get(LABEL) != OWNER: raise RuntimeError('ownership_changed')
            docker(['volume', 'rm', name])
        except Exception: REPORT.setdefault('cleanupErrors', []).append('volume_cleanup_failed')
    if network:
        try:
            if inspect(network, 'network').get('Labels', {}).get(LABEL) != OWNER: raise RuntimeError('ownership_changed')
            docker(['network', 'rm', network])
        except Exception: REPORT.setdefault('cleanupErrors', []).append('network_cleanup_failed')
    if args.action == 'replay':
        remaining = {kind: len(docker([kind, 'ls', '-q', '--filter', 'label=' + LABEL + '=' + OWNER]).stdout.splitlines())
                     for kind in ['container', 'volume', 'network']}
        REPORT['ownedResourcesRemaining'] = remaining
        if any(remaining.values()) or REPORT.get('cleanupErrors'): REPORT['result'] = 'FAIL'
    REPORT['completedAt'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    save_json(PUBLIC / (args.action + '.json'), REPORT)
    print(json.dumps({'result': REPORT['result'], 'checks': len(REPORT['checks']), 'errorCode': REPORT.get('errorCode')}))
if REPORT['result'] != 'PASS': sys.exit(1)
