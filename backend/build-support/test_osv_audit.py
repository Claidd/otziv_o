import importlib.util, json, tempfile, unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('osv_audit',Path(__file__).with_name('osv_audit.py'))
osv=importlib.util.module_from_spec(spec);spec.loader.exec_module(osv)
PURL='pkg:maven/example/component@1.0'

class OsvAuditTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        for path in osv.REPORTS:
            p=self.root/path;p.parent.mkdir(parents=True,exist_ok=True)
            p.write_text(json.dumps({'dependencies':[{'fileName':'component.jar','packages':[{'id':PURL}]}]}))
            pom=p.parent.parent/'pom.xml'
            pom.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>fixture</artifactId><version>1</version></project>')
            scope={'schema':'otziv-plugin-audit-scope-v1','project':'example:fixture:jar:1','projectPomSha256':osv._scope.digest(pom),
                   'excludedDefaultSite':False,'reason':'fixture-full-audit','originalBuildPluginRoots':['example:plugin:1'],
                   'buildPluginRoots':['example:plugin:1'],'reportPluginRoots':[],'extensionPluginRoots':[]}
            (p.parent/'plugin-audit-scope.json').write_text(json.dumps(scope))
    def test_every_report_is_required_even_when_other_reports_cover_same_packages(self):
        packages,reports=osv.inventory(self.root);self.assertEqual(packages,[PURL]);self.assertEqual(len(reports),5)
        (self.root/osv.REPORTS[-1]).unlink()
        with self.assertRaises(OSError):osv.inventory(self.root)
    def test_empty_or_unidentified_maven_artifacts_fail_closed(self):
        p=self.root/osv.REPORTS[0]
        for dependencies in [[],[{'fileName':'component.jar'}]]:
            p.write_text(json.dumps({'dependencies':dependencies}))
            with self.assertRaises(osv.AuditError):osv.inventory(self.root)
    def test_backend_scope_receipt_is_mandatory_current_and_cannot_drop_another_root(self):
        path=self.root/'backend/target/plugin-audit-scope.json';original=path.read_text()
        for mutation in [lambda x:x.update(projectPomSha256='0'*64),lambda x:x.update(project='other:project:jar:1'),
                         lambda x:x.update(buildPluginRoots=[]),lambda x:x.update(schema='unknown')]:
            value=json.loads(original);mutation(value);path.write_text(json.dumps(value))
            with self.assertRaises(osv.AuditError):osv.inventory(self.root)
        path.unlink()
        with self.assertRaises(osv.AuditError):osv.inventory(self.root)
    def test_pagination_is_exhausted_and_queries_remain_bound_to_package(self):
        calls=[]
        def fetch(path,payload):
            calls.append(payload)
            return {'results':[{'vulns':[{'id':'GHSA-test-'+str(len(calls))}],**({'next_page_token':'next'} if len(calls)==1 else {})}]}
        findings,_=osv.query([PURL],fetch)
        self.assertEqual(findings[PURL],{'GHSA-test-1','GHSA-test-2'})
        self.assertEqual(calls[1]['queries'],[{'package':{'purl':PURL},'page_token':'next'}])
    def test_partial_error_or_looping_api_replies_fail_closed(self):
        for response in [{},{'results':[]},{'results':[{'error':'failed'}]},{'results':[{'vulns':None}]},{'results':[{'next_page_token':'repeat'}]}]:
            with self.assertRaises(osv.AuditError):osv.query([PURL],lambda *args:response)
    def test_known_cvss_vectors_and_severity(self):
        self.assertEqual(osv.cvss3('CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H'),9.8)
        self.assertEqual(osv.cvss3('CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:L/I:L/A:N'),6.5)
        self.assertEqual(osv.cvss3('CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H'),10)
        self.assertTrue(osv.blocking({}))
        self.assertTrue(osv.blocking({'database_specific':{'severity':'HIGH'}}))
        self.assertFalse(osv.blocking({'database_specific':{'severity':'MODERATE'}}))
        self.assertFalse(osv.blocking({'withdrawn':'2026-09-01T00:00:00Z'}))
    def test_complete_high_finding_blocks_and_raw_advisory_is_retained(self):
        def fetch(path,payload=None):
            if path=='querybatch':return {'results':[{'vulns':[{'id':'GHSA-test'}]}]}
            return {'id':'GHSA-test','database_specific':{'severity':'HIGH'}}
        result=osv.audit(self.root,fetch)
        self.assertEqual(result['result'],'FAIL');self.assertIn('GHSA-test',result['advisories'])
    def test_missing_advisory_identity_never_passes(self):
        with self.assertRaises(osv.AuditError):osv.audit(self.root,lambda p,b=None: {'results':[{'vulns':[{'id':'GHSA-test'}]}]} if b else {})
    def test_network_error_is_not_a_clean_result(self):
        def fetch(*args):raise osv.AuditError('OSV transport unavailable')
        with self.assertRaises(osv.AuditError):osv.audit(self.root,fetch)
    def test_empty_findings_on_all_packages_pass_without_credentials(self):
        result=osv.audit(self.root,lambda p,b=None:{'results':[{} for q in b['queries']]})
        self.assertEqual(result['result'],'PASS');self.assertFalse(result['credentialsRequired'])

if __name__=='__main__':unittest.main()
