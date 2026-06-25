import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { catchError, Observable, shareReplay, throwError } from 'rxjs';
import { appEnvironment } from './app-environment';
import { SKIP_AUTH_REDIRECT_ON_401 } from './auth-http-context';
import { AuthService } from './auth.service';
import type {
  CreateManualPaymentTaskRequest,
  ManualPaymentTaskResponse,
  ManualPaymentType,
  ManualPaymentTaskStatus,
  UpdateManualPaymentTaskRequest
} from './payments.api';

export type {
  CreateManualPaymentTaskRequest,
  ManualPaymentTaskResponse,
  ManualPaymentType,
  ManualPaymentTaskStatus,
  UpdateManualPaymentTaskRequest
} from './payments.api';

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

export interface ManagerManualPaymentSettings {
  profileId?: number | null;
  profileName: string;
  paymentPolicy: string;
  manualPaymentEnabled: boolean;
  manualPaymentType?: ManualPaymentType | string | null;
  manualPhone: string;
  manualRecipientName: string;
  manualPaymentUrl?: string | null;
  manualPaymentButtonLabel?: string | null;
}

export interface UpdateManagerManualPaymentSettingsRequest {
  manualPaymentType?: ManualPaymentType | string | null;
  manualPhone: string;
  manualRecipientName: string;
  manualPaymentUrl?: string | null;
  manualPaymentButtonLabel?: string | null;
}

export interface WhatsAppClientStatus {
  clientId: string;
  configured: boolean;
  ready: boolean;
  authenticated: boolean;
  state: string;
  lastQrAt?: string | null;
  lastReadyAt?: string | null;
  lastError?: string | null;
  hasQr: boolean;
  qrDataUrl?: string | null;
  message?: string | null;
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
  period?: AnalyticsPeriod;
  user: UserLk;
  stats: StatDto;
}

export type CacheOptions = {
  forceRefresh?: boolean;
  skipAuthRedirectOn401?: boolean;
};

export type AnalyticsPeriod = {
  from: string;
  to: string;
  allTime: boolean;
};

export type AnalyticsOptions = CacheOptions & {
  from?: string;
  to?: string;
  allTime?: boolean;
};

type CacheEntry<T> = {
  expiresAt: number;
  request$: Observable<T>;
};

@Injectable({ providedIn: 'root' })
export class CabinetApi {
  private readonly profileCache = new Map<string, CacheEntry<CabinetProfile>>();
  private readonly userInfoCache = new Map<string, CacheEntry<CabinetUserInfo>>();
  private readonly teamCache = new Map<string, CacheEntry<TeamResponse>>();
  private readonly scoreCache = new Map<string, CacheEntry<ScoreResponse>>();
  private readonly analyticsCache = new Map<string, CacheEntry<AnalyticsResponse>>();
  private readonly cabinetCacheTtlMs = 3 * 60 * 60_000;
  private readonly cabinetCacheMaxEntries = 200;

  constructor(
    private readonly http: HttpClient,
    private readonly auth: AuthService
  ) {}

  getProfile(date?: string, options: CacheOptions = {}): Observable<CabinetProfile> {
    const cacheKey = this.cacheKey('profile', date ?? 'current');

    return this.cached(this.profileCache, cacheKey, options, () =>
      this.http.get<CabinetProfile>(`${appEnvironment.apiBaseUrl}/api/cabinet/profile`, {
        context: this.requestContext(options),
        params: this.paramsWithDate(date, options)
      })
    );
  }

  getUserInfo(userId: number, date?: string, options: CacheOptions = {}): Observable<CabinetUserInfo> {
    const cacheKey = this.cacheKey('user-info', String(userId), date ?? 'current');

    return this.cached(this.userInfoCache, cacheKey, options, () =>
      this.http.get<CabinetUserInfo>(`${appEnvironment.apiBaseUrl}/api/cabinet/user-info`, {
        params: this.paramsWithDate(date, options).set('userId', userId)
      })
    );
  }

  getTeam(date?: string, options: CacheOptions = {}): Observable<TeamResponse> {
    const cacheKey = this.cacheKey('team', date ?? 'current');

    return this.cached(this.teamCache, cacheKey, options, () =>
      this.http.get<TeamResponse>(`${appEnvironment.apiBaseUrl}/api/cabinet/team`, {
        params: this.paramsWithDate(date, options)
      })
    );
  }

  getScore(date?: string, options: CacheOptions = {}): Observable<ScoreResponse> {
    const cacheKey = this.cacheKey('score', date ?? 'current');

    return this.cached(this.scoreCache, cacheKey, options, () =>
      this.http.get<ScoreResponse>(`${appEnvironment.apiBaseUrl}/api/cabinet/score`, {
        params: this.paramsWithDate(date, options)
      })
    );
  }

  getAnalytics(date?: string, options: AnalyticsOptions = {}): Observable<AnalyticsResponse> {
    const cacheKey = this.cacheKey(
      'analytics',
      date ?? 'current',
      options.allTime ? 'all-time' : 'bounded',
      options.from ?? 'auto-from',
      options.to ?? 'auto-to'
    );

    return this.cached(this.analyticsCache, cacheKey, options, () =>
      this.http.get<AnalyticsResponse>(`${appEnvironment.apiBaseUrl}/api/cabinet/analyse`, {
        params: this.paramsWithAnalyticsPeriod(date, options)
      })
    );
  }

