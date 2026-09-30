import base64, importlib.util, json, pathlib, shutil, subprocess, sys, tempfile, unittest
from unittest.mock import patch

HERE=pathlib.Path(__file__).resolve().parent
sys.path.insert(0,str(HERE))
spec=importlib.util.spec_from_file_location('coordinated_ssl_refresh',HERE/'coordinated_ssl_refresh.py')
refresh=importlib.util.module_from_spec(spec);spec.loader.exec_module(refresh)

class CoordinatedSslTests(unittest.TestCase):
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

    def test_accepted_pair_is_bound_before_docker(self):
        root=HERE.parents[2]
        entries=json.loads((root/'infrastructure/runtime-security/reviewed-image-activations.json').read_bytes())['images']
        pair={x['component']:x for x in entries if x['component'] in ('postgres','keycloak')}
        resolved={'services':{'keycloak-postgres':{'image':pair['postgres']['reference']},'keycloak':{'image':pair['keycloak']['reference']}}}
        with patch.object(refresh,'run') as docker:
            pg,kc,accepted=refresh.reviewed_plan(root,resolved)
            self.assertEqual(pg['reference'],accepted['pair']['postgres']['reference'])
            self.assertEqual(kc['reference'],accepted['pair']['keycloak']['reference'])
            resolved['services']['keycloak-postgres']['image']=kc['reference']
            with self.assertRaises(refresh.GuardError):refresh.reviewed_plan(root,resolved)
            docker.assert_not_called()

    def test_wrong_parent_generation_is_rejected_before_docker(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=pathlib.Path(temporary);path=root/'infrastructure/runtime-security/c23-parent-activations.json'
            path.parent.mkdir(parents=True);path.write_bytes(b'{}')
            with patch.object(refresh,'run') as docker:
                with self.assertRaises(refresh.GuardError):refresh.reviewed_plan(root,{})
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
