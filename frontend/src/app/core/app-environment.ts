const isLocalAngularDevServer =
  typeof window !== 'undefined' &&
  window.location.hostname === 'localhost' &&
  window.location.port === '4200';

export const appEnvironment = {
  apiBaseUrl: '',
  legacyBaseUrl: isLocalAngularDevServer ? 'http://localhost:8080' : '',
  metricsBaseUrl: isLocalAngularDevServer ? 'http://localhost:3000' : '/grafana',
  keycloak: {
    url: isLocalAngularDevServer ? 'http://localhost:8180' : '/keycloak',
    realm: 'otziv',
    clientId: 'otziv-frontend'
  }
} as const;
