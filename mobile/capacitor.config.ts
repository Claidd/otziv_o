import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.hunt.otziv',
  appName: 'Otziv',
  webDir: 'dist/mobile/browser',
  plugins: {
    SplashScreen: {
      launchAutoHide: false,
      backgroundColor: '#f7f9fc'
    },
    StatusBar: {
      backgroundColor: '#f7f9fc',
      style: 'LIGHT'
    }
  }
};

export default config;
