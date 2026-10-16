import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { mobileEnvironment } from './mobile-environment';

export type NotificationRecipientType = 'MANAGER' | 'WORKER';

export interface NotificationMediaEvent {
  code: string;
  recipientType: NotificationRecipientType;
  label: string;
  description: string;
  serious: boolean;
}

export interface NotificationMediaAsset {
  id: number;
  imageUrl: string;
  originalFilename: string | null;
  contentType: string;
  active: boolean;
  sortOrder: number;
  createdAt: string;
  updatedAt: string;
}

export interface NotificationMediaRule {
  id: number;
  eventCode: string;
  recipientType: NotificationRecipientType;
  eventLabel: string;
  eventDescription: string;
  serious: boolean;
  enabled: boolean;
  imageProbabilityPercent: number;
  cooldownMinutes: number;
  images: NotificationMediaAsset[];
  createdAt: string;
  updatedAt: string;
}

export interface NotificationMediaRuleRequest {
  eventCode: string;
  enabled: boolean;
  imageProbabilityPercent: number;
  cooldownMinutes: number;
}

export interface NotificationMediaTestResponse {
  sent: boolean;
  message: string;
}

@Injectable({ providedIn: 'root' })
export class AdminNotificationMediaApi {
  private readonly http = inject(HttpClient);
  private readonly root = `${mobileEnvironment.apiBaseUrl}/api/admin/notification-media`;

  events(): Observable<NotificationMediaEvent[]> {
    return this.http.get<NotificationMediaEvent[]>(`${this.root}/events`);
  }

  rules(): Observable<NotificationMediaRule[]> {
    return this.http.get<NotificationMediaRule[]>(`${this.root}/rules`);
  }

  create(request: NotificationMediaRuleRequest): Observable<NotificationMediaRule> {
    return this.http.post<NotificationMediaRule>(`${this.root}/rules`, request);
  }

  update(ruleId: number, request: NotificationMediaRuleRequest): Observable<NotificationMediaRule> {
    return this.http.put<NotificationMediaRule>(`${this.root}/rules/${ruleId}`, request);
  }

  deleteRule(ruleId: number): Observable<void> {
    return this.http.delete<void>(`${this.root}/rules/${ruleId}`);
  }

  uploadImages(ruleId: number, files: File[]): Observable<NotificationMediaRule> {
    const form = new FormData();
    files.forEach((file) => form.append('files', file));
    return this.http.post<NotificationMediaRule>(`${this.root}/rules/${ruleId}/images`, form);
  }

  updateAsset(assetId: number, active: boolean, sortOrder: number): Observable<NotificationMediaRule> {
    return this.http.put<NotificationMediaRule>(`${this.root}/images/${assetId}`, { active, sortOrder });
  }

  replaceAsset(assetId: number, file: File): Observable<NotificationMediaRule> {
    const form = new FormData();
    form.append('file', file);
    return this.http.put<NotificationMediaRule>(`${this.root}/images/${assetId}/file`, form);
  }

  deleteAsset(assetId: number): Observable<NotificationMediaRule> {
    return this.http.delete<NotificationMediaRule>(`${this.root}/images/${assetId}`);
  }

  sendTest(ruleId: number): Observable<NotificationMediaTestResponse> {
    return this.http.post<NotificationMediaTestResponse>(`${this.root}/rules/${ruleId}/test`, {});
  }
}
