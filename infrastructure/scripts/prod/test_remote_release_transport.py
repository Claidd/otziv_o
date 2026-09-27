import hashlib,io,json,subprocess,sys,tempfile,unittest
from pathlib import Path
from unittest.mock import Mock,patch
from ci_artifacts import storage_url
from remote_release_transport import SignedArtifacts,invoke,validate_remote_plan,remote,PORT
from release_ci import GateError
import test_ci_release as fixture_module

class RemoteTransportTest(unittest.TestCase):
    def test_transmitted_helpers_validate_new_manifest_in_an_isolated_interpreter(self):
        from ci_test_reuse import DECISION,JOBS
        fixture=fixture_module.ReleaseArtifactsTest();fixture.setUp();self.addCleanup(fixture.doCleanups)
        release=fixture.release
        release['testEvidence']={'schema':DECISION,'revision':release['revision'],'runId':123,'runAttempt':1,
            'tree':'f'*40,'mode':'shadow','checks':{scope:{'reused':False} for scope in JOBS}}
        record={'host':'server.example','user':'hunt','port':22,'path':'/docker','key':'key','known_hosts':'known','owner':'a'*32}
        with patch('remote_release_transport.subprocess.run',return_value=Mock(returncode=0,stdout=b'{}')) as transport:
            invoke(record,'prepare',release=release,links=[],releaseReserveBytes=0)
        packet=json.loads(transport.call_args.kwargs['input'])
        with tempfile.TemporaryDirectory() as directory:
            for name,source in packet['modules'].items():
                (Path(directory)/(name+'.py')).write_text(source,encoding='utf-8')
            script=('import json,sys;sys.path.insert(0,sys.argv[1]);'
                    'from ci_release import validate_release,capacity_plan;'
                    'value=json.load(sys.stdin);validate_release(value,value["revision"]);'
                    'assert capacity_plan(value)["revision"]==value["revision"]')
            for valid in (True,False):
                if not valid:packet['release']['testEvidence']['runAttempt']=2
                result=subprocess.run([sys.executable,'-I','-B','-c',script,directory],
                    input=json.dumps(packet['release']),capture_output=True,text=True,timeout=30)
                with self.subTest(valid=valid):
                    if valid:self.assertEqual(0,result.returncode,result.stderr)
                    else:
                        self.assertNotEqual(0,result.returncode)
                        self.assertIn('Test transfer does not belong to current release',result.stderr)

    def test_signed_download_has_no_account_auth_and_checks_every_byte(self):
        data=b'bounded immutable artifact';digest='sha256:'+hashlib.sha256(data).hexdigest()
        item={'id':123,'size_in_bytes':len(data),'digest':digest}
        transport=SignedArtifacts([{'metadata':item,'url':'https://store.blob.core.windows.net/image?sig=private'}])
        response=io.BytesIO(data);opener=Mock();opener.open.return_value=response
        with tempfile.TemporaryDirectory() as directory,patch('remote_release_transport.urllib.request.build_opener',return_value=opener):
            target=Path(directory)/'image.zip';transport.download(item,target,100)
            self.assertEqual(data,target.read_bytes())
        request=opener.open.call_args.args[0]
        self.assertFalse(request.has_header('Authorization'))
        self.assertIs(opener.open.call_args.kwargs.get('timeout'),180)

    def test_unsafe_url_mismatch_size_and_checksum_fail_closed(self):
        for kind in ['url','checksum','size']:
            item={'id':1,'size_in_bytes':3,'digest':'sha256:'+hashlib.sha256(b'abc').hexdigest()}
            url='https://store.blob.core.windows.net/file'
            if kind=='url':url='https://attacker.invalid/file'
            if kind=='checksum':item['digest']='sha256:'+'f'*64
            if kind=='size':item['size_in_bytes']=2
            opener=Mock();opener.open.return_value=io.BytesIO(b'abc')
            with self.subTest(kind=kind),tempfile.TemporaryDirectory() as directory,patch('remote_release_transport.urllib.request.build_opener',return_value=opener),self.assertRaises(GateError):
                SignedArtifacts([{'metadata':item,'url':url}]).download(item,Path(directory)/'image.zip',10)

    def test_links_are_bound_to_exact_artifact_ids(self):
        client=SignedArtifacts([{'metadata':{'id':1},'url':'unused'}])
        for path in ['/actions/artifacts/2','/actions/artifacts/1/zip','https://other/path']:
            with self.assertRaises(GateError):client.get(path)
        with self.assertRaises(GateError):SignedArtifacts([{'metadata':{'id':1}},{'metadata':{'id':1}}])

    def test_remote_invocation_confines_secrets_to_stdin(self):
        record={'host':'server.example','user':'hunt','port':22,'path':'/docker','key':'key','known_hosts':'known','owner':'a'*32}
        result=Mock(returncode=0,stdout=b'{}')
        with patch('remote_release_transport.subprocess.run',return_value=result) as run:
            invoke(record,'prepare',links=[{'url':'SIGNED_PRIVATE_URL'}])
        args=run.call_args.args[0];packet=json.loads(run.call_args.kwargs['input'])
        self.assertNotIn('SIGNED_PRIVATE_URL',' '.join(args))
        self.assertIn('StrictHostKeyChecking=yes',args)
        self.assertIn('SIGNED_PRIVATE_URL',str(packet['links']))
        self.assertNotIn('token',packet)

    def test_remote_plan_rejects_wrong_owner_and_any_changed_image(self):
        fixture=fixture_module.ReleaseArtifactsTest();fixture.setUp();self.addCleanup(fixture.doCleanups)
        from ci_release import capacity_plan
        release=fixture.release;owner='a'*32
        refs={row['component']:f"127.0.0.1:{PORT}/otziv-prepared/ci-{row['component']}@{row['manifestDigest']}" for row in release['images']}
        expected=capacity_plan(release,refs)
        status={'registry':{'owner':owner,'port':PORT,'state':'readonly'},'capacity':expected}
        with patch('remote_release_transport.invoke',return_value=status):
            self.assertEqual(expected,validate_remote_plan({'owner':owner},release,expected))
            with self.assertRaises(GateError):validate_remote_plan({'owner':'b'*32},release,expected)
            changed=json.loads(json.dumps(expected));changed['images'][-1]['configId']='sha256:'+'f'*64
            with self.assertRaises(GateError):validate_remote_plan({'owner':owner},release,changed)

    def test_stop_before_registry_creation_is_safe(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertTrue(remote({'action':'stop'},Path(directory))['notCreated'])

    def test_failed_partial_registry_stops_only_owned_container(self):
        owner='a'*32
        record={'owner':owner,'container':'otziv-release-'+owner,'volume':'otziv-release-'+owner+'-data','port':PORT,'state':'creating'}
        for foreign in [False,True]:
            with self.subTest(foreign=foreign),tempfile.TemporaryDirectory() as directory:
                path=Path(directory);(path/'registry.json').write_text(json.dumps(record))
                def docker(*args):
                    if args[0]=='ps':return (record['container']+'\n').encode()
                    if args[0]=='inspect':return json.dumps([{'Config':{'Labels':{'otziv.release.owner':'b'*32 if foreign else owner}}}]).encode()
                    return b''
                with patch('remote_release_transport.release_registry.run',side_effect=docker) as run:
                    if foreign:
                        with self.assertRaises(GateError):remote({'action':'stop','owner':owner},path)
                        self.assertFalse(any(c.args[0]=='stop' for c in run.call_args_list))
                    else:
                        self.assertTrue(remote({'action':'stop','owner':owner},path)['stopped'])
                        self.assertTrue(any(c.args[0]=='stop' for c in run.call_args_list))
                    self.assertFalse(any(c.args[0] in ('rm','volume') for c in run.call_args_list))

if __name__=='__main__':unittest.main()
