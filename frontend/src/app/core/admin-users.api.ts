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
  imageId?: number | null;
  active: boolean;
  createTime?: string;
  lastLoginAt?: string;
  roles: string[];
}

export interface UpdateKeycloakUserRequest {
  email?: string;
  fio?: string;
  phoneNumber?: string;
  coefficient?: number;
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
  managerIds: number[];
  workerIds: number[];
  operatorIds: number[];
  marketologIds: number[];
}

export interface UpdateUserAssignmentsRequest {
  managerIds: number[];
  workerIds: number[];
  operatorIds: number[];
  marketologIds: number[];
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
}
