import base64, importlib.util, json, os, pathlib, shutil, subprocess, sys, tempfile, unittest
from unittest.mock import patch

HERE=pathlib.Path(__file__).resolve().parent
sys.path.insert(0,str(HERE))
spec=importlib.util.spec_from_file_location('coordinated_ssl_refresh',HERE/'coordinated_ssl_refresh.py')
refresh=importlib.util.module_from_spec(spec);spec.loader.exec_module(refresh)

class CoordinatedSslTests(unittest.TestCase):
    def preparation_receipt_fixture(self, root, pg_reference):
        base=root/'.deploy-backups';base.mkdir(mode=0o700)
        directory=base/('ssl-refresh-'+refresh.uuid.uuid4().hex);directory.mkdir(mode=0o700)
        key=b'K'*32
        env=root/'.env';env.write_text('DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64='+base64.b64encode(key).decode()+'\n')
        cipher=directory/'keycloak.tar.gz.enc';cipher.write_bytes(b'synthetic encrypted backup bytes')
        mac_key=refresh.hmac.new(key,b'otziv-keycloak-ssl-refresh-backup-v1',refresh.hashlib.sha256).digest()
        backup={'schema':'otziv-keycloak-ssl-refresh-backup-v1',
                'cipherSha256':refresh.digest(cipher.read_bytes()),
                'payloadSha256':'a'*64,
                'hmacSha256':refresh.hmac.new(mac_key,cipher.read_bytes(),refresh.hashlib.sha256).hexdigest(),
                'artifact':cipher.name,'decryptVerified':True}
        backup_record=directory/'backup.json';backup_record.write_text(json.dumps(backup))
        receipt={'schema':'otziv-keycloak-c35-preparation-v1','result':'PASS',
                 'mainRevision':'d'*40,'lockToken':'owner-token',
                 'sourceKeycloak':refresh.SOURCE_KC,'targetKeycloak':refresh.ACTIVE_KC,
                 'sourcePostgres':refresh.SOURCE_PG,'targetPostgres':pg_reference,
                 'tableCount':102,'recordsPreserved':True,'recordsDigest':'b'*64,
                 'productionRowsExported':False,'backup':backup}
        receipt_path=directory/'prepare-keycloak.json';receipt_path.write_text(json.dumps(receipt))
        for path in (cipher,backup_record,receipt_path):os.chmod(path,0o600)
        return env,receipt_path,cipher,backup_record,receipt

    def split_attestation_fixture(self, root, kc):
        path='infrastructure/runtime-security/proofs/c35-keycloak/publication/registry-attestation-0-payload-0.json'
        target=root/path;target.parent.mkdir(parents=True,exist_ok=True)
        payloads=[b'A'*(4*1024*1024),b'B'*(1024*1024+1)]
        parts=[]
        for number,data in enumerate(payloads,1):
            name=target.name+f'.part{number:03d}'
            (target.parent/name).write_bytes(data)
            parts.append({'file':name,'bytes':len(data),'sha256':refresh.digest(data)})
        sha=refresh.digest(b''.join(payloads))
        metadata={'schema':'otziv-raw-evidence-parts-v1','bytes':sum(map(len,payloads)),
                  'sha256':sha,'parts':parts}
        metadata_path=target.with_name(target.name+'.parts.json')
        metadata_path.write_text(json.dumps(metadata))
        acceptance=root/kc['vertxRefreshAcceptance']['path']
        issuer=json.loads(acceptance.read_text());issuer['files'][path]=sha
        acceptance.write_text(json.dumps(issuer))
        index=root/'infrastructure/runtime-security/reviewed-image-activations.json'
        registry=json.loads(index.read_text())
        keycloak=next(entry for entry in registry['images'] if entry['component']=='keycloak')
        keycloak['vertxRefreshAcceptance']['sha256']=refresh.digest(acceptance.read_bytes())
        keycloak['migrationAcceptance']['sha256']=keycloak['vertxRefreshAcceptance']['sha256']
        index.write_text(json.dumps(registry))
        return target,metadata_path,metadata,payloads

    def candidate_fixture(self, root):
        source=HERE.parents[2]
        parent='infrastructure/runtime-security/c36-parent-postgres.json'
        target=root/parent;target.parent.mkdir(parents=True,exist_ok=True)
        target.write_bytes((source/parent).read_bytes())
        original=json.loads((source/'infrastructure/runtime-security/reviewed-image-activations.json').read_bytes())['images']
        kc=next(value for value in original if value['component']=='keycloak')
        kc_manifest=kc['manifest']['path']
        target=root/kc_manifest;target.parent.mkdir(parents=True,exist_ok=True)
        target.write_bytes((source/kc_manifest).read_bytes())
        issuer_files={}
        for index in range(6):
            path=f'infrastructure/runtime-security/proofs/c35-keycloak/fixture-{index}.json'
            destination=root/path;destination.parent.mkdir(parents=True,exist_ok=True)
            destination.write_text('{}')
            issuer_files[path]=refresh.digest(destination.read_bytes())
        issuer_sources={}
        for index in range(2):
            path=f'infrastructure/runtime-security/c35-fixture-{index}.py'
            destination=root/path;destination.write_text('# source')
            issuer_sources[path]=refresh.digest(destination.read_bytes())
        issuer_accepted={'schema':'otziv-keycloak-vertx-c35-acceptance-v1','result':'PASS',
            'component':'keycloak','reference':refresh.ACTIVE_KC,'imageConfigId':refresh.ACTIVE_KC_CONFIG,
            'parentReference':refresh.SOURCE_KC,'parentConfigId':refresh.SOURCE_KC_CONFIG,
            'postgres':{'reference':refresh.SOURCE_PG,'imageConfigId':refresh.SOURCE_PG_CONFIG},
            'commit':kc['commit'],'run':kc['run'],'attempt':kc['attempt'],
            'files':issuer_files,'executedSources':issuer_sources}
        issuer='infrastructure/runtime-security/proofs/c35-keycloak/acceptance.json'
        target=root/issuer;target.write_text(json.dumps(issuer_accepted))
        kc['vertxRefreshAcceptance']={'path':issuer,'sha256':refresh.digest(target.read_bytes())}
        kc['migrationAcceptance']=kc['vertxRefreshAcceptance'].copy()
        candidate='ghcr.io/claidd/otziv-security@sha256:'+'a'*64
        candidate_id='sha256:'+'b'*64
        pg={'component':'postgres','reference':candidate,'commit':'c'*40,'run':'456','attempt':'1',
            'manifest':{'path':refresh.C36_PG_MANIFEST}}
        pg_manifest=root/refresh.C36_PG_MANIFEST;pg_manifest.write_text('{}')
        pg['manifest']['sha256']=refresh.digest(pg_manifest.read_bytes())
        executed={}
        for name in ('postgres-c36-inspection.py','ssl-refresh-inspection.py','postgres-c36-adjudication.mjs',
                     'postgres-c36-rehearsal.py'):
            path='infrastructure/runtime-security/'+name
            destination=root/path;destination.parent.mkdir(parents=True,exist_ok=True)
            destination.write_text('# test source '+name)
            executed[path]=refresh.digest(destination.read_bytes())
        rehearsal={'schema':'otziv-postgres-c36-rehearsal-v1','result':'PASS',
            'sourcePostgres':refresh.SOURCE_PG,'sourcePostgresConfig':refresh.SOURCE_PG_CONFIG,
            'keycloak':refresh.ACTIVE_KC,'keycloakConfig':refresh.ACTIVE_KC_CONFIG,
            'productionWrites':False,'publishedPorts':0,'privateRowsPublished':False,
            'sourceScope':'LOCAL_PROD_LIKE_KEYCLOAK',
            'executedScriptSha256':executed['infrastructure/runtime-security/postgres-c36-rehearsal.py']}
        replay={**rehearsal,'candidatePostgres':candidate,'candidatePostgresLocalId':candidate_id,
            'candidate':refresh.ACTIVE_KC,'candidateLocalId':refresh.ACTIVE_KC_CONFIG,'tableCount':102,
            'ownedResourcesRemaining':{'container':0,'volume':0,'network':0},'cleanupErrors':[],
            'checks':[{'name':name,'passed':True} for name in refresh.REQUIRED_REHEARSAL_CHECKS]}
        files={}
        for name,value in (('capture',rehearsal),('replay',replay)):
            path='infrastructure/runtime-security/proofs/c36-postgres/rehearsal/'+name+'.json'
            destination=root/path;destination.parent.mkdir(parents=True,exist_ok=True)
            destination.write_text(json.dumps(value))
            files[path]=refresh.digest(destination.read_bytes())
        for index in range(6):
            path=f'infrastructure/runtime-security/proofs/c36-postgres/publication/file-{index}.json'
            destination=root/path;destination.parent.mkdir(parents=True,exist_ok=True)
            destination.write_text('{}')
            files[path]=refresh.digest(destination.read_bytes())
        accepted={'schema':'otziv-postgres-c36-acceptance-v1','result':'PASS','reference':candidate,
            'parentReference':refresh.SOURCE_PG,'parentConfigId':refresh.SOURCE_PG_CONFIG,
            'imageConfigId':candidate_id,'commit':pg['commit'],'run':pg['run'],'attempt':pg['attempt'],
            'pair':{'postgres':{'reference':candidate,'imageConfigId':candidate_id},
                    'keycloak':{'reference':refresh.ACTIVE_KC,'imageConfigId':refresh.ACTIVE_KC_CONFIG}},
            'capturePath':'infrastructure/runtime-security/proofs/c36-postgres/rehearsal/capture.json',
            'replayPath':'infrastructure/runtime-security/proofs/c36-postgres/rehearsal/replay.json',
            'files':files,'executedSources':executed}
        path='infrastructure/runtime-security/proofs/c36-postgres/acceptance.json'
        destination=root/path;destination.write_text(json.dumps(accepted))
        pg['postgresC36Acceptance']={'path':path,'sha256':refresh.digest(destination.read_bytes())}
        pg['databaseTransition']=pg['postgresC36Acceptance'].copy()
        activation=root/'infrastructure/runtime-security/reviewed-image-activations.json'
        activation.write_text(json.dumps({'images':[pg,kc]}))
        resolved={'services':{'keycloak-postgres':{'image':candidate},'keycloak':{'image':refresh.ACTIVE_KC}}}
        return pg,kc,accepted,resolved

    def test_no_implicit_cutover(self):
        run=subprocess.run([sys.executable,'-B',str(HERE/'coordinated_ssl_refresh.py')],capture_output=True,text=True)
        self.assertNotEqual(run.returncode,0)
        self.assertIn('--explicit-reviewed-ssl-cutover',run.stderr)

    def test_second_cutover_uses_verified_local_image_without_repull(self):
        with patch.object(refresh,'inspect',return_value={'Id':refresh.ACTIVE_KC_CONFIG}) as image, \
             patch.object(refresh,'run') as docker:
            refresh.ensure_image_present(refresh.ACTIVE_KC)
            image.assert_called_once_with(refresh.ACTIVE_KC,'image')
            docker.assert_not_called()
        with patch.object(refresh,'inspect',side_effect=refresh.GuardError('image_absent')), \
             patch.object(refresh,'run') as docker:
            refresh.ensure_image_present(refresh.ACTIVE_KC)
            docker.assert_called_once_with(['docker','pull','--platform','linux/amd64',refresh.ACTIVE_KC],timeout=300)

    def test_guard_failure_reports_code_without_lock_token(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=pathlib.Path(temporary);lock=root/'.deploy.lock.d';lock.mkdir()
            (lock/'owner').write_text('private-owner-token')
            env=root/'.env';env.write_text('')
            command=[sys.executable,'-B',str(HERE/'coordinated_ssl_refresh.py'),
                     '--root',str(root),'--env-file',str(env),'--lock-token','wrong-token',
                     '--main-revision','d'*40,'--explicit-reviewed-ssl-cutover']
            result=subprocess.run(command,capture_output=True,text=True)
            self.assertEqual(result.returncode,1)
            self.assertIn('coordinated_ssl_deploy_lock',result.stderr)
            self.assertNotIn('private-owner-token',result.stderr)
            self.assertNotIn('wrong-token',result.stderr)

    def test_proof_tampering_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=pathlib.Path(temporary);data=b'{"result":"PASS"}'
            (root/'proof.json').write_bytes(data)
            self.assertEqual(refresh.proof(root,{'path':'proof.json','sha256':refresh.digest(data)})['result'],'PASS')
            (root/'proof.json').write_bytes(b'{"result":"FAIL"}')
            with self.assertRaises(refresh.GuardError):refresh.proof(root,{'path':'proof.json','sha256':refresh.digest(data)})

    def test_external_proof_cannot_grant_database_authority(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=pathlib.Path(temporary)/'root';root.mkdir()
            data=b'{}';(root.parent/'outside.json').write_bytes(data)
            with self.assertRaises(refresh.GuardError):refresh.proof(root,{'path':'../outside.json','sha256':refresh.digest(data)})

    def test_current_c23_pair_is_rejected_before_docker(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=pathlib.Path(temporary)
            pg,_,_,resolved=self.candidate_fixture(root)
            index=root/'infrastructure/runtime-security/reviewed-image-activations.json'
            registry=json.loads(index.read_text())
            registry['images'][0]['manifest']['path']='infrastructure/runtime-security/reviewed-images-c23-postgres.json'
            index.write_text(json.dumps(registry))
            with patch.object(refresh,'run') as docker:
                with self.assertRaisesRegex(refresh.GuardError,'^coordinated_ssl_unreviewed_generation$'):
                    refresh.reviewed_plan(root,resolved)
                docker.assert_not_called()

    def test_c36_pair_requires_exact_acceptance_rehearsal_and_compose(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=pathlib.Path(temporary)
            pg,kc,accepted,resolved=self.candidate_fixture(root)
            with patch.object(refresh,'run') as docker:
                planned=refresh.reviewed_plan(root,resolved)
                self.assertEqual(planned[0]['reference'],pg['reference'])
                self.assertEqual(planned[1]['reference'],kc['reference'])
                self.assertEqual(planned[3]['reference'],refresh.SOURCE_PG)
                docker.assert_not_called()
            resolved['services']['keycloak-postgres']['image']=kc['reference']
            with self.assertRaisesRegex(refresh.GuardError,'coordinated_ssl_compose_reference_changed'):
                refresh.reviewed_plan(root,resolved)
            resolved['services']['keycloak-postgres']['image']=pg['reference']
            source=root/accepted['capturePath'];source.write_text(source.read_text()+' ')
            with self.assertRaisesRegex(refresh.GuardError,'coordinated_ssl_evidence_changed'):
                refresh.reviewed_plan(root,resolved)

    def test_split_attestation_validates_original_and_each_part_without_reconstruction(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=pathlib.Path(temporary)
            _,kc,_,resolved=self.candidate_fixture(root)
            target,metadata_path,metadata,payloads=self.split_attestation_fixture(root,kc)
            with patch.object(refresh,'run') as docker:
                self.assertEqual(refresh.reviewed_plan(root,resolved)[1]['reference'],kc['reference'])
                docker.assert_not_called()
            self.assertFalse(target.exists())
            second=target.with_name(metadata['parts'][1]['file'])
            second.write_bytes(b'C'*len(payloads[1]))
            with self.assertRaisesRegex(refresh.GuardError,'coordinated_ssl_evidence_changed'):
                refresh.reviewed_plan(root,resolved)
            second.write_bytes(payloads[1])
            metadata_path.write_text(json.dumps({**metadata,'bytes':metadata['bytes']+1}))
            with self.assertRaisesRegex(refresh.GuardError,'coordinated_ssl_evidence_changed'):
                refresh.reviewed_plan(root,resolved)
            metadata_path.write_text(json.dumps(metadata))
            extra=target.with_name(target.name+'.part003');extra.write_bytes(b'X')
            with self.assertRaisesRegex(refresh.GuardError,'coordinated_ssl_evidence_changed'):
                refresh.reviewed_plan(root,resolved)
            extra.unlink()
            target.write_bytes(b''.join(payloads))
            with self.assertRaisesRegex(refresh.GuardError,'coordinated_ssl_evidence_changed'):
                refresh.reviewed_plan(root,resolved)
            target.unlink()
            second.unlink()
            with self.assertRaisesRegex(refresh.GuardError,'coordinated_ssl_evidence_changed'):
                refresh.reviewed_plan(root,resolved)

    def test_repository_split_evidence_passes_plan_without_docker(self):
        root=HERE.parents[2]
        entries={entry['component']:entry for entry in json.loads(
            (root/'infrastructure/runtime-security/reviewed-image-activations.json').read_text())['images']}
        resolved={'services':{'keycloak-postgres':{'image':entries['postgres']['reference']},
                              'keycloak':{'image':entries['keycloak']['reference']}}}
        with patch.object(refresh,'run') as docker:
            self.assertEqual(refresh.reviewed_plan(root,resolved)[1]['reference'],refresh.ACTIVE_KC)
            docker.assert_not_called()

    def test_keycloak_preparation_backs_up_before_switch_and_restores_c26(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=pathlib.Path(temporary)
            pg,kc,accepted,resolved=self.candidate_fixture(root)
            (root/'.deploy-backups').mkdir()
            lock=root/'.deploy.lock.d';lock.mkdir();(lock/'owner').write_text('owner-token')
            env=root/'.env';env.write_text('')
            state={'keycloak':'running'};calls=[]
            def command(args,**kwargs):
                calls.append(tuple(args))
                if args[:3]==['docker','stop','--time']:state['keycloak']='exited'
                if args[:2]==['docker','start']:state['keycloak']='running'
                return b''
            def inspection(name,kind='container'):
                ids={pg['reference']:'sha256:'+'b'*64,
                     kc['reference']:refresh.ACTIVE_KC_CONFIG,
                     refresh.SOURCE_PG:refresh.SOURCE_PG_CONFIG,
                     refresh.SOURCE_KC:refresh.SOURCE_KC_CONFIG}
                if kind=='image':return {'Id':ids[name]}
                if name==refresh.PG:return {'Image':refresh.SOURCE_PG_CONFIG,
                                            'State':{'Status':'running','Health':{'Status':'healthy'}}}
                return {'Image':refresh.SOURCE_KC_CONFIG,
                        'State':{'Status':state['keycloak'],'Health':{'Status':'healthy'}}}
            fingerprints={f'table_{n}':'a'*64 for n in range(102)}
            def encrypted(_root,directory,_env):
                calls.append(('encrypted_backup',))
                (directory/'keycloak.tar.gz.enc').write_bytes(b'cipher')
                return {'decryptVerified':True,'cipherSha256':refresh.digest(b'cipher')}
            with patch.object(refresh,'run',side_effect=command), \
                 patch.object(refresh,'inspect',side_effect=inspection), \
                 patch.object(refresh,'checked_override'), \
                 patch.object(refresh,'healthy'), \
                 patch.object(refresh,'table_fingerprints',return_value=fingerprints), \
                 patch.object(refresh,'encrypted_backup',side_effect=encrypted):
                result=refresh.prepare_keycloak_cutover(root,env,'owner-token','d'*40,
                                                         resolved,pg,kc,accepted,{'reference':refresh.SOURCE_PG})
            self.assertEqual(result['result'],'PREPARED')
            self.assertEqual(state['keycloak'],'running')
            self.assertEqual(sum(x[:2]==('docker','pull') for x in calls),0)
            self.assertLess(next(i for i,x in enumerate(calls) if x==('encrypted_backup',)),
                            next(i for i,x in enumerate(calls) if x[:2]==('docker','start')))
            receipt=json.loads(next((root/'.deploy-backups').glob('ssl-refresh-*/prepare-keycloak.json')).read_text())
            self.assertEqual(receipt['tableCount'],102)
            self.assertTrue(receipt['backup']['decryptVerified'])

    def test_keycloak_preparation_failure_restarts_c26_without_receipt(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=pathlib.Path(temporary)
            pg,kc,accepted,resolved=self.candidate_fixture(root)
            (root/'.deploy-backups').mkdir()
            lock=root/'.deploy.lock.d';lock.mkdir();(lock/'owner').write_text('owner-token')
            state={'keycloak':'running'}
            def command(args,**kwargs):
                if args[:3]==['docker','stop','--time']:state['keycloak']='exited'
                if args[:2]==['docker','start']:state['keycloak']='running'
                return b''
            def inspection(name,kind='container'):
                ids={pg['reference']:'sha256:'+'b'*64,kc['reference']:refresh.ACTIVE_KC_CONFIG,
                     refresh.SOURCE_PG:refresh.SOURCE_PG_CONFIG,refresh.SOURCE_KC:refresh.SOURCE_KC_CONFIG}
                if kind=='image':return {'Id':ids[name]}
                if name==refresh.PG:return {'Image':refresh.SOURCE_PG_CONFIG,
                                            'State':{'Status':'running','Health':{'Status':'healthy'}}}
                return {'Image':refresh.SOURCE_KC_CONFIG,
                        'State':{'Status':state['keycloak'],'Health':{'Status':'healthy'}}}
            with patch.object(refresh,'run',side_effect=command), \
                 patch.object(refresh,'inspect',side_effect=inspection), \
                 patch.object(refresh,'checked_override'), \
                 patch.object(refresh,'healthy'), \
                 patch.object(refresh,'table_fingerprints',return_value={f'table_{n}':'a'*64 for n in range(102)}), \
                 patch.object(refresh,'encrypted_backup',side_effect=refresh.GuardError('backup_failed')):
                with self.assertRaisesRegex(refresh.GuardError,'backup_failed'):
                    refresh.prepare_keycloak_cutover(root,root/'.env','owner-token','d'*40,
                                                     resolved,pg,kc,accepted,{'reference':refresh.SOURCE_PG})
            self.assertEqual(state['keycloak'],'running')
            self.assertFalse(list((root/'.deploy-backups').glob('ssl-refresh-*/prepare-keycloak.json')))

    def test_cutover_requires_unique_authenticated_current_preparation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=pathlib.Path(temporary)
            pg_reference='ghcr.io/claidd/otziv-security@sha256:'+'a'*64
            env,receipt_path,cipher,backup_record,receipt=self.preparation_receipt_fixture(root,pg_reference)
            self.assertEqual(refresh.verify_preparation_receipt(root,env,'owner-token','d'*40,pg_reference),receipt)
            self.assertFalse(cipher.is_symlink())
            old_cipher=cipher.read_bytes();cipher.write_bytes(b'changed encrypted backup bytes!!')
            with self.assertRaisesRegex(refresh.GuardError,'coordinated_ssl_preparation_backup_changed'):
                refresh.verify_preparation_receipt(root,env,'owner-token','d'*40,pg_reference)
            cipher.write_bytes(old_cipher)
            with self.assertRaisesRegex(refresh.GuardError,'coordinated_ssl_preparation_missing_or_ambiguous'):
                refresh.verify_preparation_receipt(root,env,'other-token','d'*40,pg_reference)
            with self.assertRaisesRegex(refresh.GuardError,'coordinated_ssl_preparation_missing_or_ambiguous'):
                refresh.verify_preparation_receipt(root,env,'owner-token','e'*40,pg_reference)
            receipt_path.write_text(json.dumps({**receipt,'targetKeycloak':refresh.SOURCE_KC}))
            with self.assertRaisesRegex(refresh.GuardError,'coordinated_ssl_preparation_identity'):
                refresh.verify_preparation_receipt(root,env,'owner-token','d'*40,pg_reference)
            receipt_path.write_text(json.dumps(receipt))
            backup_record.write_text(json.dumps({**receipt['backup'],'decryptVerified':False}))
            with self.assertRaisesRegex(refresh.GuardError,'coordinated_ssl_preparation_backup_metadata'):
                refresh.verify_preparation_receipt(root,env,'owner-token','d'*40,pg_reference)
            backup_record.write_text(json.dumps(receipt['backup']))
            second=root/'.deploy-backups'/('ssl-refresh-'+refresh.uuid.uuid4().hex)
            shutil.copytree(receipt_path.parent,second)
            with self.assertRaisesRegex(refresh.GuardError,'coordinated_ssl_preparation_missing_or_ambiguous'):
                refresh.verify_preparation_receipt(root,env,'owner-token','d'*40,pg_reference)

    @unittest.skipUnless(os.name=='posix','Unix file mode gate')
    def test_preparation_receipt_must_be_private(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=pathlib.Path(temporary)
            pg_reference='ghcr.io/claidd/otziv-security@sha256:'+'a'*64
            env,receipt_path,_,_,_=self.preparation_receipt_fixture(root,pg_reference)
            os.chmod(receipt_path,0o644)
            with self.assertRaisesRegex(refresh.GuardError,'coordinated_ssl_preparation_permissions'):
                refresh.verify_preparation_receipt(root,env,'owner-token','d'*40,pg_reference)

    def test_normal_cutover_rejects_missing_receipt_before_service_stop(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=pathlib.Path(temporary)
            pg,kc,accepted,resolved=self.candidate_fixture(root)
            lock=root/'.deploy.lock.d';lock.mkdir();(lock/'owner').write_text('owner-token')
            env=root/'.env';env.write_text('')
            def inspection(name,kind='container'):
                if kind=='image':
                    return {'Id':({'pg_target':'sha256:'+'b'*64,
                                    'issuer_target':refresh.ACTIVE_KC_CONFIG,
                                    'pg_source':refresh.SOURCE_PG_CONFIG})[
                                        'pg_target' if name==pg['reference'] else
                                        'issuer_target' if name==kc['reference'] else 'pg_source']}
                if name==refresh.PG:return {'Image':refresh.SOURCE_PG_CONFIG,'State':{'Status':'running'}}
                return {'Image':refresh.ACTIVE_KC_CONFIG,'State':{'Status':'running'}}
            argv=['coordinated_ssl_refresh.py','--root',str(root),'--env-file',str(env),
                  '--lock-token','owner-token','--main-revision','d'*40,
                  '--explicit-reviewed-ssl-cutover']
            with patch.object(sys,'argv',argv), \
                 patch.object(refresh,'run',return_value=json.dumps(resolved).encode()) as docker, \
                 patch.object(refresh,'ensure_image_present'), \
                 patch.object(refresh,'inspect',side_effect=inspection), \
                 patch.object(refresh,'verify_preparation_receipt',side_effect=refresh.GuardError('receipt_missing')):
                with self.assertRaisesRegex(refresh.GuardError,'receipt_missing'):
                    refresh.main()
                self.assertFalse(any('stop' in call.args[0] for call in docker.call_args_list))

    def test_c36_rehearsal_must_prove_same_volume_rollback(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=pathlib.Path(temporary)
            pg,_,accepted,resolved=self.candidate_fixture(root)
            path=root/accepted['replayPath'];replay=json.loads(path.read_text())
            replay['checks']=[x for x in replay['checks'] if x['name']!='same_volume_rollback_preserves_all_post_upgrade_writes']
            path.write_text(json.dumps(replay))
            accepted['files'][accepted['replayPath']]=refresh.digest(path.read_bytes())
            acceptance=root/pg['postgresC36Acceptance']['path'];acceptance.write_text(json.dumps(accepted))
            index=root/'infrastructure/runtime-security/reviewed-image-activations.json'
            registry=json.loads(index.read_text());registry['images'][0]['postgresC36Acceptance']['sha256']=refresh.digest(acceptance.read_bytes())
            registry['images'][0]['databaseTransition']['sha256']=refresh.digest(acceptance.read_bytes())
            index.write_text(json.dumps(registry))
            with self.assertRaisesRegex(refresh.GuardError,'coordinated_ssl_rehearsal_checks_missing'):
                refresh.reviewed_plan(root,resolved)

    def test_wrong_parent_generation_is_rejected_before_docker(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=pathlib.Path(temporary);path=root/'infrastructure/runtime-security/c36-parent-postgres.json'
            path.parent.mkdir(parents=True);path.write_bytes(b'{}')
            with patch.object(refresh,'run') as docker:
                with self.assertRaisesRegex(refresh.GuardError,'coordinated_ssl_parent_changed'):
                    refresh.reviewed_plan(root,{})
                docker.assert_not_called()

    @unittest.skipUnless(shutil.which('openssl'),'OpenSSL required for independent real crypto round-trip')
    def test_backup_is_encrypted_verified_and_plaintext_removed(self):
        actual_run=subprocess.run
        with tempfile.TemporaryDirectory() as temporary:
            directory=pathlib.Path(temporary);env=directory/'env';env.write_text('DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64='+base64.b64encode(b'K'*32).decode()+'\n')
            fixture=b'PGDMP'+b'local synthetic keycloak fixture\n'*12
            def producer(args,**kwargs):
                if args[0]=='docker':
                    if 'pg_restore' in args:return subprocess.CompletedProcess(args,0)
                    if kwargs.get('stdout') not in (None,subprocess.PIPE):kwargs['stdout'].write(fixture);return subprocess.CompletedProcess(args,0)
                    return subprocess.CompletedProcess(args,0,stdout=b'-- Synthetic globals fixture\n',stderr=b'')
                return actual_run(args,**kwargs)
            with patch.object(refresh.subprocess,'run',side_effect=producer):record=refresh.encrypted_backup(directory,directory,env)
            cipher=(directory/record['artifact']).read_bytes()
            self.assertNotIn(fixture,cipher);self.assertTrue(record['decryptVerified'])
            self.assertEqual(record['cipherSha256'],refresh.digest(cipher))
            self.assertEqual(len(record['hmacSha256']),64)
            for name in ('keycloak.dump','globals.sql','payload.tar.gz','key.pass'):self.assertFalse((directory/name).exists())

    def test_failed_backup_clears_partial_plaintext_dump(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory=pathlib.Path(temporary)
            env=directory/'env'
            env.write_text('DEPLOY_DB_BACKUP_ENCRYPTION_KEY_BASE64='+base64.b64encode(b'K'*32).decode()+'\n')
            def broken_dump(args,**kwargs):
                kwargs['stdout'].write(b'PGDMP partial private data')
                raise subprocess.CalledProcessError(1,args)
            with patch.object(refresh.subprocess,'run',side_effect=broken_dump):
                with self.assertRaises(subprocess.CalledProcessError):
                    refresh.encrypted_backup(directory,directory,env)
            for name in ('keycloak.dump','globals.sql','payload.tar.gz','key.pass',
                         'keycloak.tar.gz.enc','backup.json'):
                self.assertFalse((directory/name).exists())

if __name__=='__main__':unittest.main()
