"""Explicit reviewed C23-to-C36 PostgreSQL cutover, under the normal deploy lock.

Ordinary database_image_guard is unchanged. This procedure runs only when the
release caller explicitly selects it, after the mandatory main DB backup.
Identity data and the additional encrypted PG backup remain on the VPS.
"""
import argparse, base64, copy, hashlib, hmac, json, os, pathlib, re, stat, subprocess, tarfile, time, uuid
from database_image_guard import checked_override, DockerReadOnly, GuardError

C36_PARENT_SHA = 'fcc50b0b792637c08a9a74cf51057354d138b6a5a741a344ed6979a3c7a611b3'
SOURCE_PG = 'ghcr.io/claidd/otziv-security@sha256:c974c12a1107e314b1ba8cf65bd1fc5b6964645ced420c2151d336ee8a9a6534'
SOURCE_PG_CONFIG = 'sha256:1f5c2590fadde6b449908569285f1ed91a10831d36535d5c4ed647e9a0afca6a'
ACTIVE_KC = 'ghcr.io/claidd/otziv-security@sha256:c28364544835f5fbef6d4e0b0b3e63154127f7fb440b05432760f11235b5f4a9'
ACTIVE_KC_CONFIG = 'sha256:60a8dd92cf43b8cf909a9e13f231da1f59103f9259cf482185c54856dcfd6a1b'
SOURCE_KC = 'ghcr.io/claidd/otziv-security@sha256:76ad15fac3c0041798b6b2b87632bbf91ae7c171ebfa813aa1d1633b48f90ff9'
SOURCE_KC_CONFIG = 'sha256:f641317e4919c0959cf51693cf434024094922ec612f5f3bb5a08122f10ab511'
C36_PG_MANIFEST = 'infrastructure/runtime-security/reviewed-images-c36-postgres.json'
C35_KC_MANIFEST = 'infrastructure/runtime-security/reviewed-images-c35-keycloak.json'
REQUIRED_REHEARSAL_CHECKS = (
    'restored_critical_records_exact', 'capture_stable_tables_restored_exact',
    'same_volume_all_tables_exact', 'existing_admin_password_login',
    'existing_roles_and_credentials_preserved', 'unauthenticated_provider_denied',
    'same_volume_rollback_preserves_all_post_upgrade_writes',
    'same_volume_old_issuer_login', 'same_volume_old_issuer_credentials_preserved',
    'fresh_rollback_restore_preserves_all_post_upgrade_writes',
    'restored_existing_password_login', 'restored_roles_and_credentials_preserved',
)
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

def ensure_image_present(reference):
    try:
        inspect(reference, 'image')
    except GuardError:
        run(['docker', 'pull', '--platform', 'linux/amd64', reference], timeout=300)

def proof(root, reference):
    require(isinstance(reference, dict) and isinstance(reference.get('path'), str)
            and isinstance(reference.get('sha256'), str), 'coordinated_ssl_proof_reference')
    path = (root / reference['path']).resolve()
    require(path.is_relative_to(root) and path.is_file(), 'coordinated_ssl_proof_path')
    data = path.read_bytes(); require(digest(data) == reference['sha256'], 'coordinated_ssl_proof_hash')
    return json.loads(data)

