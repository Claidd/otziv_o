const isLocalAngularDevServer =
  typeof window !== 'undefined' &&
  window.location.hostname === 'localhost' &&
  window.location.port === '4200';

const metricsDashboardPath =
  '/d/otziv-backend-performance/otziv-backend3a-proizvoditel-nost--i-logi?orgId=1&refresh=5s&from=now-15m&to=now';

export const appEnvironment = {
  apiBaseUrl: '',
  legacyBaseUrl: isLocalAngularDevServer ? 'http://localhost:8080' : '/legacy',
  metricsBaseUrl: isLocalAngularDevServer ? `http://localhost:3000${metricsDashboardPath}` : `/grafana${metricsDashboardPath}`,
  keycloak: {
    url: isLocalAngularDevServer ? 'http://localhost:8180' : '/keycloak',
    realm: 'otziv',
    clientId: 'otziv-frontend'
  }
} as const;
