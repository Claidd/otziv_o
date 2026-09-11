"""Read-only VPS evidence. SSH host checking stays enabled; no tokens/rows/envs exported."""
import argparse
import json
import subprocess
from pathlib import Path

REMOTE = r'''
import datetime,json,subprocess,urllib.parse,pathlib
queries = {
 "endpoint_count":"sum(increase(otziv_endpoint_duration_seconds_count[6h])) by(endpoint,result)",
 "endpoint_p95":"histogram_quantile(0.95,sum(increase(otziv_endpoint_duration_seconds_bucket[6h])) by(le,endpoint))",
 "http_count":"sum(increase(otziv_http_duration_seconds_count[6h])) by(endpoint,status)",
 "http_p95":"histogram_quantile(0.95,sum(increase(otziv_http_duration_seconds_bucket[6h])) by(le,endpoint))",
 "segments":"sum(increase(otziv_service_segment_duration_seconds_sum[6h])) by(component,segment) / sum(increase(otziv_service_segment_duration_seconds_count[6h])) by(component,segment)",
 "pool_pending":"max_over_time(hikaricp_connections_pending[6h])",
 "pool_acquire":"rate(hikaricp_connections_acquire_seconds_sum[6h])/rate(hikaricp_connections_acquire_seconds_count[6h])",
 "gc_max":"max_over_time(jvm_gc_pause_seconds_max{job=\"app\"}[6h])",
 "allocation_rate":"rate(jvm_gc_memory_allocated_bytes_total{job=\"app\"}[6h])",
 "heap_used":"jvm_memory_used_bytes{job=\"app\",area=\"heap\"}",
 "cache":"sum(increase(cache_gets_total[6h])) by(cache,result)",
 "http_success_p95":"histogram_quantile(0.95,sum(increase(otziv_http_duration_seconds_bucket{status=\"2xx\"}[6h])) by(le,endpoint))",
 "http_success_p99":"histogram_quantile(0.99,sum(increase(otziv_http_duration_seconds_bucket{status=\"2xx\"}[6h])) by(le,endpoint))",
 "http_success_at_most_100ms":"sum(increase(otziv_http_duration_seconds_bucket{status=\"2xx\",le=\"0.1\"}[6h])) by(endpoint) / sum(increase(otziv_http_duration_seconds_count{status=\"2xx\"}[6h])) by(endpoint)",
 "http_sql_count":"sum(increase(otziv_http_sql_executions_sum{status=\"2xx\"}[6h])) by(endpoint) / sum(increase(otziv_http_sql_executions_count{status=\"2xx\"}[6h])) by(endpoint)",
 "http_sql_mean":"sum(increase(otziv_http_sql_duration_seconds_sum{status=\"2xx\"}[6h])) by(endpoint) / sum(increase(otziv_http_sql_duration_seconds_count{status=\"2xx\"}[6h])) by(endpoint)",
 "container_throttle":"rate(otziv_container_cpu_throttled_periods_total[6h])/rate(otziv_container_cpu_periods_total[6h])",
 "container_pressure":"rate(otziv_container_pressure_seconds_total[6h])",
 "projection_age":"time()-otziv_projection_last_success_epoch",
 "projection_failures":"increase(otziv_projection_refresh_total{result=\"error\"}[6h])",
 "http_errors":"sum(increase(http_server_requests_seconds_count{status=~\"5..\"}[6h])) by(uri,status)"
}
def run(args):
 return subprocess.check_output(args,text=True,timeout=30).strip()
report={"measuredAt":datetime.datetime.now(datetime.timezone.utc).isoformat(),"metrics":{}}
for name, query in queries.items():
 url='http://localhost:9090/api/v1/query?'+urllib.parse.urlencode({'query':query})
 response=json.loads(run(['docker','exec','prometheus','wget','-qO-',url]))
 if response.get('status')!='success': raise RuntimeError('Prometheus query failed: '+name)
 report['metrics'][name]=response['data']['result']
report['host']={"cpus":run(['nproc']),"memory":run(['free','-m']),"disk":run(['df','-h','/']),"vmstat":run(['vmstat','1','5'])}
report['containers']=run(['docker','stats','--no-stream','--format','{{.Name}}:{{.CPUPerc}}:{{.MemUsage}}'])
report['app']=run(['docker','inspect','--format','{{.Config.Image}} {{.State.StartedAt}}','otziv-prod-app-1'])
report['jarSha256']=run(['docker','exec','otziv-prod-app-1','sha256sum','/app/app.jar']).split()[0]
report['pressure']={name:pathlib.Path('/proc/pressure/'+name).read_text().strip() for name in ['cpu','memory','io'] if pathlib.Path('/proc/pressure/'+name).exists()}
report['diskstats']=pathlib.Path('/proc/diskstats').read_text()
capacity=pathlib.Path('/docker/.deploy-capacity.json')
if capacity.exists(): report['deploymentCapacity']=json.loads(capacity.read_text())
digest_query="SELECT DIGEST,COUNT_STAR,SUM_TIMER_WAIT,SUM_ROWS_EXAMINED,SUM_ROWS_SENT FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME='otziv' ORDER BY SUM_TIMER_WAIT DESC LIMIT 40"
raw=subprocess.check_output(['docker','exec','-i','my-mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names'],input=digest_query,text=True,timeout=30)
report['digests']=[dict(zip(['digest','calls','timerPs','rowsExamined','rowsSent'],line.split('\t'))) for line in raw.splitlines()]
print(json.dumps(report,ensure_ascii=False))
'''

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', required=True)
    parser.add_argument('--user', default='hunt')
    parser.add_argument('--port', default='22022')
    parser.add_argument('--key', required=True)
    parser.add_argument('--known-hosts', required=True)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--window', default='6h', help='Prometheus lookback, e.g. 24h or 10m; use an unmixed post-deploy window')
    args = parser.parse_args()
    import re
    if not re.fullmatch(r'[1-9][0-9]*[mhd]',args.window): raise ValueError('Invalid lookback')
    result = subprocess.run(['ssh', '-i', args.key, '-p', args.port, '-o', 'BatchMode=yes',
                             '-o', 'StrictHostKeyChecking=yes', '-o', 'ConnectTimeout=10',
                             '-o', 'UserKnownHostsFile=' + args.known_hosts,
                             args.user + '@' + args.host, 'python3 -'],
                            input=REMOTE.replace('[6h]','['+args.window+']'), text=True, encoding='utf-8', capture_output=True, timeout=150)
    if result.returncode:
        raise RuntimeError('Read-only capture failed; no successful evidence written: ' + result.stderr[:300])
    report = json.loads(result.stdout)
    report['lookback'] = args.window
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print('Saved metrics (empty series remain unavailable):', args.output)

if __name__ == '__main__':
    main()
