import base64, importlib.util, json, pathlib, shutil, subprocess, sys, tempfile, unittest
from unittest.mock import patch

HERE=pathlib.Path(__file__).resolve().parent
sys.path.insert(0,str(HERE))
spec=importlib.util.spec_from_file_location('coordinated_ssl_refresh',HERE/'coordinated_ssl_refresh.py')
refresh=importlib.util.module_from_spec(spec);spec.loader.exec_module(refresh)

class CoordinatedSslTests(unittest.TestCase):
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

if __name__=='__main__':unittest.main()
