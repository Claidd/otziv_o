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

export interface PerformerCityOption {
  id: number;
  cityTitle: string;
}

export interface RegisterPerformerRequest {
  phoneNumber: string;
  cityId: number;
  gender?: 'MALE' | 'FEMALE' | 'OTHER' | 'NOT_SPECIFIED';
  fio: string;
  telegramUsername?: string;
  registeredSource?: string;
  personalDataConsentAccepted: boolean;
  rulesConsentAccepted: boolean;
  honestReviewConsentAccepted: boolean;
}

export interface RegisterPerformerResponse {
  userId: number;
  performerId: number;
  username: string;
  temporaryPassword?: string | null;
  telegramLinkToken?: string | null;
  telegramLinkUrl: string;
  status: string;
  registrationExpiresAt: string;
  requiresAdminApproval: boolean;
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

  getPerformerCities(): Observable<PerformerCityOption[]> {
    return this.http.get<PerformerCityOption[]>(
      `${appEnvironment.apiBaseUrl}/api/auth/performer-cities`
    );
  }

  registerPerformer(request: RegisterPerformerRequest): Observable<RegisterPerformerResponse> {
    return this.http.post<RegisterPerformerResponse>(
      `${appEnvironment.apiBaseUrl}/api/auth/register-performer`,
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
