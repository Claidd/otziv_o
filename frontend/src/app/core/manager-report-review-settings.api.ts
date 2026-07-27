import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';

export interface ManagerReportReviewManagerSetting {
  managerId: number;
  managerName: string;
  userActive: boolean;
  auditEnabled: boolean;
  auditGroupConnected: boolean;
}

export interface ManagerReportReviewSettings {
  enabled: boolean;
  managerGroupsEnabled: boolean;
  restrictionEnabled: boolean;
  maxQuestionCount: number;
  minimumReadSeconds: number;
  testMinimumReadSeconds: number;
  reminderOneMinutes: number;
  reminderThreeMinutes: number;
  minimumAnswerScore: number;
  maxAnswerCharacters: number;
  maxPlanCharacters: number;
  fastPasteSeconds: number;
  fastPasteMinCharacters: number;
  copyGramSize: number;
  copySimilarityPercent: number;
  aiTimeoutSeconds: number;
  questionGenerationMaxTokens: number;
  questionGenerationRetryMaxTokens: number;
  managers: ManagerReportReviewManagerSetting[];
}

export type ManagerReportReviewSettingsRequest = Omit<ManagerReportReviewSettings, 'managers'>;

@Injectable({ providedIn: 'root' })
export class ManagerReportReviewSettingsApi {
  private readonly url = `${appEnvironment.apiBaseUrl}/api/admin/settings/manager-report-review`;

  constructor(private readonly http: HttpClient) {}

  settings(): Observable<ManagerReportReviewSettings> {
    return this.http.get<ManagerReportReviewSettings>(this.url);
  }

  update(request: ManagerReportReviewSettingsRequest): Observable<ManagerReportReviewSettings> {
    return this.http.put<ManagerReportReviewSettings>(this.url, request);
  }

  updateManager(
    managerId: number,
    enabled: boolean
  ): Observable<ManagerReportReviewManagerSetting> {
    return this.http.put<ManagerReportReviewManagerSetting>(
      `${this.url}/managers/${managerId}`,
      { enabled }
    );
  }
}