def validate_evidence(root, path, sha):
    require(isinstance(path, str) and isinstance(sha, str)
            and re.fullmatch('[a-f0-9]{64}', sha), 'coordinated_ssl_evidence_path')
    target = (root/path).resolve()
    require(target.is_relative_to(root), 'coordinated_ssl_evidence_path')
    split_path = re.fullmatch(
        r'infrastructure/runtime-security/proofs/[A-Za-z0-9_-]+/'
        r'(?:publication|anonymous)/registry-attestation-[0-9]+-payload-[0-9]+\.json', path)
    metadata_path = target.with_name(target.name + '.parts.json')
    if target.is_file():
        require(not split_path or not metadata_path.exists(), 'coordinated_ssl_evidence_changed')
        require(digest(target.read_bytes()) == sha, 'coordinated_ssl_evidence_changed')
        return
    # Large registry attestations are stored as two bounded parts. Validate
    # their original digest without writing reconstructed evidence to disk.
    require(split_path, 'coordinated_ssl_evidence_path')
    require(metadata_path.is_file() and not metadata_path.is_symlink()
            and metadata_path.stat().st_size <= 4096, 'coordinated_ssl_evidence_path')
    metadata = json.loads(metadata_path.read_bytes())
    require(isinstance(metadata, dict)
            and set(metadata) == {'schema', 'bytes', 'sha256', 'parts'}
            and metadata['schema'] == 'otziv-raw-evidence-parts-v1'
            and metadata['sha256'] == sha
            and type(metadata['bytes']) is int and 5*1024*1024 < metadata['bytes'] <= 8*1024*1024
            and isinstance(metadata['parts'], list) and len(metadata['parts']) == 2,
            'coordinated_ssl_evidence_changed')
    expected = [target.name + '.part001', target.name + '.part002', target.name + '.parts.json']
    found = sorted(item.name for item in target.parent.iterdir()
                   if item.name.startswith(target.name + '.part'))
    require(found == sorted(expected), 'coordinated_ssl_evidence_changed')
    whole = hashlib.sha256(); total = 0
    for index, part in enumerate(metadata['parts']):
        require(isinstance(part, dict) and set(part) == {'file', 'bytes', 'sha256'}
                and part['file'] == expected[index]
                and type(part['bytes']) is int and 0 < part['bytes'] <= 4*1024*1024
                and isinstance(part['sha256'], str)
                and re.fullmatch('[a-f0-9]{64}', part['sha256']),
                'coordinated_ssl_evidence_changed')
        file = target.parent/part['file']
        require(file.is_file() and not file.is_symlink()
                and file.stat().st_size == part['bytes'], 'coordinated_ssl_evidence_changed')
        current = hashlib.sha256(); length = 0
        with file.open('rb') as stream:
            while chunk := stream.read(1024*1024):
                current.update(chunk); whole.update(chunk); length += len(chunk)
        require(length == part['bytes'] and current.hexdigest() == part['sha256'],
                'coordinated_ssl_evidence_changed')
        total += length
    require(total == metadata['bytes'] and whole.hexdigest() == sha,
            'coordinated_ssl_evidence_changed')

