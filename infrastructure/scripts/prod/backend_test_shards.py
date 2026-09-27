"""Partition the full Surefire default test set and verify complete shard reports."""
import argparse,hashlib,json,os,re,shutil
from pathlib import Path
import xml.etree.ElementTree as ET

COUNT=3


def inventory(root):
    root=Path(root);rows=[]
    for path in sorted(root.rglob('*.java')):
        if not re.fullmatch(r'(?:Test.*|.*Test|.*Tests|.*TestCase)\.java',path.name):continue
        relative=path.relative_to(root).as_posix();source=path.read_text(encoding='utf-8')
        package=re.search(r'(?m)^package\s+([\w.]+)\s*;',source)
        name=(package[1]+'.' if package else '')+path.stem
        executable=bool(re.search(r'@(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate|Nested)\b',source))
        abstract=bool(re.search(r'\babstract\s+class\s+'+re.escape(path.stem)+r'\b',source))
        rows.append({'path':relative,'class':name,'reportRequired':executable and not abstract})
    if not rows or len({r['class'] for r in rows})!=len(rows):raise ValueError('Invalid default test inventory')
    return rows


def selection(root,index,revision='',attempt=1):
    if not 0<=index<COUNT:raise ValueError('Invalid test shard')
    rows=inventory(root)
    # Sorted round-robin keeps whole classes (including nested tests) together.
    chosen=rows[index::COUNT]
    return {'schema':'otziv-backend-shard-v1','index':index,'count':COUNT,'revision':revision,'attempt':attempt,
            'inventoryDigest':hashlib.sha256(json.dumps(rows,sort_keys=True).encode()).hexdigest(),'tests':chosen}


def verify(root,inputs,revision):
    expected=[selection(root,i,revision) for i in range(COUNT)]
    found={}; totals={'tests':0,'failures':0,'errors':0,'skipped':0}
    manifests=[]; latest={}
    for path in Path(inputs).rglob('shard-selection.json'):
        manifest=json.loads(path.read_text());index=manifest['index']
        if not 0<=index<COUNT or type(manifest.get('attempt')) is not int or manifest['attempt']<1:raise ValueError('Invalid shard identity')
        compare={**manifest,'attempt':1}
        if compare!=expected[index]:raise ValueError('Shard inventory or source changed')
        manifests.append((path,manifest))
        latest[index]=max(latest.get(index,0),manifest['attempt'])
    # A failed-job rerun replaces that shard's failed/incomplete older reports.
    # Never fall back to an earlier green attempt when the latest one is bad.
    for path,manifest in manifests:
        index=manifest['index']
        if manifest['attempt']!=latest[index]:continue
        reports=list(path.parent.glob('TEST-*.xml'))
        if not reports:raise ValueError('Shard reports missing')
        selected={r['class'] for r in manifest['tests']};actual=set();counts={k:0 for k in totals};cases=set()
        for report in reports:
            suite=ET.parse(report).getroot();name=suite.attrib['name'];outer=name.split('$')[0]
            if outer not in selected and not (index==0 and name.endswith('.FinancialCtePlanEvidence')):raise ValueError('Test ran in wrong shard: '+name)
            actual.add(outer)
            for key in counts:counts[key]+=int(suite.get(key,'0'))
            for case in suite.findall('testcase'):
                ident=(case.get('classname'),case.get('name'))
                if ident in cases:raise ValueError('Duplicate test case in shard')
                cases.add(ident)
        missing={r['class'] for r in manifest['tests'] if r['reportRequired']}-actual
        if missing:raise ValueError('Selected tests did not report: '+','.join(sorted(missing)))
        if counts['failures'] or counts['errors'] or counts['tests']==0:raise ValueError('Shard tests failed or empty')
        row={'attempt':manifest['attempt'],'cases':cases,'counts':counts}
        previous=found.get(index)
        if previous and previous['attempt']==row['attempt'] and previous!=row:raise ValueError('Ambiguous shard reports')
        if not previous or row['attempt']>previous['attempt']:found[index]=row
    if set(found)!=set(range(COUNT)):raise ValueError('Full test suite is incomplete')
    seen=set()
    for row in found.values():
        if seen & row['cases']:raise ValueError('Tests ran in multiple shards')
        seen.update(row['cases'])
        for key in totals:totals[key]+=row['counts'][key]
    return {'result':'PASS','shards':COUNT,'classes':len(inventory(root)),**totals}


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['select','verify'])
    p.add_argument('--root',type=Path,default=Path('backend/src/test/java'));p.add_argument('--index',type=int)
    p.add_argument('--output',type=Path,required=True);p.add_argument('--inputs',type=Path)
    p.add_argument('--revision',default=os.environ.get('GITHUB_SHA',''))
    a=p.parse_args();a.output.parent.mkdir(parents=True,exist_ok=True)
    if a.action=='select':
        value=selection(a.root,a.index,a.revision,int(os.environ.get('GITHUB_RUN_ATTEMPT','1')))
        a.output.with_suffix('.txt').write_text('\n'.join(row['path'] for row in value['tests'])+'\n')
    else:value=verify(a.root,a.inputs,a.revision)
    a.output.write_text(json.dumps(value,indent=2)+'\n');print(json.dumps({k:v for k,v in value.items() if k!='tests'}))

if __name__=='__main__':main()
