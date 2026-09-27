"""Share one freshly built proof runner within a single exact CI run/attempt."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
from release_ci import require

TAG='otziv-monitoring-proof-ci'

def digest(path):
    with Path(path).open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()

def validate(value,archive,revision,run,attempt):
    require(value.get('schema')=='otziv-monitoring-runner-v1' and value.get('revision')==revision
            and value.get('runId')==run and value.get('attempt')==attempt,'Proof runner is from another run/attempt/source')
    require(archive.is_file() and digest(archive)==value.get('sha256'),'Shared proof runner archive changed')
    return value

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['save','load'])
    p.add_argument('--directory',type=Path,required=True);a=p.parse_args();a.directory.mkdir(parents=True,exist_ok=True)
    archive=a.directory/'proof-runner.tar';receipt=a.directory/'proof-runner.json'
    revision=os.environ['GITHUB_SHA'];run=int(os.environ['GITHUB_RUN_ID']);attempt=int(os.environ['GITHUB_RUN_ATTEMPT'])
    if a.action=='save':
        subprocess.run(['docker','save','--output',str(archive),TAG],check=True)
        value={'schema':'otziv-monitoring-runner-v1','revision':revision,'runId':run,'attempt':attempt,
               'sha256':digest(archive),'imageId':json.loads(subprocess.check_output(['docker','image','inspect',TAG]))[0]['Id']}
        receipt.write_text(json.dumps(value)+'\n')
    else:
        value=validate(json.loads(receipt.read_text()),archive,revision,run,attempt)
        subprocess.run(['docker','load','--input',str(archive)],check=True)
        require(json.loads(subprocess.check_output(['docker','image','inspect',TAG]))[0]['Id']==value['imageId'],
                'Loaded proof runner differs from its receipt')

if __name__=='__main__':main()
