import tempfile,unittest
from pathlib import Path
from ci_monitoring_runner import validate,digest
from release_ci import GateError

class RunnerTest(unittest.TestCase):
    def test_same_attempt_and_bytes_required(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'image.tar';path.write_bytes(b'fixture image')
            value={'schema':'otziv-monitoring-runner-v1','revision':'a'*40,'runId':1,'attempt':1,'sha256':digest(path)}
            validate(value,path,'a'*40,1,1)
            for revision,run,attempt in [('b'*40,1,1),('a'*40,2,1),('a'*40,1,2)]:
                with self.assertRaises(GateError):validate(value,path,revision,run,attempt)
            path.write_bytes(b'substituted')
            with self.assertRaises(GateError):validate(value,path,'a'*40,1,1)

if __name__=='__main__':unittest.main()
