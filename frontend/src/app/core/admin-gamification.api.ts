import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';
import type {
  AdminGamificationSettings,
  AdminGamificationSettingsRequest,
  AdminGamificationEvent,
  AdminGamificationProgress,
  AdminGamificationRulesResponse,
  AdminGamificationRulesRequest,
  AdminGamificationScorePreview,
  AdminGamificationScoreLedger,
  AdminGamificationScoreLedgerRebuild,
  AdminGamificationBackfill,
  AdminGamificationBalances
} from './admin-dictionaries.api';

@Injectable({ providedIn: 'root' })
export class AdminGamificationApi {
  private readonly baseUrl = `${appEnvironment.apiBaseUrl}/api/admin`;
  constructor(private readonly http: HttpClient) {}

  getGamificationSettings(): Observable<AdminGamificationSettings> {
    return this.http.get<AdminGamificationSettings>(`${this.baseUrl}/gamification/settings`);
  }

  updateGamificationSettings(
    request: AdminGamificationSettingsRequest
  ): Observable<AdminGamificationSettings> {
    return this.http.put<AdminGamificationSettings>(
      `${this.baseUrl}/gamification/settings`,
      request
    );
  }

  getGamificationEvents(limit = 50): Observable<AdminGamificationEvent[]> {
    return this.http.get<AdminGamificationEvent[]>(`${this.baseUrl}/gamification/events`, {
      params: { limit }
    });
  }

  getGamificationProgress(days = 1): Observable<AdminGamificationProgress> {
    return this.http.get<AdminGamificationProgress>(`${this.baseUrl}/gamification/progress`, {
      params: { days }
    });
  }

  getGamificationRules(): Observable<AdminGamificationRulesResponse> {
    return this.http.get<AdminGamificationRulesResponse>(`${this.baseUrl}/gamification/rules`);
  }

  updateGamificationRules(
    request: AdminGamificationRulesRequest
  ): Observable<AdminGamificationRulesResponse> {
    return this.http.put<AdminGamificationRulesResponse>(
      `${this.baseUrl}/gamification/rules`,
      request
    );
  }

  getGamificationScorePreview(days = 1): Observable<AdminGamificationScorePreview> {
    return this.http.get<AdminGamificationScorePreview>(
      `${this.baseUrl}/gamification/score-preview`,
      {
        params: { days }
      }
    );
  }

  getGamificationScoreLedger(days = 1): Observable<AdminGamificationScoreLedger> {
    return this.http.get<AdminGamificationScoreLedger>(
      `${this.baseUrl}/gamification/score-ledger`,
      {
        params: { days }
      }
    );
  }

  rebuildGamificationScoreLedger(days = 1): Observable<AdminGamificationScoreLedgerRebuild> {
    return this.http.post<AdminGamificationScoreLedgerRebuild>(
      `${this.baseUrl}/gamification/score-ledger/rebuild`,
      {},
      { params: { days } }
    );
  }

  backfillGamificationEvents(days = 1): Observable<AdminGamificationBackfill> {
    return this.http.post<AdminGamificationBackfill>(
      `${this.baseUrl}/gamification/events/backfill`,
      {},
      { params: { days } }
    );
  }

  getGamificationBalances(days = 1): Observable<AdminGamificationBalances> {
    return this.http.get<AdminGamificationBalances>(`${this.baseUrl}/gamification/balances`, {
      params: { days }
    });
  }
}
