import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';

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
  originalFilename?: string | null;
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
  eventCode?: string | null;
  enabled: boolean;
  imageProbabilityPercent: number;
  cooldownMinutes: number;
}

export interface NotificationMediaAssetRequest {
  active: boolean;
  sortOrder: number;
}

export interface NotificationMediaTestResponse {
  sent: boolean;
  message: string;
}

@Injectable({ providedIn: 'root' })
export class AdminNotificationMediaApi {
  private readonly baseUrl = `${appEnvironment.apiBaseUrl}/api/admin/notification-media`;

  constructor(private readonly http: HttpClient) {}

  events(): Observable<NotificationMediaEvent[]> {
    return this.http.get<NotificationMediaEvent[]>(`${this.baseUrl}/events`);
  }

  rules(): Observable<NotificationMediaRule[]> {
    return this.http.get<NotificationMediaRule[]>(`${this.baseUrl}/rules`);
  }

  create(request: NotificationMediaRuleRequest): Observable<NotificationMediaRule> {
    return this.http.post<NotificationMediaRule>(`${this.baseUrl}/rules`, request);
  }

  update(ruleId: number, request: NotificationMediaRuleRequest): Observable<NotificationMediaRule> {
    return this.http.put<NotificationMediaRule>(`${this.baseUrl}/rules/${ruleId}`, request);
  }

  deleteRule(ruleId: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/rules/${ruleId}`);
  }

  uploadImages(ruleId: number, files: File[]): Observable<NotificationMediaRule> {
    const body = new FormData();
    files.forEach((file) => body.append('files', file));
    return this.http.post<NotificationMediaRule>(`${this.baseUrl}/rules/${ruleId}/images`, body);
  }

  updateAsset(assetId: number, request: NotificationMediaAssetRequest): Observable<NotificationMediaRule> {
    return this.http.put<NotificationMediaRule>(`${this.baseUrl}/images/${assetId}`, request);
  }

  replaceAsset(assetId: number, file: File): Observable<NotificationMediaRule> {
    const body = new FormData();
    body.append('file', file);
    return this.http.put<NotificationMediaRule>(`${this.baseUrl}/images/${assetId}/file`, body);
  }

  deleteAsset(assetId: number): Observable<NotificationMediaRule> {
    return this.http.delete<NotificationMediaRule>(`${this.baseUrl}/images/${assetId}`);
  }

  test(ruleId: number): Observable<NotificationMediaTestResponse> {
    return this.http.post<NotificationMediaTestResponse>(`${this.baseUrl}/rules/${ruleId}/test`, {});
  }
}