def reviewed_plan(root, resolved):
    parent_path = root/'infrastructure/runtime-security/c36-parent-postgres.json'
    require(parent_path.is_file() and digest(parent_path.read_bytes()) == C36_PARENT_SHA,
            'coordinated_ssl_parent_changed')
    parent = json.loads(parent_path.read_bytes())
    require(parent.get('component') == 'postgres' and parent.get('reference') == SOURCE_PG
            and parent.get('manifest', {}).get('path') == 'infrastructure/runtime-security/reviewed-images-c23-postgres.json',
            'coordinated_ssl_parent_identity')
    activations = json.loads((root/'infrastructure/runtime-security/reviewed-image-activations.json').read_bytes())['images']
    entries = {x['component']: x for x in activations}
    pg, kc = entries['postgres'], entries['keycloak']
    require(pg.get('manifest', {}).get('path') == C36_PG_MANIFEST
            and kc.get('manifest', {}).get('path') == C35_KC_MANIFEST,
            'coordinated_ssl_unreviewed_generation')
    for entry in (pg, kc):
        manifest = entry['manifest']
        path = (root/manifest['path']).resolve()
        require(path.is_relative_to(root) and path.is_file()
                and digest(path.read_bytes()) == manifest.get('sha256'),
                'coordinated_ssl_manifest_changed')
    require(pg.get('databaseTransition') == pg.get('postgresC36Acceptance')
            and pg.get('postgresC36Acceptance') and kc.get('migrationAcceptance') == kc.get('vertxRefreshAcceptance')
            and kc.get('vertxRefreshAcceptance'),
            'coordinated_ssl_transition_binding')
    accepted = proof(root, pg['postgresC36Acceptance']); issuer = proof(root, kc['vertxRefreshAcceptance'])
    require(accepted.get('schema') == 'otziv-postgres-c36-acceptance-v1'
            and accepted.get('result') == 'PASS'
            and accepted.get('reference') == pg['reference']
            and accepted.get('parentReference') == SOURCE_PG
            and accepted.get('parentConfigId') == SOURCE_PG_CONFIG
            and all(accepted.get(key) == pg.get(key) for key in ('commit', 'run', 'attempt')),
            'coordinated_ssl_acceptance_identity')
    require(issuer.get('schema') == 'otziv-keycloak-vertx-c35-acceptance-v1'
            and issuer.get('result') == 'PASS' and issuer.get('component') == 'keycloak'
            and issuer.get('reference') == kc['reference'] == ACTIVE_KC
            and issuer.get('imageConfigId') == ACTIVE_KC_CONFIG
            and issuer.get('parentReference') == SOURCE_KC
            and issuer.get('parentConfigId') == SOURCE_KC_CONFIG
            and issuer.get('postgres', {}).get('reference') == SOURCE_PG
            and issuer.get('postgres', {}).get('imageConfigId') == SOURCE_PG_CONFIG
            and all(issuer.get(key) == kc.get(key) for key in ('commit', 'run', 'attempt')),
            'coordinated_ssl_issuer_identity')
    require(accepted.get('pair', {}).get('postgres', {}).get('reference') == pg['reference']
            and accepted.get('pair', {}).get('postgres', {}).get('imageConfigId') == accepted.get('imageConfigId')
            and accepted.get('pair', {}).get('keycloak', {}).get('reference') == ACTIVE_KC
            and accepted.get('pair', {}).get('keycloak', {}).get('imageConfigId') == ACTIVE_KC_CONFIG
            and re.fullmatch('sha256:[a-f0-9]{64}', accepted.get('imageConfigId', '')),
            'coordinated_ssl_pair_references')
    require(isinstance(accepted.get('files'), dict) and len(accepted['files']) >= 8
            and isinstance(accepted.get('executedSources'), dict) and len(accepted['executedSources']) >= 4,
            'coordinated_ssl_acceptance_incomplete')
    require(isinstance(issuer.get('files'), dict) and len(issuer['files']) >= 6
            and isinstance(issuer.get('executedSources'), dict) and len(issuer['executedSources']) >= 2,
            'coordinated_ssl_issuer_incomplete')
    for record in (accepted, issuer):
        for path, sha in {**record['files'], **record['executedSources']}.items():
            validate_evidence(root, path, sha)
    for key in ('capturePath', 'replayPath'):
        path = accepted.get(key)
        require(path in accepted['files'], 'coordinated_ssl_rehearsal_unbound')
    capture = json.loads((root/accepted['capturePath']).read_bytes())
    replay = json.loads((root/accepted['replayPath']).read_bytes())
    for record in (capture, replay):
        require(record.get('schema') == 'otziv-postgres-c36-rehearsal-v1' and record.get('result') == 'PASS'
                and record.get('sourcePostgres') == SOURCE_PG
                and record.get('sourcePostgresConfig') == SOURCE_PG_CONFIG
                and record.get('keycloak') == ACTIVE_KC
                and record.get('keycloakConfig') == ACTIVE_KC_CONFIG
                and record.get('productionWrites') is False and record.get('publishedPorts') == 0
                and record.get('privateRowsPublished') is False
                and record.get('sourceScope') == 'LOCAL_PROD_LIKE_KEYCLOAK'
                and record.get('executedScriptSha256') ==
                    accepted['executedSources'].get('infrastructure/runtime-security/postgres-c36-rehearsal.py'),
                'coordinated_ssl_rehearsal_identity')
    require(replay.get('candidatePostgres') == pg['reference']
            and replay.get('candidatePostgresLocalId') in (accepted['imageConfigId'], pg['reference'].split('@')[1])
            and replay.get('candidate') == ACTIVE_KC
            and replay.get('candidateLocalId') in (ACTIVE_KC_CONFIG, ACTIVE_KC.split('@')[1])
            and replay.get('tableCount') == 102
            and replay.get('ownedResourcesRemaining') == {'container': 0, 'volume': 0, 'network': 0}
            and not replay.get('cleanupErrors'), 'coordinated_ssl_rehearsal_incomplete')
    require(all(sum(check.get('name') == name and check.get('passed') is True
                    for check in replay.get('checks', [])) == 1 for name in REQUIRED_REHEARSAL_CHECKS),
            'coordinated_ssl_rehearsal_checks_missing')
    require(resolved['services']['keycloak-postgres']['image'] == pg['reference']
            and resolved['services']['keycloak']['image'] == kc['reference'],
            'coordinated_ssl_compose_reference_changed')
    return pg, kc, accepted, parent

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

def backup_key(env_file):
    keys = []
    for line in env_file.read_text().splitlines():
        if line.startswith('DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64='):
            keys.append(base64.b64decode(line.split('=', 1)[1].strip().strip('"\''), validate=True))
    require(len(keys) == 1 and len(keys[0]) == 32, 'coordinated_ssl_backup_key_not_ready')
    return keys[0]

