import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';

export interface GamificationRewardSettings {
  rewardsEnabled: boolean;
  competitionEnabled: boolean;
  levelXp: number;
  tokenLevelStep: number;
  slaEnabled: boolean;
  controlTargetHours: number;
  dayTargetPercent: number;
  messageTargetMinutes: number;
  messageHardMinutes: number;
  leadTargetMinutes: number;
  leadHardMinutes: number;
  riskTargetMinutes: number;
  riskHardMinutes: number;
  defaultTargetMinutes: number;
  defaultHardMinutes: number;
}

@Injectable({ providedIn: 'root' })
export class AdminGamificationRewardsApi {
  constructor(private readonly http: HttpClient) {}

  settings(): Observable<GamificationRewardSettings> {
    return this.http.get<GamificationRewardSettings>(`${appEnvironment.apiBaseUrl}/api/admin/gamification/reward-settings`);
  }

  updateSettings(settings: GamificationRewardSettings): Observable<GamificationRewardSettings> {
    return this.http.put<GamificationRewardSettings>(`${appEnvironment.apiBaseUrl}/api/admin/gamification/reward-settings`, settings);
  }
}
