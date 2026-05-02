import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';

export interface RegisterClientRequest {
  username: string;
  email: string;
  fio?: string;
  phoneNumber?: string;
  password: string;
  matchingPassword: string;
}

export interface LegacyUserMigrationRequest {
  username: string;
  password: string;
}

export interface ProvisionedUserResponse {
  id: number;
  keycloakId: string;
  username: string;
  email?: string;
  fio?: string;
  phoneNumber?: string;
  coefficient?: number;
  active: boolean;
  roles: string[];
}

@Injectable({ providedIn: 'root' })
export class AuthLifecycleApi {
  constructor(private readonly http: HttpClient) {}

  registerClient(request: RegisterClientRequest): Observable<ProvisionedUserResponse> {
    return this.http.post<ProvisionedUserResponse>(
      `${appEnvironment.apiBaseUrl}/api/auth/register`,
      request
    );
  }

  migrateLegacyUser(request: LegacyUserMigrationRequest): Observable<ProvisionedUserResponse> {
    return this.http.post<ProvisionedUserResponse>(
      `${appEnvironment.apiBaseUrl}/api/auth/legacy-migration`,
      request
    );
  }
}
