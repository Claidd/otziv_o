import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';
import type {
  BotBrowserOpenResponse,
  BotBrowserMetadata,
  BotsResponse,
  AdminBot,
  BotCountResponse,
  BotCityUnblockedCountResponse,
  BotRequest,
  BotImportResponse
} from './admin-dictionaries.api';

@Injectable({ providedIn: 'root' })
export class AdminAccountsApi {
  private readonly baseUrl = `${appEnvironment.apiBaseUrl}/api/admin`;
  constructor(private readonly http: HttpClient) {}

  getBots(keyword = '', page = 0, size = 50): Observable<BotsResponse> {
    const params = this.keywordParams(keyword).set('page', String(page)).set('size', String(size));
    return this.http.get<BotsResponse>(`${this.baseUrl}/bots`, {
      params
    });
  }

  getBot(id: number): Observable<AdminBot> {
    return this.http.get<AdminBot>(`${this.baseUrl}/bots/${id}`);
  }

  getBotCount(): Observable<BotCountResponse> {
    return this.http.get<BotCountResponse>(`${this.baseUrl}/bots/count`);
  }

  getBotCityUnblockedCount(cityId: number): Observable<BotCityUnblockedCountResponse> {
    const params = new HttpParams().set('cityId', String(cityId));
    return this.http.get<BotCityUnblockedCountResponse>(`${this.baseUrl}/bots/unblocked-count`, {
      params
    });
  }

  createBot(request: BotRequest): Observable<AdminBot> {
    return this.http.post<AdminBot>(`${this.baseUrl}/bots`, request);
  }

  updateBot(id: number, request: BotRequest): Observable<AdminBot> {
    return this.http.put<AdminBot>(`${this.baseUrl}/bots/${id}`, request);
  }

  deleteBot(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/bots/${id}`);
  }

  importBots(file: File, cityId?: number | null): Observable<BotImportResponse> {
    const formData = new FormData();
    formData.append('file', file);
    const params = cityId == null ? undefined : new HttpParams().set('cityId', String(cityId));
    return this.http.post<BotImportResponse>(`${this.baseUrl}/bots/import`, formData, { params });
  }

  openBotBrowser(botId: number): Observable<BotBrowserOpenResponse> {
    return this.http.post<BotBrowserOpenResponse>(
      `${appEnvironment.apiBaseUrl}/api/bots/${botId}/browser/open`,
      { heartbeatSupported: true }
    );
  }

  getBotBrowserMetadata(botId: number): Observable<BotBrowserMetadata> {
    return this.http.get<BotBrowserMetadata>(
      `${appEnvironment.apiBaseUrl}/api/bots/${botId}/browser/metadata`
    );
  }

  heartbeatBotBrowser(botId: number, sessionId: string): Observable<void> {
    return this.http.post<void>(
      `${appEnvironment.apiBaseUrl}/api/bots/${botId}/browser/sessions/${encodeURIComponent(sessionId)}/heartbeat`,
      {}
    );
  }

  closeBotBrowser(botId: number, sessionId?: string | null): Observable<void> {
    const path = sessionId
      ? `/api/bots/${botId}/browser/sessions/${encodeURIComponent(sessionId)}/close`
      : `/api/bots/${botId}/browser/close`;
    return this.http.post<void>(
      `${appEnvironment.apiBaseUrl}${path}`,
      {}
    );
  }

  private keywordParams(keyword: string): HttpParams {
    const value = keyword.trim();
    return value ? new HttpParams().set('keyword', value) : new HttpParams();
  }
}
