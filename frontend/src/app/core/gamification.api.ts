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

@Injectable({ providedIn: 'root' })
export class GamificationApi {
  constructor(private readonly http: HttpClient) {}

  getMyProgress(days = 7): Observable<GamificationMyProgress> {
    return this.http.get<GamificationMyProgress>(`${appEnvironment.apiBaseUrl}/api/gamification/me`, {
      params: { days }
    });
  }
}
