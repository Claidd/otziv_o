import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';
import { GamificationReward, GamificationRewardClaim, GamificationWallet } from './gamification.api';

export interface AdminGamificationRewardRequest {
  code: string;
  title: string;
  description?: string | null;
  rewardType: string;
  icon?: string | null;
  imageUrl?: string | null;
  tokenCost: number;
  requiredLevel: number;
  stockQuantity?: number | null;
  active: boolean;
  sortOrder: number;
}

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

  rewards(): Observable<GamificationReward[]> {
    return this.http.get<GamificationReward[]>(`${appEnvironment.apiBaseUrl}/api/admin/gamification/rewards`);
  }

  create(request: AdminGamificationRewardRequest): Observable<GamificationReward> {
    return this.http.post<GamificationReward>(`${appEnvironment.apiBaseUrl}/api/admin/gamification/rewards`, request);
  }

  update(id: number, request: AdminGamificationRewardRequest): Observable<GamificationReward> {
    return this.http.put<GamificationReward>(`${appEnvironment.apiBaseUrl}/api/admin/gamification/rewards/${id}`, request);
  }

  uploadImage(id: number, file: File): Observable<GamificationReward> {
    const body = new FormData();
    body.append('file', file);
    return this.http.post<GamificationReward>(`${appEnvironment.apiBaseUrl}/api/admin/gamification/rewards/${id}/image`, body);
  }

  claims(): Observable<GamificationRewardClaim[]> {
    return this.http.get<GamificationRewardClaim[]>(`${appEnvironment.apiBaseUrl}/api/admin/gamification/reward-claims`);
  }

  updateClaim(id: number, status: string, adminComment = ''): Observable<GamificationRewardClaim> {
    return this.http.put<GamificationRewardClaim>(`${appEnvironment.apiBaseUrl}/api/admin/gamification/reward-claims/${id}`, { status, adminComment });
  }

  grantTokens(userId: number, amount: number, description: string): Observable<GamificationWallet> {
    return this.http.post<GamificationWallet>(`${appEnvironment.apiBaseUrl}/api/admin/gamification/tokens/grant`, { userId, amount, description });
  }

  settings(): Observable<GamificationRewardSettings> {
    return this.http.get<GamificationRewardSettings>(`${appEnvironment.apiBaseUrl}/api/admin/gamification/reward-settings`);
  }

  updateSettings(settings: GamificationRewardSettings): Observable<GamificationRewardSettings> {
    return this.http.put<GamificationRewardSettings>(`${appEnvironment.apiBaseUrl}/api/admin/gamification/reward-settings`, settings);
  }
}
