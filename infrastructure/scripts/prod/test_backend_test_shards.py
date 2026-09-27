import json,tempfile,unittest
from pathlib import Path
from backend_test_shards import COUNT,inventory,selection,verify

class BackendShardsTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        self.sources=self.root/'src';self.sources.mkdir();self.reports=self.root/'reports';self.reports.mkdir()
        for i in range(9):(self.sources/f'Example{i}Test.java').write_text(f'package example; class Example{i}Test {{ @Test void run() {{}} }}')
        (self.sources/'Helper.java').write_text('package example; class Helper {}')
        for i in range(COUNT):
            group=self.reports/str(i);group.mkdir();manifest=selection(self.sources,i,'a'*40)
            (group/'shard-selection.json').write_text(json.dumps(manifest))
            for row in manifest['tests']:
                name=row['class'];(group/('TEST-'+name+'.xml')).write_text(f'<testsuite name="{name}" tests="1" failures="0" errors="0" skipped="0"><testcase classname="{name}" name="run"/></testsuite>')
    def test_all_default_classes_run_once(self):
        rows=inventory(self.sources);groups=[selection(self.sources,i)['tests'] for i in range(COUNT)]
        self.assertEqual(9,len(rows));self.assertEqual(sorted(r['class'] for r in rows),sorted(r['class'] for g in groups for r in g))
        self.assertEqual(9,verify(self.sources,self.reports,'a'*40)['tests'])
    def test_missing_report_or_partition_is_rejected(self):
        report=next((self.reports/'0').glob('TEST-*.xml'));report.unlink()
        with self.assertRaisesRegex(ValueError,'did not report'):verify(self.sources,self.reports,'a'*40)
    def test_different_revision_and_changed_inventory_are_rejected(self):
        with self.assertRaises(ValueError):verify(self.sources,self.reports,'b'*40)
        (self.sources/'NewTest.java').write_text('package example; class NewTest { @Test void run() {} }')
        with self.assertRaises(ValueError):verify(self.sources,self.reports,'a'*40)
    def test_wrong_shard_execution_is_rejected(self):
        report=next((self.reports/'0').glob('TEST-*.xml'))
        (self.reports/'1'/report.name).write_bytes(report.read_bytes())
        with self.assertRaisesRegex(ValueError,'wrong shard'):verify(self.sources,self.reports,'a'*40)

if __name__=='__main__':unittest.main()