  getWhatsAppBindingStatus(options: CacheOptions = {}): Observable<WhatsAppClientStatus> {
    return this.http.get<WhatsAppClientStatus>(`${appEnvironment.apiBaseUrl}/api/cabinet/whatsapp`, {
      context: this.requestContext(options)
    });
  }

  getManagerManualPaymentSettings(options: CacheOptions = {}): Observable<ManagerManualPaymentSettings> {
    return this.http.get<ManagerManualPaymentSettings>(
      `${appEnvironment.apiBaseUrl}/api/cabinet/payment-profile/manual`,
      { context: this.requestContext(options) }
    );
  }

  updateManagerManualPaymentSettings(
    request: UpdateManagerManualPaymentSettingsRequest
  ): Observable<ManagerManualPaymentSettings> {
    return this.http.put<ManagerManualPaymentSettings>(
      `${appEnvironment.apiBaseUrl}/api/cabinet/payment-profile/manual`,
      request
    );
  }

  getManagerManualPaymentTasks(options: CacheOptions = {}): Observable<ManualPaymentTaskResponse[]> {
    return this.http.get<ManualPaymentTaskResponse[]>(
      `${appEnvironment.apiBaseUrl}/api/cabinet/manual-payment-tasks`,
      { context: this.requestContext(options) }
    );
  }

  createManagerManualPaymentTask(
    request: CreateManualPaymentTaskRequest
  ): Observable<ManualPaymentTaskResponse> {
    return this.http.post<ManualPaymentTaskResponse>(
      `${appEnvironment.apiBaseUrl}/api/cabinet/manual-payment-tasks`,
      request
    );
  }

  updateManagerManualPaymentTaskStatus(
    taskId: number,
    status: ManualPaymentTaskStatus
  ): Observable<ManualPaymentTaskResponse> {
    return this.http.put<ManualPaymentTaskResponse>(
      `${appEnvironment.apiBaseUrl}/api/cabinet/manual-payment-tasks/${taskId}/status`,
      { status }
    );
  }

  updateManagerManualPaymentTask(
    taskId: number,
    request: UpdateManualPaymentTaskRequest
  ): Observable<ManualPaymentTaskResponse> {
    return this.http.put<ManualPaymentTaskResponse>(
      `${appEnvironment.apiBaseUrl}/api/cabinet/manual-payment-tasks/${taskId}`,
      request
    );
  }

  imageUrl(imageId?: number | null): string {
    const id = imageId || 1;
    return `${appEnvironment.backendBaseUrl}/images/${id}`;
  }

  private paramsWithDate(date?: string, options: CacheOptions = {}): HttpParams {
    let params = date ? new HttpParams().set('date', date) : new HttpParams();

    if (options.forceRefresh) {
      params = params.set('refresh', 'true');
    }

    return params;
  }

  private paramsWithAnalyticsPeriod(date?: string, options: AnalyticsOptions = {}): HttpParams {
    let params = this.paramsWithDate(date, options);

    if (options.allTime) {
      params = params.set('allTime', 'true');
    } else {
      if (options.from) {
        params = params.set('from', options.from);
      }
      if (options.to) {
        params = params.set('to', options.to);
      }
    }

    return params;
  }

  private requestContext(options: CacheOptions): HttpContext | undefined {
    if (!options.skipAuthRedirectOn401) {
      return undefined;
    }

    return new HttpContext().set(SKIP_AUTH_REDIRECT_ON_401, true);
  }

  private cached<T>(
    cache: Map<string, CacheEntry<T>>,
    key: string,
    options: CacheOptions,
    requestFactory: () => Observable<T>
  ): Observable<T> {
    const now = Date.now();
    this.removeExpiredEntries(cache, now);

    const cached = cache.get(key);

    if (!options.forceRefresh && cached && cached.expiresAt > now) {
      return cached.request$;
    }

    const request$ = requestFactory().pipe(
      catchError((error: unknown) => {
        cache.delete(key);
        return throwError(() => error);
      }),
      shareReplay({ bufferSize: 1, refCount: false })
    );

    cache.set(key, {
      expiresAt: now + this.cabinetCacheTtlMs,
      request$
    });
    this.trimCache(cache);

    return request$;
  }

  private removeExpiredEntries<T>(cache: Map<string, CacheEntry<T>>, now: number): void {
    for (const [entryKey, entry] of cache) {
      if (entry.expiresAt <= now) {
        cache.delete(entryKey);
      }
    }
  }

  private trimCache<T>(cache: Map<string, CacheEntry<T>>): void {
    while (cache.size > this.cabinetCacheMaxEntries) {
      const oldestKey = cache.keys().next().value;
      if (oldestKey === undefined) {
        return;
      }
      cache.delete(oldestKey);
    }
  }

  private cacheKey(scope: string, ...parts: string[]): string {
    const token = this.auth.tokenParsed();
    const username = token?.['preferred_username'] ?? 'anonymous';
    const realmAccess = token?.['realm_access'] as { roles?: string[] } | undefined;
    const roles = realmAccess?.roles?.join(',') ?? 'no-roles';

    return [scope, username, roles, ...parts].join(':');
  }
}
