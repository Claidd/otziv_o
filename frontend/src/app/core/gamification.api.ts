import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';

export interface GamificationMyBreakdown {
  eventType: string;
  events: number;
  points: number;
}

export interface GamificationMyMission {
  code: string;
  title: string;
  description: string;
  progress: number;
  target: number;
  percent: number;
  completed: boolean;
}

export interface GamificationMyProgress {
  enabled: boolean;
  from: string;
  to: string;
  days: number;
  actorUserId?: number | null;
  actorName?: string | null;
  actorRole?: string | null;
  totalEvents: number;
  totalPoints: number;
  lifetimeXp: number;
  tokenBalance: number;
  nextTokenLevel: number;
  dailyGoal: number;
  dailyProgress: number;
  dailyGoalPercent: number;
  level: number;
  currentLevelPoints: number;
  nextLevelPoints: number;
  pointsToNextLevel: number;
  onTimeEvents: number;
  delayedEvents: number;
  lostPoints: number;
  timelinessPercent: number;
  streakDays: number;
  missions: GamificationMyMission[];
  breakdown: GamificationMyBreakdown[];
}

export interface GamificationReward {
  id: number;
  code: string;
  title: string;
  description?: string | null;
  rewardType: 'VIRTUAL' | 'MATERIAL' | 'PRIVILEGE' | 'CERTIFICATE' | string;
  icon?: string | null;
  imageUrl?: string | null;
  tokenCost: number;
  requiredLevel: number;
  stockQuantity?: number | null;
  active: boolean;
  sortOrder: number;
  claimable: boolean;
  lockedReason?: string | null;
}

export interface GamificationWallet {
  lifetimeXp: number;
  level: number;
  tokens: number;
  nextTokenLevel: number;
}

export interface GamificationRewardClaim {
  id: number;
  rewardId: number;
  rewardTitle: string;
  rewardImageUrl?: string | null;
  userId: number;
  userName: string;
  status: string;
  tokenCost: number;
  comment?: string | null;
  adminComment?: string | null;
  requestedAt: string;
  updatedAt: string;
  fulfilledAt?: string | null;
}

@Injectable({ providedIn: 'root' })
export class GamificationApi {
  constructor(private readonly http: HttpClient) {}

  getMyProgress(days = 7): Observable<GamificationMyProgress> {
    return this.http.get<GamificationMyProgress>(`${appEnvironment.apiBaseUrl}/api/gamification/me`, {
      params: { days }
    });
  }

  getWallet(): Observable<GamificationWallet> {
    return this.http.get<GamificationWallet>(`${appEnvironment.apiBaseUrl}/api/gamification/wallet`);
  }

  getRewards(): Observable<GamificationReward[]> {
    return this.http.get<GamificationReward[]>(`${appEnvironment.apiBaseUrl}/api/gamification/rewards`);
  }

  getMyClaims(): Observable<GamificationRewardClaim[]> {
    return this.http.get<GamificationRewardClaim[]>(`${appEnvironment.apiBaseUrl}/api/gamification/reward-claims`);
  }

  claimReward(rewardId: number, comment = ''): Observable<GamificationRewardClaim> {
    return this.http.post<GamificationRewardClaim>(
      `${appEnvironment.apiBaseUrl}/api/gamification/rewards/${rewardId}/claim`,
      { comment }
    );
  }
}
