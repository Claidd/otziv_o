"""Report real CI wall/queue time and the dependency critical path, not a sum of parallel jobs."""
import argparse
import datetime as dt
import json
from pathlib import Path
import re

from ci_artifacts import Client
from release_ci import pages


def stamp(value):
    return dt.datetime.fromisoformat(value.replace('Z','+00:00')).timestamp()


def workflow_jobs(text):
    rows={}
    for match in re.finditer(r'(?m)^  ([\w-]+):\n(.*?)(?=^  [\w-]+:|\Z)',text,re.S):
        body=match[2];name=re.search(r'^    name: (.+)$',body,re.M)
        if not name:continue
        pattern='^'+'.*'.join(re.escape(v) for v in re.split(r'\$\{\{.*?\}\}',name[1]))+'$'
        needs=re.search(r'^    needs: (.+)$',body,re.M)
        parents=[v.strip() for v in needs[1].strip('[]').split(',')] if needs else []
        rows[match[1]]={'namePattern':pattern,'needs':parents}
    return rows


def summarize(run,jobs,graph):
    start=stamp(run['created_at']);rows=[];groups={}
    for job in jobs:
        if not job.get('started_at') or not job.get('completed_at'):continue
        begin=stamp(job['started_at']);end=stamp(job['completed_at'])
        rows.append({'id':job['id'],'name':job['name'],'result':job['conclusion'],
                     'queueSeconds':max(0,begin-stamp(job.get('created_at') or job['started_at'])),
                     'executionSeconds':max(0,end-begin)})
        keys=[key for key,value in graph.items() if re.fullmatch(value['namePattern'],job['name'])]
        if len(keys)==1:
            group=groups.setdefault(keys[0],{'start':begin,'end':end})
            group['start']=min(group['start'],begin);group['end']=max(group['end'],end)
    end=max((stamp(j['completed_at']) for j in jobs if j.get('completed_at')),default=start)
    chain=[];key=max(groups,key=lambda k:groups[k]['end']) if groups else None;seen=set()
    while key:
        if key in seen:raise ValueError('Cyclic CI dependency graph')
        seen.add(key);group=groups[key]
        parents=[p for p in graph[key]['needs'] if p in groups]
        parent=max(parents,key=lambda p:groups[p]['end']) if parents else None
        ready=groups[parent]['end'] if parent else start
        chain.append({'job':key,'waitSeconds':max(0,group['start']-ready),
                      'groupWallSeconds':max(0,group['end']-group['start'])})
        key=parent
    return {'runId':run['id'],'attempt':run['run_attempt'],'revision':run['head_sha'],
            'event':run['event'],'result':run['conclusion'],'startedAt':run['created_at'],
            'wallSeconds':max(0,end-start),'criticalPath':list(reversed(chain)),'jobs':rows}


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--run-id',type=int,action='append',required=True)
    p.add_argument('--repo',type=Path,default=Path.cwd());p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();client=Client(a.repo);graph=workflow_jobs((a.repo/'.github/workflows/quality-gates.yml').read_text())
    runs=[]
    for run_id in a.run_id:
        run=client.get(f'/actions/runs/{run_id}')
        jobs=pages(client.get,f"/actions/runs/{run_id}/attempts/{run['run_attempt']}/jobs",'jobs')
        runs.append(summarize(run,jobs,graph))
    value={'schema':'otziv-ci-timing-v1','runs':runs}
    a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps([{'runId':r['runId'],'minutes':round(r['wallSeconds']/60,2),'criticalPath':[p['job'] for p in r['criticalPath']]} for r in runs]))


if __name__=='__main__':main()
