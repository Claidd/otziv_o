import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';

export interface UserLk {
  username: string;
  role: string;
  image: number;
  leadCount: number;
  reviewCount: number;
}

export interface UserStat {
  id: number;
  fio: string;
  imageId: number;
  coefficient?: number | null;
  percentNoPay?: number | null;
  avgPublish1Day?: number | null;
  zpPayMap?: string | null;
  zpPayMapMonth?: string | null;
  sum1Day: number;
  sum1Week: number;
  sum1Month: number;
  sum1Year: number;
  sumOrders1Month: number;
  sumOrders2Month: number;
  percent1Day: number;
  percent1Week: number;
  percent1Month: number;
  percent1Year: number;
  percent1MonthOrders: number;
  percent2MonthOrders: number;
}

export interface StatDto {
  zpPayMap?: string | null;
  zpPayMapMonth?: string | null;
  orderPayMap?: string | null;
  orderPayMapMonth?: string | null;
  sum1DayPay: number;
  sum1WeekPay: number;
  sum1MonthPay: number;
  sum1YearPay: number;
  sumOrders1MonthPay: number;
  sumOrders2MonthPay: number;
  newLeads: number;
  leadsInWork: number;
  percent1DayPay: number;
  percent1WeekPay: number;
  percent1MonthPay: number;
  percent1YearPay: number;
  percent1MonthOrdersPay: number;
  percent2MonthOrdersPay: number;
  percent1NewLeadsPay: number;
  percent2InWorkLeadsPay: number;
  sum1Day: number;
  sum1Week: number;
  sum1Month: number;
  sum1Year: number;
  sumOrders1Month: number;
  sumOrders2Month: number;
  percent1Day: number;
  percent1Week: number;
  percent1Month: number;
  percent1Year: number;
  percent1MonthOrders: number;
  percent2MonthOrders: number;
}

export interface CabinetProfile {
  date: string;
  user: UserLk;
  workerZp: UserStat;
}

export interface CabinetUserInfo {
  date: string;
  currentUser: UserLk;
  workerZp: UserStat;
}

export interface TeamMember {
  id: number;
  userId: number;
  login: string;
  fio: string;
  imageId: number;
  sum1Month?: number;
  order1Month?: number;
  review1Month?: number;
  payment1Month?: number;
  leadsInWorkInMonth?: number;
  leadsNew?: number;
  leadsInWork?: number;
  percentInWork?: number;
  newOrder?: number;
  inCorrect?: number;
  intVigul?: number;
  publish?: number;
}

export interface TeamResponse {
  date: string;
  role: string;
  canEditUsers: boolean;
  canAddUsers: boolean;
  canOpenUserInfo: boolean;
  managers: TeamMember[];
  marketologs: TeamMember[];
  workers: TeamMember[];
  operators: TeamMember[];
}

export interface ScoreUser {
  fio: string;
  role: string;
  salary?: number | null;
  totalSum?: number | null;
  zpTotal?: number | null;
  newCompanies?: number | null;
  newOrders?: number | null;
  correctOrders?: number | null;
  inVigul?: number | null;
  inPublish?: number | null;
  imageId?: number | null;
  userId?: number | null;
  order1Month?: number | null;
  review1Month?: number | null;
  leadsNew?: number | null;
  leadsInWork?: number | null;
  percentInWork?: number | null;
}

export interface ScoreResponse {
  date: string;
  user: UserLk;
  financeVisible: boolean;
  groups: {
    managers: ScoreUser[];
    marketologs: ScoreUser[];
    workers: ScoreUser[];
    operators: ScoreUser[];
  };
}

export interface AnalyticsResponse {
  date: string;
  user: UserLk;
  stats: StatDto;
}

@Injectable({ providedIn: 'root' })
export class CabinetApi {
  constructor(private readonly http: HttpClient) {}

  getProfile(date?: string): Observable<CabinetProfile> {
    return this.http.get<CabinetProfile>(`${appEnvironment.apiBaseUrl}/api/cabinet/profile`, {
      params: this.paramsWithDate(date)
    });
  }

  getUserInfo(userId: number, date?: string): Observable<CabinetUserInfo> {
    return this.http.get<CabinetUserInfo>(`${appEnvironment.apiBaseUrl}/api/cabinet/user-info`, {
      params: this.paramsWithDate(date).set('userId', userId)
    });
  }

  getTeam(date?: string): Observable<TeamResponse> {
    return this.http.get<TeamResponse>(`${appEnvironment.apiBaseUrl}/api/cabinet/team`, {
      params: this.paramsWithDate(date)
    });
  }

  getScore(date?: string): Observable<ScoreResponse> {
    return this.http.get<ScoreResponse>(`${appEnvironment.apiBaseUrl}/api/cabinet/score`, {
      params: this.paramsWithDate(date)
    });
  }

  getAnalytics(date?: string): Observable<AnalyticsResponse> {
    return this.http.get<AnalyticsResponse>(`${appEnvironment.apiBaseUrl}/api/cabinet/analyse`, {
      params: this.paramsWithDate(date)
    });
  }

  imageUrl(imageId?: number | null): string {
    const id = imageId || 1;
    return `${appEnvironment.legacyBaseUrl}/images/${id}`;
  }

  private paramsWithDate(date?: string): HttpParams {
    return date ? new HttpParams().set('date', date) : new HttpParams();
  }
}
