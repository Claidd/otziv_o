"""Normal PKCE sign-in to the isolated local stack; never changes users, roles or clients."""
import argparse, base64, datetime, hashlib, html.parser, json, os, pathlib, re, secrets, subprocess, time, urllib.parse
import requests

class LoginForm(html.parser.HTMLParser):
    def __init__(self): super().__init__(); self.action=None
    def handle_starttag(self,tag,attrs):
        attrs=dict(attrs)
        if tag=='form' and attrs.get('id')=='kc-form-login': self.action=attrs.get('action')

def server_snapshot():
    result=subprocess.run(['docker','exec','otziv-prod-local-app-1','curl','-fsS',
        'http://localhost:8080/actuator/prometheus'],capture_output=True,text=True,timeout=15,check=True)
    # Persist only the fixed, non-sensitive interactive HTTP metric allowlist.
    return {line.rsplit(' ',1)[0]:float(line.rsplit(' ',1)[1]) for line in result.stdout.splitlines()
        if re.match(r'^otziv_http_(duration_seconds_(bucket|sum|count)|phase_duration_seconds_(sum|count)|sql_duration_seconds_(sum|count)|sql_executions_(sum|count))\{',line)}

def server_delta(before,after):
    if set(before) - set(after):
        raise RuntimeError('Server metric series disappeared or runtime changed; comparison is invalid')
    delta={key:value-before.get(key,0) for key,value in after.items()}
    if any(value < -1e-6 for value in delta.values()): raise RuntimeError('Server counters reset during run; comparison is invalid')
    endpoints={re.search(r'endpoint="([^"]+)"',key)[1] for key in delta}
    report={}
    for endpoint in sorted(endpoints):
        rows={key:value for key,value in delta.items() if 'endpoint="'+endpoint+'"' in key and 'status="2xx"' in key}
        def scalar(prefix): return sum(value for key,value in rows.items() if key.startswith(prefix+'{'))
        count=scalar('otziv_http_duration_seconds_count')
        if not count: continue
        buckets=sorted((float(re.search(r'le="([^"]+)"',key)[1]),value) for key,value in rows.items() if '_bucket{' in key)
        def quantile(q):
            lower=previous=0
            for upper,cumulative in buckets:
                if cumulative>=q*count:
                    return None if upper==float('inf') else 1000*(lower+(upper-lower)*(q*count-previous)/(cumulative-previous))
                lower,previous=upper,cumulative
            return None
        phases={}
        for key,value in rows.items():
            if key.startswith('otziv_http_phase_duration_seconds_count{') and value:
                phase=re.search(r'phase="([^"]+)"',key)[1]
                phases[phase]=1000*rows.get(key.replace('_count{','_sum{'),0)/value
        report[endpoint]=dict(count=count,meanMs=1000*scalar('otziv_http_duration_seconds_sum')/count,
            estimatedP95Ms=quantile(.95),estimatedP99Ms=quantile(.99),
            fractionAtMost100Ms=sum(value for upper,value in buckets if upper==.1)/count,
            sqlMeanMs=1000*scalar('otziv_http_sql_duration_seconds_sum')/count,
            sqlExecutionsMean=scalar('otziv_http_sql_executions_sum')/count,
            phaseMeanMs=phases)
    return dict(boundary='servlet filter before security through serialization; excludes proxy/network/connector queue',
        quantiles='estimates interpolated within histogram buckets; <=100 ms fraction is exact',endpoints=report)

