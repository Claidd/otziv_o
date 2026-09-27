import json,tempfile,unittest,shutil
from pathlib import Path
from backend_test_shards import COUNT,inventory,selection,verify,partition

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
    def test_duration_balancing_retains_whole_classes_and_all_new_tests(self):
        rows=inventory(self.sources)
        weights={r['class']:v for r,v in zip(rows,[90,80,70,1,1,1,1,1,1])}
        groups=partition(rows,{'seconds':weights,'overheadSeconds':[0,0,0]})
        loads=[sum(weights[r['class']] for r in g) for g in groups]
        self.assertLessEqual(max(loads)-min(loads),20)
        self.assertEqual(sorted(r['class'] for r in rows),sorted(r['class'] for g in groups for r in g))
        self.assertEqual(groups,partition(list(reversed(rows)),{'seconds':weights}))

    def test_bad_duration_data_is_rejected(self):
        for value in [-1,float('nan'),float('inf'),'fast']:
            with self.assertRaises(ValueError):partition(inventory(self.sources),{'seconds':{'new.Test':value}})
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

    def test_rerun_uses_latest_shard_without_repeating_successful_groups(self):
        latest=self.reports/'rerun-0';shutil.copytree(self.reports/'0',latest)
        manifest=json.loads((latest/'shard-selection.json').read_text());manifest['attempt']=2
        (latest/'shard-selection.json').write_text(json.dumps(manifest))
        old=next((self.reports/'0').glob('TEST-*.xml'))
        old.write_text(old.read_text().replace('failures="0"','failures="1"'))
        self.assertEqual(9,verify(self.sources,self.reports,'a'*40)['tests'])

    def test_incomplete_latest_shard_never_falls_back_to_old_success(self):
        latest=self.reports/'rerun-0';latest.mkdir()
        manifest=json.loads((self.reports/'0'/'shard-selection.json').read_text());manifest['attempt']=2
        (latest/'shard-selection.json').write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError,'reports missing'):verify(self.sources,self.reports,'a'*40)

if __name__=='__main__':unittest.main()