def encrypted_backup(root, directory, env_file):
    raw_key = backup_key(env_file)
    plain, globals_file, payload, key_file = [directory/name for name in ['keycloak.dump', 'globals.sql', 'payload.tar.gz', 'key.pass']]
    encrypted = directory/'keycloak.tar.gz.enc'
    record_file = directory/'backup.json'
    options = ['-aes-256-cbc', '-pbkdf2', '-iter', '200000', '-md', 'sha256', '-pass', 'file:'+str(key_file)]
    complete = False
    try:
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
        run(['openssl', 'enc', *options, '-salt', '-in', str(payload), '-out', str(encrypted)])
        os.chmod(encrypted, 0o600)
        require(digest(run(['openssl', 'enc', '-d', *options, '-in', str(encrypted)])) == original, 'coordinated_ssl_backup_decrypt_mismatch')
        cipher = encrypted.read_bytes(); mac_key = hmac.new(raw_key, b'otziv-keycloak-ssl-refresh-backup-v1', hashlib.sha256).digest()
        record = {'schema': 'otziv-keycloak-ssl-refresh-backup-v1', 'cipherSha256': digest(cipher), 'payloadSha256': original,
                  'hmacSha256': hmac.new(mac_key, cipher, hashlib.sha256).hexdigest(), 'artifact': encrypted.name, 'decryptVerified': True}
        record_file.write_text(json.dumps(record, indent=2)+'\n'); os.chmod(record_file, 0o600)
        complete = True
        return record
    finally:
        # These plaintext files may already exist when pg_dump or encryption
        # fails. Keep only an authenticated, decrypt-verified encrypted backup.
        for path in (plain, globals_file, payload, key_file):
            if path.exists(): path.unlink()
        if not complete:
            for path in (encrypted, record_file):
                if path.exists(): path.unlink()

def verify_preparation_receipt(root, env_file, lock_token, revision, pg_reference):
    base_path = root/'.deploy-backups'
    base = base_path.resolve()
    require(base.is_relative_to(root) and base.is_dir() and not base_path.is_symlink(),
            'coordinated_ssl_preparation_missing')
    matches = []
    for path in base.glob('ssl-refresh-*/prepare-keycloak.json'):
        if (path.is_symlink() or path.parent.is_symlink() or not path.is_file()
                or path.stat().st_size > 16384):
            continue
        try:
            record = json.loads(path.read_bytes())
        except (ValueError, OSError):
            continue
        if (isinstance(record, dict) and record.get('lockToken') == lock_token
                and record.get('mainRevision') == revision):
            matches.append((path, record))
    require(len(matches) == 1, 'coordinated_ssl_preparation_missing_or_ambiguous')
    path, record = matches[0]
    directory = path.parent.resolve()
    require(directory.is_relative_to(base)
            and re.fullmatch('ssl-refresh-[0-9a-f]{32}', directory.name)
            and not path.is_symlink(), 'coordinated_ssl_preparation_path')
    if os.name == 'posix':
        require(stat.S_IMODE(directory.stat().st_mode) == 0o700
                and stat.S_IMODE(path.stat().st_mode) == 0o600,
                'coordinated_ssl_preparation_permissions')
    require(set(record) == {'schema', 'result', 'mainRevision', 'lockToken',
                            'sourceKeycloak', 'targetKeycloak', 'sourcePostgres',
                            'targetPostgres', 'tableCount', 'recordsPreserved',
                            'recordsDigest', 'productionRowsExported', 'backup'}
            and record['schema'] == 'otziv-keycloak-c35-preparation-v1'
            and record['result'] == 'PASS'
            and record['sourceKeycloak'] == SOURCE_KC
            and record['targetKeycloak'] == ACTIVE_KC
            and record['sourcePostgres'] == SOURCE_PG
            and record['targetPostgres'] == pg_reference
            and record['tableCount'] == 102
            and record['recordsPreserved'] is True
            and record['productionRowsExported'] is False
            and isinstance(record['recordsDigest'], str)
            and re.fullmatch('[a-f0-9]{64}', record['recordsDigest']),
            'coordinated_ssl_preparation_identity')
    backup = record['backup']
    require(isinstance(backup, dict)
            and set(backup) == {'schema', 'cipherSha256', 'payloadSha256',
                                'hmacSha256', 'artifact', 'decryptVerified'}
            and backup['schema'] == 'otziv-keycloak-ssl-refresh-backup-v1'
            and backup['artifact'] == 'keycloak.tar.gz.enc'
            and backup['decryptVerified'] is True
            and all(isinstance(backup[key], str) and re.fullmatch('[a-f0-9]{64}', backup[key])
                    for key in ('cipherSha256', 'payloadSha256', 'hmacSha256')),
            'coordinated_ssl_preparation_backup_metadata')
    backup_record = directory/'backup.json'
    cipher = directory/backup['artifact']
    require(backup_record.is_file() and not backup_record.is_symlink()
            and backup_record.stat().st_size <= 4096
            and cipher.is_file() and not cipher.is_symlink(),
            'coordinated_ssl_preparation_backup_missing')
    if os.name == 'posix':
        require(stat.S_IMODE(backup_record.stat().st_mode) == 0o600
                and stat.S_IMODE(cipher.stat().st_mode) == 0o600,
                'coordinated_ssl_preparation_permissions')
    require(json.loads(backup_record.read_bytes()) == backup,
            'coordinated_ssl_preparation_backup_metadata')
    raw_key = backup_key(env_file)
    mac_key = hmac.new(raw_key, b'otziv-keycloak-ssl-refresh-backup-v1', hashlib.sha256).digest()
    ciphertext_sha = hashlib.sha256()
    ciphertext_mac = hmac.new(mac_key, digestmod=hashlib.sha256)
    with cipher.open('rb') as stream:
        while chunk := stream.read(1024*1024):
            ciphertext_sha.update(chunk); ciphertext_mac.update(chunk)
    require(hmac.compare_digest(ciphertext_sha.hexdigest(), backup['cipherSha256'])
            and hmac.compare_digest(ciphertext_mac.hexdigest(), backup['hmacSha256']),
            'coordinated_ssl_preparation_backup_changed')
    return record

