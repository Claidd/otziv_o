import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import type { Observable } from 'rxjs';
import { mobileEnvironment } from './mobile-environment';

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
  countTone: 'low' | 'medium' | 'high';
  balanceTone: 'positive' | 'negative' | 'neutral';
}

export interface CityStatsBoard {
  cities: CityStatsItem[];
  statistics: {
    totalCities: number;
    totalUnpublished: number;
    totalUnpublishedNotArchive: number;
    totalActiveBots: number;
    totalBotBalance: number;
    averageNotArchivePerCity: number;
    archivedCount: number;
  };
  page: {
    pageNumber: number;
    pageSize: number;
    totalElements: number;
    totalPages: number;
    first: boolean;
    last: boolean;
  };
  direction: SortDirection;
  generatedAt: string;
}

export interface CityStatsQuery {
  search?: string;
  sort?: CityStatsSort | null;
  direction?: SortDirection;
  page: number;
  size: number;
}

@Injectable({ providedIn: 'root' })
export class AdminCityStatsApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${mobileEnvironment.apiBaseUrl}/api/admin/cities`;

  getBoard(query: CityStatsQuery): Observable<CityStatsBoard> {
    return this.http.get<CityStatsBoard>(`${this.baseUrl}/board`, { params: this.params(query) });
  }

  exportAll(query: CityStatsQuery): Observable<Blob> {
    return this.http.get(`${this.baseUrl}/export-all`, { params: this.params(query), responseType: 'blob' });
  }

  private params(query: CityStatsQuery): HttpParams {
    let params = new HttpParams().set('page', query.page).set('size', query.size);
    if (query.search?.trim()) params = params.set('search', query.search.trim());
    if (query.sort) params = params.set('sort', query.sort);
    if (query.direction) params = params.set('direction', query.direction);
    return params;
  }
}
