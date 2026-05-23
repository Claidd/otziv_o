const LOCAL_DEV_HOSTS = new Set(['localhost', '127.0.0.1', '::1']);
const LOCAL_ANGULAR_DEV_PORTS = new Set(['4200']);

const isLocalAngularDevServer =
  typeof window !== 'undefined' &&
  LOCAL_DEV_HOSTS.has(window.location.hostname) &&
  LOCAL_ANGULAR_DEV_PORTS.has(window.location.port);

const metricsDashboardPath =
  '/d/otziv-backend-performance/otziv-backend3a-proizvoditel-nost--i-logi?orgId=1&refresh=5s&from=now-15m&to=now';

export const appEnvironment = {
  apiBaseUrl: '',
  backendBaseUrl: '',
  metricsBaseUrl: isLocalAngularDevServer ? `http://localhost:3000${metricsDashboardPath}` : `/grafana${metricsDashboardPath}`,
  keycloak: {
    url: isLocalAngularDevServer ? 'http://localhost:8180' : '/keycloak',
    realm: 'otziv',
    clientId: 'otziv-frontend'
  }
} as const;