def login(base, env_path, username=None):
    parsed=urllib.parse.urlparse(base)
    if parsed.hostname not in ('localhost','127.0.0.1') or parsed.scheme!='http':
        raise ValueError('This credential helper is restricted to the isolated localhost stack')
    env={}
    for line in pathlib.Path(env_path).read_text(encoding='utf-8-sig').splitlines():
        if line and not line.startswith('#') and '=' in line:
            key,value=line.split('=',1); env[key]=value.strip().strip('\"').strip("'")
    username=username or env['OTZIV_LOCAL_LOGIN_USERNAME']
    password=env['OTZIV_LOCAL_LOGIN_PASSWORD']
    verifier=secrets.token_urlsafe(48)
    challenge=base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip('=')
    session=requests.Session()
    root=base.rstrip('/')+'/keycloak/realms/otziv/protocol/openid-connect'
    redirect=base.rstrip('/')+'/'
    state=secrets.token_urlsafe(24)
    response=session.get(root+'/auth',params=dict(client_id='otziv-frontend',redirect_uri=redirect,
        response_type='code',scope='openid',state=state,code_challenge=challenge,code_challenge_method='S256'),timeout=20)
    form=LoginForm(); form.feed(response.text)
    if not form.action: raise RuntimeError('Local Keycloak login form unavailable')
    action=urllib.parse.urljoin(base,form.action)
    if urllib.parse.urlparse(action).netloc!=parsed.netloc: raise RuntimeError('Unexpected sign-in destination')
    # Browsers permit Secure cookies on localhost. requests does not; emulate this only
    # for the validated loopback origin, without changing Keycloak/cookie/TLS configuration.
    cookies='; '.join(cookie.name+'='+cookie.value for cookie in session.cookies)
    response=session.post(action,data=dict(username=username,password=password),headers={'Cookie':cookies},allow_redirects=False,timeout=20)
    callback=urllib.parse.urlparse(response.headers.get('Location',''))
    values=urllib.parse.parse_qs(callback.query or callback.fragment)
    if values.get('state')!=[state] or not values.get('code'):
        import re
        error=re.search(r'id="input-error"[^>]*>(.*?)</span>',response.text,re.S)
        message=re.sub('<[^>]+>','',error.group(1)).strip() if error else 'additional authentication step'
        if 'Cookie not found' in response.text: message='Cookie not found'
        if 'HTTPS required' in response.text: message='HTTPS required'
        if 'Invalid parameter' in response.text: message='Invalid parameter'
        raise RuntimeError('Local login did not complete: HTTP '+str(response.status_code)+'; '+message[:120])
    result=session.post(root+'/token',data=dict(grant_type='authorization_code',client_id='otziv-frontend',
        redirect_uri=redirect,code=values['code'][0],code_verifier=verifier),timeout=20)
    if result.status_code!=200: raise RuntimeError('Local PKCE token exchange failed')
    return result.json()

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--env-file',required=True); p.add_argument('--base-url',default='http://localhost:8088')
    p.add_argument('--username'); p.add_argument('--k6-image'); p.add_argument('--sections',default='companies,orders,specialist_new')
    p.add_argument('--rate',type=int,default=2); p.add_argument('--seconds',type=int,default=60)
    p.add_argument('--source-label',default='unspecified')
    p.add_argument('--role-label',default='local-default')
    p.add_argument('--output',type=pathlib.Path,required=True)
    args=p.parse_args(); token=login(args.base_url,args.env_file,args.username)
    if args.seconds+20>=token['expires_in']: raise ValueError('Run must finish before token expiry; use repeated independent runs')
    if not args.k6_image:
        # Small baseline probe; only status/shape/timing, never response bodies, are saved.
        report={}
        session=requests.Session(); session.headers['Authorization']='Bearer '+token['access_token']
        for name,path in [('companies','manager/board?section=companies'),('orders','manager/board?section=orders'),('specialist','worker/board?section=new')]:
            samples=[]; statuses={}
            for i in range(35):
                started=time.perf_counter()
                r=session.get(args.base_url+'/api/'+path,timeout=15)
                elapsed=(time.perf_counter()-started)*1000
                statuses[str(r.status_code)]=statuses.get(str(r.status_code),0)+1
                field='companies' if name=='companies' else 'orders'
                correct=r.status_code==200 and isinstance(r.json().get(field,{}).get('content'),list)
                if correct and i>=5: samples.append(elapsed)
            samples.sort()
            report[name]=dict(statuses=statuses,samples=len(samples),p95Ms=samples[int(.95*(len(samples)-1))] if samples else None)
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(json.dumps(report,indent=2),encoding='utf-8'); print(json.dumps(report)); return
    if '@sha256:' not in args.k6_image: raise ValueError('Use a resolved immutable k6 image')
    args.output.mkdir(parents=True,exist_ok=True)
    manifest=dict(startedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        source=args.source_label,role=args.role_label,sections=args.sections.split(','),
        ratePerSection=args.rate,durationSeconds=args.seconds,k6Image=args.k6_image,
        boundary='k6 HTTP request and complete response through local nginx; no DNS/connect/TLS setup',
        fixture='existing isolated production-like database; unchanged between comparison runs')
    for service in ('app','nginx','mysql'):
        inspected=subprocess.run(['docker','inspect','--format',
            '{"imageId":{{json .Image}},"memoryBytes":{{.HostConfig.Memory}},"memorySwapBytes":{{.HostConfig.MemorySwap}},"nanoCpus":{{.HostConfig.NanoCpus}}}',
            'otziv-prod-local-'+service+'-1'],
            capture_output=True,text=True,timeout=10)
        manifest[service]=json.loads(inspected.stdout) if inspected.returncode==0 else 'unavailable'
    manifest_path=args.output/'manifest.json'
    manifest_path.write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    env=dict(os.environ,AUTH_TOKEN=token['access_token'])
    script=pathlib.Path(__file__).with_name('interactive-http.js').resolve()
    local=urllib.parse.urlparse(args.base_url)
    command=['docker','run','--rm','--env','AUTH_TOKEN','--env','BASE_URL=http://host.docker.internal:'+str(local.port or 80),
        '--env','HTTP_HOST='+local.netloc,'--env','SECTIONS='+args.sections,'--env','RATE_PER_SECTION='+str(args.rate),'--env','DURATION='+str(args.seconds)+'s',
        '--mount','type=bind,source='+str(script)+',target=/scenario.js,readonly',
        '--mount','type=bind,source='+str(args.output.resolve())+',target=/results',args.k6_image,
        'run','--summary-export=/results/summary.json','/scenario.js']
    before=server_snapshot()
    outcome=subprocess.run(command,env=env).returncode
    after=server_snapshot()
    (args.output/'server-counters.json').write_text(json.dumps(dict(before=before,after=after),indent=2),encoding='utf-8')
    (args.output/'server-summary.json').write_text(json.dumps(server_delta(before,after),indent=2),encoding='utf-8')
    manifest.update(finishedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),exitCode=outcome)
    manifest_path.write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    raise SystemExit(outcome)
if __name__=='__main__': main()
