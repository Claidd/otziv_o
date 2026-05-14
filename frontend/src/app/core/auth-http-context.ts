import { HttpContextToken } from '@angular/common/http';

export const SKIP_AUTH_REDIRECT_ON_401 = new HttpContextToken<boolean>(() => false);
