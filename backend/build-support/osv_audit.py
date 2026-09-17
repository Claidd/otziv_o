"""Credential-free OSV audit of every package in all six effective Maven reports.

NVD remains independently blocking. This additional gate fails closed on missing
reports, incomplete API replies, unclassified findings, and HIGH/CRITICAL risk.
Only public package coordinates are sent; source files and credentials are not.
"""
import argparse, concurrent.futures, datetime, hashlib, json, math, re, time
import urllib.error, urllib.parse, urllib.request
from pathlib import Path

REPORTS = ['backend/target/dependency-check-report.json',
           *['backend/build-support/'+p+'target/dependency-check-report.json'
             for p in ['', 'dependency-audit/', 'site-plugin/', 'test-transport/']],
           'infrastructure/keycloak/security-generation/target/dependency-check-report.json']
class AuditError(ValueError): pass
def require(value, message):
    if not value: raise AuditError(message)
def inventory(root):
    packages=set(); reports=[]
    for relative in REPORTS:
        raw=(root/relative).read_bytes(); report=json.loads(raw)
        deps=report.get('dependencies')
        require(isinstance(deps,list) and deps,'Missing dependency coverage: '+relative)
        count=0; other=[]
        for dep in deps:
            entries=dep.get('packages',[])
            require(isinstance(entries,list),'Malformed dependency packages')
            for item in entries:
                purl=item.get('id','')
                require(re.fullmatch(r'pkg:maven/[^\s/@?#]+/[^\s/@?#]+@[^\s@?#]+(?:\?[^\s#]+)?',purl),'Unrecognized Maven package identity')
                packages.add(purl); count+=1
            if not entries:
                filename=dep.get('fileName','')
                # Embedded native/JS payloads retain independent NVD/RetireJS
                # coverage; a top-level Maven artifact may never disappear.
                require(not (filename.endswith('.jar') and ': ' not in filename),'Maven artifact missing package identity: '+filename)
                other.append(filename)
        require(count>0,'No package identities in '+relative)
        reports.append({'path':relative,'sha256':hashlib.sha256(raw).hexdigest(),'dependencies':len(deps),
                        'packageIdentities':count,'nonPackageFilesCoveredByNvd':other})
    return sorted(packages),reports

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs): return None
def request(path,payload=None):
    require(path=='querybatch' or re.fullmatch(r'vulns/[A-Za-z0-9._-]+',path),'Unexpected OSV endpoint')
    data=None if payload is None else json.dumps(payload).encode()
    req=urllib.request.Request('https://api.osv.dev/v1/'+path,data=data,
        headers={'Content-Type':'application/json','User-Agent':'otziv-free-dependency-audit'})
    for attempt in range(3):
        try:
            with urllib.request.build_opener(NoRedirect()).open(req,timeout=45) as response:
                raw=response.read(32*1024*1024+1)
                require(len(raw)<=32*1024*1024,'OSV response too large')
                result=json.loads(raw);require(isinstance(result,dict),'Malformed OSV response');return result
        except urllib.error.HTTPError as error:
            if attempt==2 or (error.code!=429 and error.code<500):raise AuditError('OSV HTTP '+str(error.code)) from None
        except (urllib.error.URLError,TimeoutError):
            if attempt==2:raise AuditError('OSV transport unavailable') from None
        time.sleep(2**attempt)

