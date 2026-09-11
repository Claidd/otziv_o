import http from 'k6/http';
import { check } from 'k6';
import { Trend, Rate } from 'k6/metrics';

// One arrival schedule per section. Secrets come from the process environment, never tags/logs.
const routes = {
  companies: ['/api/manager/board?section=companies&pageSize=10', 'companies'],
  orders: ['/api/manager/board?section=orders&pageSize=10', 'orders'],
  specialist_new: ['/api/worker/board?section=new&pageSize=10', 'orders'],
  specialist_correct: ['/api/worker/board?section=correct&pageSize=10', 'orders'],
  specialist_nagul: ['/api/worker/board?section=nagul&pageSize=10', 'reviews'],
  specialist_publish: ['/api/worker/board?section=publish&pageSize=10', 'reviews'],
  specialist_bad: ['/api/worker/board?section=bad&pageSize=10', 'reviews'],
  specialist_recovery: ['/api/worker/board?section=recovery&pageSize=10', 'reviews'],
  profile: ['/api/cabinet/profile', 'profile'],
  profile_refresh: ['/api/cabinet/profile?refresh=true', 'profile'],
  team: ['/api/cabinet/team', 'team'],
  team_refresh: ['/api/cabinet/team?refresh=true', 'team'],
  score: ['/api/cabinet/score', 'score'],
  score_refresh: ['/api/cabinet/score?refresh=true', 'score'],
  analytics: ['/api/cabinet/analyse', 'analytics'],
  analytics_refresh: ['/api/cabinet/analyse?refresh=true', 'analytics'],
  today: ['/api/admin/manager-control/today', 'today'],
};
const selected = (__ENV.SECTIONS || 'companies,orders,specialist_new').split(',');
const base = (__ENV.BASE_URL || '').replace(/\/$/, '');
if (!base || !__ENV.AUTH_TOKEN || selected.some(s => !routes[s])) throw new Error('BASE_URL, AUTH_TOKEN and valid SECTIONS are required');
const rate = Number(__ENV.RATE_PER_SECTION || 1);
const duration = __ENV.DURATION || '60s';
const successLatency = new Trend('successful_response_ms', true);
const valid = new Rate('valid_response');
const thresholds = { dropped_iterations: ['count==0'], valid_response: ['rate==1'] };
for (const name of selected) thresholds[`successful_response_ms{section:${name}}`] =
  [`p(95)<${Number(__ENV.CLIENT_P95_MS || 100)}`, `p(99)<${Number(__ENV.CLIENT_P99_MS || 200)}`];
export const options = {
  scenarios: Object.fromEntries(selected.map(section => [section, {
    executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration,
    preAllocatedVUs: 4, maxVUs: 12, gracefulStop: '10s', env: { SECTION: section },
  }])),
  thresholds,
  summaryTrendStats: ['avg','med','p(90)','p(95)','p(99)','max','count'],
  systemTags: ['status','method','scenario','expected_response'],
};
export default function () {
  const section=__ENV.SECTION;
  const [path, field]=routes[section];
  const headers={Authorization:`Bearer ${__ENV.AUTH_TOKEN}`};
  if (__ENV.HTTP_HOST) headers.Host=__ENV.HTTP_HOST;
  const response=http.get(base+path, {
    headers, tags:{section},
    timeout:'10s', redirects:0,
  });
  let correct=false;
  if (response.status===200) {
    try {
      const body=response.json();
      if (field==='profile') correct=!!body?.date && !!body.user;
      else if (field==='team') correct=!!body?.date && Array.isArray(body.workers)
        && Array.isArray(body.managers) && typeof body.canEditUsers==='boolean';
      else if (field==='today') correct=!!body?.date && !!body.generatedAt
        && Array.isArray(body.managers) && body.managersTotal===body.managers.length;
      else if (field==='score') correct=!!body?.date && !!body.user && !!body.groups;
      else if (field==='analytics') correct=!!body?.date && !!body.user && !!body.stats;
      else correct=!!body && !!body[field] && Array.isArray(body[field].content)
        && body[field].content.length<=10 && body[field].totalElements>=body[field].content.length
        && body.section===section.replace(/^specialist_/, '') && body.warning!==true;
    } catch (_) { correct=false; }
  }
  valid.add(correct,{section});
  check(response,{'authorized JSON page':()=>correct},{section});
  if (correct) successLatency.add(response.timings.duration,{section});
}
