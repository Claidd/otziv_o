"""Explicit reviewed SSL-only PostgreSQL cutover, under the normal deploy lock.

Ordinary database_image_guard is unchanged. This procedure runs only when the
release caller explicitly selects it, after the mandatory main DB backup.
Identity data and the additional encrypted PG backup remain on the VPS.
"""
import argparse, base64, copy, hashlib, hmac, json, os, pathlib, re, subprocess, tarfile, time, uuid
from database_image_guard import checked_override, DockerReadOnly, GuardError

PARENT_INDEX_SHA = '7df6f0ad65a8e1b7466afc226bf2db32abd34864f082fe3607701eead286ef92'
PROJECT = 'otziv-prod'
PG = 'keycloak-postgres'
KC = 'otziv-prod-keycloak-1'

def require(condition, code):
    if not condition: raise GuardError(code)

def digest(data): return hashlib.sha256(data).hexdigest()

def run(args, data=None, timeout=180):
    result = subprocess.run(args, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    require(result.returncode == 0, 'coordinated_ssl_command_failed_' + pathlib.Path(args[0]).name)
    return result.stdout

def inspect(name, kind='container'):
    values = json.loads(run(['docker', kind, 'inspect', name]))
    require(len(values) == 1, 'coordinated_ssl_image_or_container_ambiguous')
    return values[0]

def proof(root, reference):
    path = (root / reference['path']).resolve()
    require(path.is_relative_to(root) and path.is_file(), 'coordinated_ssl_proof_path')
    data = path.read_bytes(); require(digest(data) == reference['sha256'], 'coordinated_ssl_proof_hash')
    return json.loads(data)

def reviewed_plan(root, resolved):
    require(digest((root/'infrastructure/runtime-security/c23-parent-activations.json').read_bytes()) == PARENT_INDEX_SHA,
            'coordinated_ssl_parent_index_changed')
    activations = json.loads((root/'infrastructure/runtime-security/reviewed-image-activations.json').read_bytes())['images']
    entries = {x['component']: x for x in activations}
    pg, kc = entries['postgres'], entries['keycloak']
    require(pg.get('manifest', {}).get('path') == 'infrastructure/runtime-security/reviewed-images-c23-postgres.json'
            and kc.get('manifest', {}).get('path') in ('infrastructure/runtime-security/reviewed-images-c23-keycloak.json','infrastructure/runtime-security/reviewed-images-c24-keycloak.json','infrastructure/runtime-security/reviewed-images-c26-keycloak.json'),
            'coordinated_ssl_unreviewed_generation')
    accepted = proof(root, pg['sslRefreshAcceptance']); issuer = proof(root, kc['sslRefreshAcceptance'])
    require(accepted['result'] == issuer['result'] == 'PASS' and accepted['pair'] == issuer['pair'], 'coordinated_ssl_pair_not_accepted')
    for record, entry in ((accepted, pg), (issuer, kc)):
        require(record['schema']=='otziv-ssl-refresh-acceptance-v1' and record['component']==entry['component']
                and record['reference']==entry['reference'], 'coordinated_ssl_acceptance_identity')
        require(len(record['files'])>=6 and len(record['executedSources'])>=2, 'coordinated_ssl_acceptance_incomplete')
    require(accepted['parentIndexSha256'] == issuer['parentIndexSha256'] == PARENT_INDEX_SHA, 'coordinated_ssl_parent_binding')
    require(pg['databaseTransition'] == pg['sslRefreshAcceptance'] and kc['migrationAcceptance'] == kc['sslRefreshAcceptance'],
            'coordinated_ssl_transition_binding')
    for record in (accepted, issuer):
        for path, sha in {**record['files'], **record['executedSources']}.items():
            target = (root/path).resolve(); require(target.is_relative_to(root) and target.is_file(), 'coordinated_ssl_evidence_path')
            require(digest(target.read_bytes()) == sha, 'coordinated_ssl_evidence_changed')
    require(accepted['pair']['postgres']['reference'] == pg['reference'] and accepted['pair']['keycloak']['reference'] == kc['reference'],
            'coordinated_ssl_pair_references')
    require(resolved['services']['keycloak-postgres']['image'] == pg['reference'] and resolved['services']['keycloak']['image'] == kc['reference'],
            'coordinated_ssl_compose_reference_changed')
    return pg, kc, accepted

def sql(query):
    shell = 'export PGPASSWORD="$POSTGRES_PASSWORD"; export PGOPTIONS="-c default_transaction_read_only=on -c statement_timeout=30000 -c lock_timeout=2000"; exec psql -X -q -A -t -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
    return run(['docker', 'exec', '-i', PG, 'sh', '-c', shell], query.encode())

def table_fingerprints():
    tables = json.loads(sql("SELECT json_agg(tablename ORDER BY tablename) FROM pg_tables WHERE schemaname='public'"))
    require(len(tables) == 102 and all(re.fullmatch('[a-z_][a-z_0-9]*', x) for x in tables), 'coordinated_ssl_unrehearsed_schema')
    # Rows are hashed on the VPS and are never printed or returned to the client.
    return {name: digest(sql('SELECT row_to_json(t)::text FROM public."'+name+'" t ORDER BY row_to_json(t)::text')) for name in tables}

def healthy(name):
    for _ in range(90):
        obj = inspect(name)
        if obj['State'].get('Health', {}).get('Status') == 'healthy': return
        require(obj['State']['Status'] == 'running', 'coordinated_ssl_container_stopped')
        time.sleep(2)
    raise GuardError('coordinated_ssl_health_timeout')

def encrypted_backup(root, directory, env_file):
    raw_key = None
    for line in env_file.read_text().splitlines():
        if line.startswith('DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64='):
            raw_key = base64.b64decode(line.split('=', 1)[1].strip().strip('"\''), validate=True)
    require(raw_key is not None and len(raw_key) == 32, 'coordinated_ssl_backup_key_not_ready')
    plain, globals_file, payload, key_file = [directory/name for name in ['keycloak.dump', 'globals.sql', 'payload.tar.gz', 'key.pass']]
    shell = 'export PGPASSWORD="$POSTGRES_PASSWORD"; exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom'
    with plain.open('xb') as stream:
        os.chmod(plain, 0o600)
        subprocess.run(['docker', 'exec', PG, 'sh', '-c', shell], stdout=stream, stderr=subprocess.DEVNULL, check=True, timeout=180)
    with plain.open('rb') as stream:
        require(stream.read(5)==b'PGDMP', 'coordinated_ssl_invalid_custom_dump')
    with plain.open('rb') as stream:
        result=subprocess.run(['docker','exec','-i',PG,'pg_restore','--list'],stdin=stream,stdout=subprocess.DEVNULL,
                              stderr=subprocess.DEVNULL,timeout=180)
        require(result.returncode==0,'coordinated_ssl_custom_dump_not_restorable')
    shell = 'export PGPASSWORD="$POSTGRES_PASSWORD"; exec pg_dumpall -U "$POSTGRES_USER" --globals-only'
    globals_file.write_bytes(run(['docker', 'exec', PG, 'sh', '-c', shell])); os.chmod(globals_file, 0o600)
    with tarfile.open(payload, 'w:gz') as archive:
        archive.add(plain, arcname=plain.name); archive.add(globals_file, arcname=globals_file.name)
    os.chmod(payload, 0o600); original = digest(payload.read_bytes())
    key_file.write_bytes(base64.b64encode(raw_key)); os.chmod(key_file, 0o600)
    encrypted = directory/'keycloak.tar.gz.enc'
    options = ['-aes-256-cbc', '-pbkdf2', '-iter', '200000', '-md', 'sha256', '-pass', 'file:'+str(key_file)]
    try:
        run(['openssl', 'enc', *options, '-salt', '-in', str(payload), '-out', str(encrypted)])
        os.chmod(encrypted, 0o600)
        require(digest(run(['openssl', 'enc', '-d', *options, '-in', str(encrypted)])) == original, 'coordinated_ssl_backup_decrypt_mismatch')
        cipher = encrypted.read_bytes(); mac_key = hmac.new(raw_key, b'otziv-keycloak-ssl-refresh-backup-v1', hashlib.sha256).digest()
        record = {'schema': 'otziv-keycloak-ssl-refresh-backup-v1', 'cipherSha256': digest(cipher), 'payloadSha256': original,
                  'hmacSha256': hmac.new(mac_key, cipher, hashlib.sha256).hexdigest(), 'artifact': encrypted.name, 'decryptVerified': True}
        (directory/'backup.json').write_text(json.dumps(record, indent=2)+'\n'); os.chmod(directory/'backup.json', 0o600)
        # Only exact files freshly created above are deleted. The verified encrypted backup is retained.
        for path in (plain, globals_file, payload): path.unlink()
        return record
    finally:
        if key_file.exists(): key_file.unlink()

def main():
    parser = argparse.ArgumentParser()
    for key in ['root', 'env-file', 'lock-token', 'main-revision']: parser.add_argument('--'+key, required=True)
    parser.add_argument('--explicit-reviewed-ssl-cutover', action='store_true', required=True)
    args = parser.parse_args(); root = pathlib.Path(args.root).resolve()
    require(re.fullmatch('[a-f0-9]{40}', args.main_revision), 'coordinated_ssl_main_revision')
    require((root/'.deploy.lock.d/owner').read_text().strip() == args.lock_token, 'coordinated_ssl_deploy_lock')
    env_file = pathlib.Path(args.env_file).resolve(); require(env_file.is_relative_to(root), 'coordinated_ssl_env_path')
    compose = ['docker', 'compose', '--project-name', PROJECT, '--project-directory', str(root), '-f', str(root/'docker-compose.yaml'), '--env-file', str(env_file)]
    resolved = json.loads(run([*compose, 'config', '--format', 'json']))
    pg, kc, accepted = reviewed_plan(root, resolved)
    # Both immutable images are part of the CI/capacity plan; pull before any
    # source stop or new backup so a transport failure cannot interrupt auth.
    for reference in (pg['reference'], kc['reference']): run(['docker', 'pull', '--platform', 'linux/amd64', reference], timeout=300)
    target = inspect(pg['reference'], 'image'); old = inspect(PG); issuer = inspect(KC)
    if old['Image'] == target['Id']:
        checked_override(resolved, DockerReadOnly()); print(json.dumps({'result': 'ALREADY_INSTALLED', 'productionRowsExported': False})); return
    parents = json.loads((root/'infrastructure/runtime-security/c23-parent-activations.json').read_bytes())['images']
    parent_pg = next(x for x in parents if x['component']=='postgres'); parent_kc = next(x for x in parents if x['component']=='keycloak')
    source = inspect(parent_pg['reference'], 'image'); source_issuer = inspect(parent_kc['reference'], 'image')
    require(old['Image'] == source['Id'] and issuer['Image'] == source_issuer['Id'], 'coordinated_ssl_source_image_changed')
    require(issuer['State']['Status']=='running' and old['State']['Status']=='running', 'coordinated_ssl_source_not_running')
    before_config = copy.deepcopy(resolved); before_config['services']['keycloak-postgres']['image'] = parent_pg['reference']
    # The normal guard verifies the existing volume/launch/project before this
    # separate activation. It is called again with the real target afterwards.
    checked_override(before_config, DockerReadOnly())
    base = (root/'.deploy-backups').resolve(); require(base.is_relative_to(root), 'coordinated_ssl_backup_root')
    directory = base/('ssl-refresh-'+uuid.uuid4().hex); directory.mkdir(mode=0o700, parents=True)
    backup = None; changed = False
    override = directory/'rollback-compose.json'
    override.write_text(json.dumps({'services': {'keycloak-postgres': {'image': parent_pg['reference'], 'pull_policy': 'never'}}}))
    os.chmod(override, 0o600)
    try:
        run(['docker', 'stop', '--time', '30', KC]); healthy(PG)
        before = table_fingerprints(); backup = encrypted_backup(root, directory, env_file)
        require(table_fingerprints() == before, 'coordinated_ssl_source_changed_during_backup')
        require((root/'.deploy.lock.d/owner').read_text().strip() == args.lock_token, 'coordinated_ssl_lock_changed')
        changed = True
        run([*compose, 'up', '-d', '--no-deps', '--pull', 'never', 'keycloak-postgres']); healthy(PG)
        require(inspect(PG)['Image'] == target['Id'], 'coordinated_ssl_wrong_target_started')
        require(table_fingerprints() == before, 'coordinated_ssl_records_changed')
        checked_override(resolved, DockerReadOnly())
        run(['docker', 'start', KC]); healthy(KC)
        record = {'schema': 'otziv-coordinated-ssl-cutover-v1', 'result': 'PASS', 'mainRevision': args.main_revision,
                  'source': parent_pg['reference'], 'target': pg['reference'], 'tableCount': len(before),
                  'recordsPreserved': True, 'productionRowsExported': False, 'backup': backup, 'volumeName': old['Mounts'][0]['Name']}
        (directory/'cutover.json').write_text(json.dumps(record, indent=2)+'\n'); os.chmod(directory/'cutover.json', 0o600)
        print(json.dumps({'result': 'PASS', 'tablesPreserved': len(before), 'productionRowsExported': False, 'backupDirectory': str(directory)}))
    except BaseException:
        if changed:
            run([*compose, '-f', str(override), 'up', '-d', '--no-deps', '--pull', 'never', 'keycloak-postgres']); healthy(PG)
            require(table_fingerprints() == before, 'coordinated_ssl_rollback_records_changed')
        if inspect(KC)['State']['Status'] != 'running': run(['docker', 'start', KC]); healthy(KC)
        raise

if __name__ == '__main__':
    try: main()
    except (GuardError, OSError, ValueError, subprocess.SubprocessError):
        print('Coordinated SSL refresh failed; inspect the protected server evidence.', file=__import__('sys').stderr)
        raise SystemExit(1)
