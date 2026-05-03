import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';

export type CityStatsSort = 'name' | 'countAll' | 'countArchive' | 'bots' | 'balance';
export type SortDirection = 'asc' | 'desc';

export interface CityStatsItem {
  cityId: number;
  cityTitle: string;
  unpublishedCount: number;
  unpublishedNotArchiveCount: number;
  activeBotsCount: number;
  botBalance: number;
  botStatus: string;
  botPercentage: number;
  botStatusCssClass: string;
  countTone: 'low' | 'medium' | 'high';
  balanceTone: 'positive' | 'negative' | 'neutral';
  percentageTone: 'green' | 'yellow' | 'blue' | 'neutral';
}

export interface CityStatsTotals {
  totalCities: number;
  totalUnpublished: number;
  totalUnpublishedNotArchive: number;
  totalActiveBots: number;
  totalBotBalance: number;
  averagePerCity: number;
  averageNotArchivePerCity: number;
  averageBotsPerCity: number;
  averageBalancePerCity: number;
  criticalCount: number;
  excessCount: number;
  archivedCount: number;
}

export interface CityStatsPage {
  pageNumber: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface CityStatsBoard {
  cities: CityStatsItem[];
  statistics: CityStatsTotals;
  page: CityStatsPage;
  search: string;
  sort?: CityStatsSort | null;
  direction: SortDirection;
  generatedAt: string;
}

export interface CityStatsQuery {
  search?: string;
  sort?: CityStatsSort | null;
  direction?: SortDirection | null;
  page?: number;
  size?: number;
}

@Injectable({ providedIn: 'root' })
export class AdminCityStatsApi {
  private readonly baseUrl = `${appEnvironment.apiBaseUrl}/api/admin/cities`;

  constructor(private readonly http: HttpClient) {}

  getBoard(query: CityStatsQuery): Observable<CityStatsBoard> {
    return this.http.get<CityStatsBoard>(`${this.baseUrl}/board`, {
      params: this.params(query)
    });
  }

  exportAll(query: CityStatsQuery): Observable<Blob> {
    return this.http.get(`${this.baseUrl}/export-all`, {
      params: this.params(query),
      responseType: 'blob'
    });
  }

  private params(query: CityStatsQuery): HttpParams {
    let params = new HttpParams()
      .set('page', String(query.page ?? 0))
      .set('size', String(query.size ?? 20));

    const search = query.search?.trim();
    if (search) {
      params = params.set('search', search);
    }

    if (query.sort) {
      params = params.set('sort', query.sort);
    }

    if (query.direction) {
      params = params.set('direction', query.direction);
    }

    return params;
  }
}
