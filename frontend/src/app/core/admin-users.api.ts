import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';

export interface CreateKeycloakUserRequest {
  username: string;
  email: string;
  fio?: string;
  phoneNumber?: string;
  password: string;
  temporaryPassword: boolean;
  enabled: boolean;
  emailVerified: boolean;
  coefficient?: number;
  roles: string[];
}

export interface CreatedKeycloakUserResponse {
  id: number;
  keycloakId: string;
  username: string;
  email: string;
  fio?: string;
  phoneNumber?: string;
  coefficient?: number;
  active: boolean;
  roles: string[];
}

export interface AdminUser {
  id: number;
  keycloakId?: string;
  keycloakLinked: boolean;
  authProvider: string;
  username: string;
  email?: string;
  fio?: string;
  phoneNumber?: string;
  coefficient?: number;
  workerChatUrl?: string;
  personalTelegramLinked: boolean;
  workerTelegramGroupChatId?: number | null;
  workerTelegramBotInviteUrl?: string;
  managerAuditChatUrl?: string;
  managerAuditTelegramGroupChatId?: number | null;
  managerAuditTelegramBotInviteUrl?: string;
  imageId?: number | null;
  active: boolean;
  createTime?: string;
  lastLoginAt?: string;
  roles: string[];
}

export interface UpdateKeycloakUserRequest {
  username?: string;
  email?: string;
  fio?: string;
  phoneNumber?: string;
  coefficient?: number;
  workerChatUrl?: string;
  managerAuditChatUrl?: string;
  enabled: boolean;
  roles: string[];
}

export interface ChangeKeycloakPasswordRequest {
  password: string;
  temporary: boolean;
}

export interface AssignmentOption {
  id: number;
  userId: number;
  username: string;
  fio?: string;
  email?: string;
  role: string;
}

export interface AssignmentOptions {
  managers: AssignmentOption[];
  workers: AssignmentOption[];
  operators: AssignmentOption[];
  marketologs: AssignmentOption[];
}

export interface UserAssignments {
  userId: number;
  ownerControlViewMode?: 'OWN_MANAGERS' | 'ALL_MANAGERS';
  managerIds: number[];
  workerIds: number[];
  operatorIds: number[];
  marketologIds: number[];
}

export interface UpdateUserAssignmentsRequest {
  ownerControlViewMode?: 'OWN_MANAGERS' | 'ALL_MANAGERS';
  managerIds: number[];
  workerIds: number[];
  operatorIds: number[];
  marketologIds: number[];
}

export type ContractorRole = 'SPECIALIST' | 'MANAGER';

export interface ContractorPaymentProfile {
  id: number;
  userId: number;
  role: ContractorRole;
  rowVersion: number;
  enabled: boolean;
  liveEnabled: boolean;
  recipientName?: string;
  paymentPhone?: string;
  bankName?: string;
  paymentComment?: string;
  openingBalanceKopecks: number;
  trackingStartedAt: string;
  accruedMonthKopecks: number;
  accruedTotalKopecks: number;
  reservedKopecks: number;
  clientReportedKopecks: number;
  partiallyConfirmedOutstandingKopecks: number;
  grossConfirmedMonthKopecks: number;
  grossConfirmedTotalKopecks: number;
  returnedMonthKopecks: number;
  returnedTotalKopecks: number;
  closedWithoutPaymentMonthKopecks: number;
  closedWithoutPaymentTotalKopecks: number;
  netReceivedMonthKopecks: number;
  netReceivedTotalKopecks: number;
  availableKopecks: number;
  exposureOverrunKopecks: number;
  reportingLive: boolean;
  shadowMode: boolean;
  liveRouting: boolean;
}

export interface ContractorPaymentProfileRequest {
  role: ContractorRole;
  expectedVersion: number;
  enabled: boolean;
  liveEnabled: boolean;
  recipientName?: string;
  paymentPhone?: string;
  bankName?: string;
  paymentComment?: string;
  openingBalanceKopecks: number;
  openingBalanceReason?: string;
}

