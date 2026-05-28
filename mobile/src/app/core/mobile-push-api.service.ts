import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { mobileEnvironment } from './mobile-environment';

export interface MobilePushTokenRequest {
  token: string;
  platform?: string;
  deviceId?: string;
  appVersion?: string;
}

@Injectable({ providedIn: 'root' })
export class MobilePushApiService {
  constructor(private readonly http: HttpClient) {}

  registerToken(request: MobilePushTokenRequest): Observable<void> {
    return this.http.post<void>(this.apiUrl('/api/mobile/push-token'), request);
  }

  private apiUrl(path: string): string {
    return `${mobileEnvironment.apiBaseUrl}${path}`;
  }
}