def prepare_keycloak_cutover(root, env_file, lock_token, revision, resolved, pg, kc, accepted, parent_pg):
    # Keep the C26/C23 pair serving traffic unless the immutable C35/C36 pair
    # is fully available and its production source identities still match.
    for reference in (pg['reference'], kc['reference']):
        ensure_image_present(reference)
    target_pg = inspect(pg['reference'], 'image')
    target_kc = inspect(kc['reference'], 'image')
    source_pg = inspect(parent_pg['reference'], 'image')
    source_kc = inspect(SOURCE_KC, 'image')
    old_pg = inspect(PG); old_kc = inspect(KC)
    require(target_pg['Id'] in (pg['reference'].split('@')[1], accepted['imageConfigId'])
            and target_kc['Id'] in (ACTIVE_KC_CONFIG, ACTIVE_KC.split('@')[1])
            and source_pg['Id'] in (SOURCE_PG_CONFIG, SOURCE_PG.split('@')[1])
            and source_kc['Id'] in (SOURCE_KC_CONFIG, SOURCE_KC.split('@')[1])
            and old_pg['Image'] == source_pg['Id']
            and old_kc['Image'] == source_kc['Id']
            and old_pg['State']['Status'] == 'running'
            and old_pg['State'].get('Health', {}).get('Status') == 'healthy'
            and old_kc['State']['Status'] == 'running'
            and old_kc['State'].get('Health', {}).get('Status') == 'healthy',
            'coordinated_ssl_prepare_source_or_candidate_changed')
    source_config = copy.deepcopy(resolved)
    source_config['services']['keycloak-postgres']['image'] = parent_pg['reference']
    checked_override(source_config, DockerReadOnly())
    base = (root/'.deploy-backups').resolve()
    require(base.is_relative_to(root) and base.is_dir(), 'coordinated_ssl_backup_root')
    directory = base/('ssl-refresh-'+uuid.uuid4().hex)
    directory.mkdir(mode=0o700)
    backup = None
    try:
        run(['docker', 'stop', '--time', '30', KC]); healthy(PG)
        before = table_fingerprints()
        backup = encrypted_backup(root, directory, env_file)
        require(table_fingerprints() == before, 'coordinated_ssl_source_changed_during_backup')
        require((root/'.deploy.lock.d/owner').read_text().strip() == lock_token,
                'coordinated_ssl_lock_changed')
        run(['docker', 'start', KC]); healthy(KC)
        require(inspect(KC)['Image'] == source_kc['Id'],
                'coordinated_ssl_prepare_wrong_issuer_restarted')
        receipt = {'schema': 'otziv-keycloak-c35-preparation-v1', 'result': 'PASS',
                   'mainRevision': revision, 'lockToken': lock_token,
                   'sourceKeycloak': SOURCE_KC, 'targetKeycloak': ACTIVE_KC,
                   'sourcePostgres': SOURCE_PG, 'targetPostgres': pg['reference'],
                   'tableCount': len(before), 'recordsPreserved': True,
                   'recordsDigest': digest(json.dumps(before, sort_keys=True).encode()),
                   'productionRowsExported': False, 'backup': backup}
        output = directory/'prepare-keycloak.json'
        output.write_text(json.dumps(receipt, sort_keys=True, indent=2)+'\n')
        os.chmod(output, 0o600)
        return {'result': 'PREPARED', 'tablesPreserved': len(before),
                'productionRowsExported': False, 'backupDirectory': str(directory)}
    except BaseException:
        if inspect(KC)['State']['Status'] != 'running': run(['docker', 'start', KC])
        healthy(KC)
        raise

