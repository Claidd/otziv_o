import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.hunt.otziv',
  appName: 'Компания О!',
  webDir: 'dist/mobile/browser',
  plugins: {
    CapacitorHttp: {
      enabled: true
    },
    SplashScreen: {
      launchAutoHide: false,
      backgroundColor: '#f7f9fc'
    },
    StatusBar: {
      overlaysWebView: false,
      backgroundColor: '#f7f9fc',
      style: 'LIGHT'
    },
    Keyboard: {
      resize: 'ionic',
      style: 'DEFAULT',
      resizeOnFullScreen: true
    },
    PushNotifications: {
      presentationOptions: ['badge', 'sound', 'alert', 'banner', 'list']
    }
  }
};

export default config;