def query(packages,fetch=request):
    found={p:set() for p in packages}; evidence=[]
    for offset in range(0,len(packages),100):
        pending=[(p,{'package':{'purl':p}}) for p in packages[offset:offset+100]]; seen=set()
        while pending:
            identity=json.dumps(pending,sort_keys=True)
            require(identity not in seen and len(seen)<100,'OSV pagination did not complete');seen.add(identity)
            payload={'queries':[x[1] for x in pending]};response=fetch('querybatch',payload)
            results=response.get('results');require(isinstance(results,list) and len(results)==len(pending),'Incomplete OSV batch response')
            evidence.append({'request':payload,'response':response});next_queries=[]
            for (purl,item),result in zip(pending,results):
                require(isinstance(result,dict) and 'error' not in result,'OSV package query failed')
                vulns=result.get('vulns',[]);require(isinstance(vulns,list),'Malformed OSV findings')
                for vuln in vulns:
                    vid=vuln.get('id','');require(re.fullmatch('[A-Za-z0-9._-]+',vid),'Malformed OSV vulnerability identity')
                    found[purl].add(vid)
                token=result.get('next_page_token')
                if token:
                    require(isinstance(token,str),'Malformed OSV page token')
                    next_queries.append((purl,{'package':{'purl':purl},'page_token':token}))
            pending=next_queries
    return found,evidence

def cvss3(vector):
    require(vector.startswith(('CVSS:3.0/','CVSS:3.1/')),'Unsupported CVSS vector')
    parts=vector.split('/')[1:];m=dict(x.split(':') for x in parts)
    require(len(m)==len(parts),'Duplicate CVSS metric')
    changed=m['S']=='C';require(m['S'] in ['U','C'],'Invalid CVSS scope')
    av={'N':.85,'A':.62,'L':.55,'P':.2}[m['AV']];ac={'L':.77,'H':.44}[m['AC']]
    pr={'N':.85,'L':.68 if changed else .62,'H':.5 if changed else .27}[m['PR']];ui={'N':.85,'R':.62}[m['UI']]
    impacts=[{'N':0,'L':.22,'H':.56}[m[x]] for x in ['C','I','A']]
    iss=1-math.prod(1-x for x in impacts)
    impact=7.52*(iss-.029)-3.25*(iss-.02)**15 if changed else 6.42*iss
    if impact<=0:return 0
    score=min(10,(1.08 if changed else 1)*(impact+8.22*av*ac*pr*ui))
    return math.ceil(round(score,5)*10)/10

def blocking(record):
    if record.get('withdrawn'):return False
    label=record.get('database_specific',{}).get('severity','').upper()
    known={'LOW':0,'MODERATE':0,'MEDIUM':0,'HIGH':7,'CRITICAL':9}
    scores=[known[label]] if label in known else []
    for item in record.get('severity',[]):
        score=item.get('score','')
        if item.get('type')=='CVSS_V3': scores.append(cvss3(score))
    # Unknown severity needs investigation; it never silently becomes low risk.
    return not scores or max(scores)>=7

def audit(root,fetch=request):
    packages,reports=inventory(root);found,batches=query(packages,fetch)
    ids=sorted({vid for values in found.values() for vid in values})
    def load(vid):
        record=fetch('vulns/'+vid);require(record.get('id')==vid,'OSV advisory identity mismatch');return vid,record
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool: records=dict(pool.map(load,ids))
    findings=[{'package':p,'id':vid,'blocking':blocking(records[vid])} for p in packages for vid in sorted(found[p])]
    return {'schema':'otziv-osv-complete-maven-audit-v1','createdAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'provider':'https://api.osv.dev','credentialsRequired':False,'reports':reports,'packages':packages,
        'batches':batches,'advisories':records,'findings':findings,
        'result':'FAIL' if any(f['blocking'] for f in findings) else 'PASS'}

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[2]);args=parser.parse_args()
    output=args.root/'backend/target/osv-audit.json';output.parent.mkdir(parents=True,exist_ok=True)
    if output.exists():output.unlink()
    try:
        result=audit(args.root);output.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
        print('OSV: '+result['result']+', '+str(len(result['packages']))+' packages across all six reports')
        for finding in result['findings']:
            if finding['blocking']:print('BLOCK '+finding['package']+' '+finding['id'])
        return 0 if result['result']=='PASS' else 1
    except (AuditError,OSError,ValueError,KeyError,TypeError) as error:
        output.write_text(json.dumps({'result':'FAIL','complete':False,'error':str(error)})+'\n',encoding='utf-8')
        print('OSV audit incomplete: '+str(error));return 2
if __name__=='__main__':raise SystemExit(main())
