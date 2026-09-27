import unittest
from release_metrics import summarize,workflow_jobs

class MetricsTest(unittest.TestCase):
    def test_parallel_jobs_are_not_added_together(self):
        graph=workflow_jobs('  a:\n    name: Tests (${{ matrix.id }})\n  b:\n    name: Finish\n    needs: a\n')
        run={'id':1,'run_attempt':1,'head_sha':'a'*40,'event':'push','conclusion':'success','created_at':'2026-09-27T00:00:00Z'}
        def job(i,name,start,end):return {'id':i,'name':name,'started_at':f'2026-09-27T00:{start}:00Z','completed_at':f'2026-09-27T00:{end}:00Z','conclusion':'success'}
        value=summarize(run,[job(1,'Tests (0)','00','05'),job(2,'Tests (1)','00','07'),job(3,'Finish','07','08')],graph)
        self.assertEqual(480,value['wallSeconds'])
        self.assertEqual(['a','b'],[p['job'] for p in value['criticalPath']])
        self.assertEqual(420,value['criticalPath'][0]['groupWallSeconds'])

if __name__=='__main__':unittest.main()
