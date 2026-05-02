import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';

export interface CurrentUser {
  authenticated: boolean;
  name: string;
  principalType: string;
  authorities: string[];
  subject?: string;
  issuer?: string;
  preferredUsername?: string;
  email?: string;
  clientId?: string;
  authorizedParty?: string;
  realmRoles?: string[];
}

@Injectable({ providedIn: 'root' })
export class CurrentUserApi {
  constructor(private readonly http: HttpClient) {}

  getMe(): Observable<CurrentUser> {
    return this.http.get<CurrentUser>(`${appEnvironment.apiBaseUrl}/api/me`);
  }
}
