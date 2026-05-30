import { Capacitor } from '@capacitor/core';
import { mobileBuildTarget } from './mobile-build-target';

const isBrowser = typeof window !== 'undefined';
const isNative = Capacitor.isNativePlatform();
const localDevHost = isBrowser && (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1');
const localDevPort = isBrowser && window.location.port === '4300';
const isLocalWebDev = localDevHost && localDevPort;
const localProdLikeBaseUrl = 'http://localhost:8088';
const nativeBackendBaseUrl = trimTrailingSlash(mobileBuildTarget.nativeBackendBaseUrl);

export const mobileEnvironment = {
  appName: 'Компания О!',
  apiBaseUrl: isNative ? nativeBackendBaseUrl : '',
  backendBaseUrl: isNative ? nativeBackendBaseUrl : '',
  pushNotificationsEnabled: mobileBuildTarget.pushNotificationsEnabled,
  keycloak: {
    url: isNative ? `${nativeBackendBaseUrl}/keycloak` : isLocalWebDev ? `${localProdLikeBaseUrl}/keycloak` : '/keycloak',
    realm: 'otziv',
    clientId: 'otziv-mobile',
    nativeRedirectUri: 'otziv://auth/callback',
    webRedirectPath: '/auth/callback',
    logoutRedirectPath: '/login'
  }
} as const;

export function webRedirectUri(): string {
  return `${window.location.origin}${mobileEnvironment.keycloak.webRedirectPath}`;
}

function trimTrailingSlash(value: string): string {
  return value.replace(/\/+$/, '');
}
