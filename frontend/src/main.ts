import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';
import { reportAppStartupError } from './app/core/app-startup';

bootstrapApplication(App, appConfig)
  .catch((err) => {
    reportAppStartupError();
    console.error(err);
  });