def main():
    parser = argparse.ArgumentParser()
    for key in ['root', 'env-file', 'lock-token', 'main-revision']: parser.add_argument('--'+key, required=True)
    parser.add_argument('--explicit-reviewed-ssl-cutover', action='store_true', required=True)
    parser.add_argument('--prepare-keycloak-cutover', action='store_true')
    args = parser.parse_args(); root = pathlib.Path(args.root).resolve()
    require(re.fullmatch('[a-f0-9]{40}', args.main_revision), 'coordinated_ssl_main_revision')
    require((root/'.deploy.lock.d/owner').read_text().strip() == args.lock_token, 'coordinated_ssl_deploy_lock')
    env_file = pathlib.Path(args.env_file).resolve(); require(env_file.is_relative_to(root), 'coordinated_ssl_env_path')
    compose = ['docker', 'compose', '--project-name', PROJECT, '--project-directory', str(root), '-f', str(root/'docker-compose.yaml'), '--env-file', str(env_file)]
    resolved = json.loads(run([*compose, 'config', '--format', 'json']))
    pg, kc, accepted, parent_pg = reviewed_plan(root, resolved)
    if args.prepare_keycloak_cutover:
        print(json.dumps(prepare_keycloak_cutover(root, env_file, args.lock_token,
                                                  args.main_revision, resolved, pg, kc, accepted, parent_pg)))
        return
    # Both immutable images are part of the CI/capacity plan; pull before any
    # source stop or new backup so a transport failure cannot interrupt auth.
    for reference in (pg['reference'], kc['reference']): ensure_image_present(reference)
    target = inspect(pg['reference'], 'image'); old = inspect(PG); issuer = inspect(KC)
    source_issuer = inspect(kc['reference'], 'image')
    require(target['Id'] in (accepted['imageConfigId'], pg['reference'].split('@')[1])
            and source_issuer['Id'] in (ACTIVE_KC_CONFIG, ACTIVE_KC.split('@')[1])
            and issuer['Image'] == source_issuer['Id'] and issuer['State']['Status'] == 'running',
            'coordinated_ssl_issuer_or_target_changed')
    if old['Image'] == target['Id']:
        checked_override(resolved, DockerReadOnly()); print(json.dumps({'result': 'ALREADY_INSTALLED', 'productionRowsExported': False})); return
    source = inspect(parent_pg['reference'], 'image')
    require(source['Id'] in (SOURCE_PG_CONFIG, SOURCE_PG.split('@')[1])
            and old['Image'] == source['Id'],
            'coordinated_ssl_inspected_image_identity')
    require(old['State']['Status']=='running', 'coordinated_ssl_source_not_running')
    verify_preparation_receipt(root, env_file, args.lock_token, args.main_revision, pg['reference'])
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
    except GuardError as exc:
        print('Coordinated SSL refresh failed: '+str(exc), file=__import__('sys').stderr)
        raise SystemExit(1)
    except (OSError, ValueError, subprocess.SubprocessError):
        print('Coordinated SSL refresh failed; inspect the protected server evidence.', file=__import__('sys').stderr)
        raise SystemExit(1)
