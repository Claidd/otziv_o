import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import type { Observable } from 'rxjs';
import { mobileEnvironment } from './mobile-environment';

export interface MobileOfferSettings {
  title: string;
  message: string;
  dailyLimit: number;
  intervalMinutes: number;
  windowStart: string;
  windowEnd: string;
  includeActive: boolean;
  includeStopped: boolean;
  includeBanned: boolean;
  fileMode: 'ATTACHMENT' | 'LINK';
  testOnly: boolean;
  includeLeadInWork: boolean;
  includeLeadOther: boolean;
  leadFallbackClientId: string | null;
}

export interface MobileOfferCampaign {
  id: string;
  settings: MobileOfferSettings;
  state: 'DRAFT' | 'RUNNING' | 'PAUSED' | 'COMPLETED' | 'CANCELLED';
  fileName: string | null;
  createdAt: string;
  startedAt: string | null;
  nextAt: string | null;
}

export interface MobileOfferCounts {
  total: number;
  pending: number;
  sending: number;
  sent: number;
  failed: number;
  unknown: number;
  skipped: number;
}

export interface MobileOfferSummary {
  campaign: MobileOfferCampaign;
  counts: MobileOfferCounts;
  usedToday: number;
}

export interface MobileOfferBoard {
  liveEnabled: boolean;
  campaigns: MobileOfferSummary[];
}

export interface MobileOfferAudience {
  audience: string;
  total: number;
  reachable: number;
}

export interface MobileOfferRecipient {
  id: number;
  companyTitle: string;
  audience: string;
  state: 'PENDING' | 'SENDING' | 'SENT' | 'FAILED' | 'UNKNOWN' | 'SKIPPED';
  errorMessage: string | null;
  finishedAt: string | null;
}

export type MobileOfferAction = 'start' | 'pause' | 'resume' | 'cancel' | 'retry-failed';

@Injectable({ providedIn: 'root' })
export class MobileClientOffersApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${mobileEnvironment.apiBaseUrl}/api/admin/client-offers`;

  board(): Observable<MobileOfferBoard> {
    return this.http.get<MobileOfferBoard>(this.baseUrl);
  }

  leadSenders(): Observable<string[]> {
    return this.http.get<string[]>(`${this.baseUrl}/lead-senders`);
  }

  preview(settings: MobileOfferSettings): Observable<MobileOfferAudience[]> {
    return this.http.post<MobileOfferAudience[]>(`${this.baseUrl}/preview`, settings);
  }

  save(id: string, settings: MobileOfferSettings, file: File | null, removeFile: boolean): Observable<MobileOfferCampaign> {
    const body = new FormData();
    body.append('settings', new Blob([JSON.stringify(settings)], { type: 'application/json' }));
    if (file) body.append('file', file, file.name);
    return this.http.put<MobileOfferCampaign>(`${this.baseUrl}/${id}`, body, { params: { removeFile } });
  }

  action(id: string, action: MobileOfferAction): Observable<MobileOfferBoard> {
    return this.http.post<MobileOfferBoard>(`${this.baseUrl}/${id}/${action}`, {});
  }

  recipients(id: string, page: number): Observable<MobileOfferRecipient[]> {
    return this.http.get<MobileOfferRecipient[]>(`${this.baseUrl}/${id}/recipients`, { params: { page } });
  }

  file(id: string): Observable<Blob> {
    return this.http.get(`${this.baseUrl}/${id}/file`, { responseType: 'blob' });
  }
}
