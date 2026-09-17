import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { appEnvironment } from './app-environment';

export interface OfferSettings {
  title: string; message: string; dailyLimit: number; intervalMinutes: number;
  windowStart: string; windowEnd: string; includeActive: boolean; includeStopped: boolean; includeBanned: boolean;
  fileMode: 'ATTACHMENT' | 'LINK';
}
export interface OfferCampaign {
  id: string; settings: OfferSettings; state: string; fileName: string | null;
  createdAt: string; startedAt: string | null; nextAt: string | null;
}
export interface OfferCounts {
  total: number; pending: number; sending: number; sent: number; failed: number; unknown: number; skipped: number;
}
export interface OfferSummary { campaign: OfferCampaign; counts: OfferCounts; usedToday: number }
export interface OfferBoard { liveEnabled: boolean; campaigns: OfferSummary[] }
export interface OfferAudience { audience: string; total: number; reachable: number }
export interface OfferRecipient {
  id: number; companyId: number; companyTitle: string; audience: string; state: string;
  errorMessage: string | null; finishedAt: string | null;
}

@Injectable({ providedIn: 'root' })
export class ClientOffersApi {
  private readonly http = inject(HttpClient);
  private readonly url = `${appEnvironment.apiBaseUrl}/api/admin/client-offers`;
  board() { return this.http.get<OfferBoard>(this.url); }
  preview(settings: OfferSettings) { return this.http.post<OfferAudience[]>(`${this.url}/preview`, settings); }
  save(id: string, settings: OfferSettings, file: File | null, removeFile: boolean) {
    const body = new FormData();
    body.append('settings', new Blob([JSON.stringify(settings)], { type: 'application/json' }));
    if (file) body.append('file', file);
    return this.http.put<OfferCampaign>(`${this.url}/${id}?removeFile=${removeFile}`, body);
  }
  action(id: string, action: string) { return this.http.post<OfferBoard>(`${this.url}/${id}/${action}`, {}); }
  recipients(id: string, page: number) { return this.http.get<OfferRecipient[]>(`${this.url}/${id}/recipients`, { params: { page } }); }
  file(id: string) { return this.http.get(`${this.url}/${id}/file`, { responseType: 'blob' }); }
}