export interface ContractorPaymentProfileAdjustment {
  id: number;
  profileId: number;
  oldBalanceKopecks: number;
  newBalanceKopecks: number;
  deltaKopecks: number;
  reason: string;
  changedBy: string;
  effectiveAt: string;
  createdAt: string;
}

@Injectable({ providedIn: 'root' })
export class AdminUsersApi {
  constructor(private readonly http: HttpClient) {}

  getUsers(): Observable<AdminUser[]> {
    return this.http.get<AdminUser[]>(`${appEnvironment.apiBaseUrl}/api/admin/users`);
  }

  createUser(request: CreateKeycloakUserRequest): Observable<CreatedKeycloakUserResponse> {
    return this.http.post<CreatedKeycloakUserResponse>(
      `${appEnvironment.apiBaseUrl}/api/admin/users`,
      request
    );
  }

  updateUser(id: number, request: UpdateKeycloakUserRequest): Observable<AdminUser> {
    return this.http.put<AdminUser>(
      `${appEnvironment.apiBaseUrl}/api/admin/users/${id}`,
      request
    );
  }

  deleteUser(id: number): Observable<void> {
    return this.http.delete<void>(`${appEnvironment.apiBaseUrl}/api/admin/users/${id}`);
  }

  resetPersonalTelegramLink(id: number): Observable<AdminUser> {
    return this.http.delete<AdminUser>(
      `${appEnvironment.apiBaseUrl}/api/admin/users/${id}/personal-telegram-link`
    );
  }

  updateUserPhoto(id: number, photo: File): Observable<AdminUser> {
    const formData = new FormData();
    formData.append('photo', photo);

    return this.http.post<AdminUser>(
      `${appEnvironment.apiBaseUrl}/api/admin/users/${id}/photo`,
      formData
    );
  }

  changePassword(id: number, request: ChangeKeycloakPasswordRequest): Observable<void> {
    return this.http.put<void>(
      `${appEnvironment.apiBaseUrl}/api/admin/users/${id}/password`,
      request
    );
  }

  getAssignmentOptions(): Observable<AssignmentOptions> {
    return this.http.get<AssignmentOptions>(
      `${appEnvironment.apiBaseUrl}/api/admin/users/assignment-options`
    );
  }

  getUserAssignments(id: number): Observable<UserAssignments> {
    return this.http.get<UserAssignments>(
      `${appEnvironment.apiBaseUrl}/api/admin/users/${id}/assignments`
    );
  }

  updateUserAssignments(id: number, request: UpdateUserAssignmentsRequest): Observable<UserAssignments> {
    return this.http.put<UserAssignments>(
      `${appEnvironment.apiBaseUrl}/api/admin/users/${id}/assignments`,
      request
    );
  }

  getContractorPaymentProfiles(id: number): Observable<ContractorPaymentProfile[]> {
    return this.http.get<ContractorPaymentProfile[]>(
      `${appEnvironment.apiBaseUrl}/api/admin/users/${id}/contractor-payment-profiles`
    );
  }

  updateContractorPaymentProfile(
    id: number,
    request: ContractorPaymentProfileRequest
  ): Observable<ContractorPaymentProfile> {
    return this.http.put<ContractorPaymentProfile>(
      `${appEnvironment.apiBaseUrl}/api/admin/users/${id}/contractor-payment-profiles`,
      request
    );
  }

  getContractorOpeningBalanceHistory(
    userId: number,
    profileId: number
  ): Observable<ContractorPaymentProfileAdjustment[]> {
    return this.http.get<ContractorPaymentProfileAdjustment[]>(
      `${appEnvironment.apiBaseUrl}/api/admin/users/${userId}/contractor-payment-profiles/${profileId}/opening-balance-history`
    );
  }
}
