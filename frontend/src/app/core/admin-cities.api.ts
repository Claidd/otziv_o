import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';
import type { AdminCity, CityCoordinateImportResponse, CityDistanceRebuildResponse, CityRequest } from './admin-dictionaries.api';

/** City dictionary and distance maintenance transport, independent of screen state. */
@Injectable({ providedIn: 'root' })
export class AdminCitiesApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${appEnvironment.apiBaseUrl}/api/admin/cities`;

  getCities(keyword = ''): Observable<AdminCity[]> {
    let params = new HttpParams();
    if (keyword.trim()) params = params.set('keyword', keyword.trim());
    return this.http.get<AdminCity[]>(this.baseUrl, { params });
  }

  createCity(request: CityRequest): Observable<AdminCity> {
    return this.http.post<AdminCity>(this.baseUrl, request);
  }

  updateCity(id: number, request: CityRequest): Observable<AdminCity> {
    return this.http.put<AdminCity>(`${this.baseUrl}/${id}`, request);
  }

  deleteCity(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  rebuildCityDistances(minCityId = 150): Observable<CityDistanceRebuildResponse> {
    return this.http.post<CityDistanceRebuildResponse>(`${this.baseUrl}/distances/rebuild`, {}, {
      params: new HttpParams().set('minCityId', String(minCityId))
    });
  }

  rebuildCityDistancesForCity(id: number): Observable<CityDistanceRebuildResponse> {
    return this.http.post<CityDistanceRebuildResponse>(`${this.baseUrl}/${id}/distances/rebuild`, {});
  }

  importCityCoordinates(file: File): Observable<CityCoordinateImportResponse> {
    const formData = new FormData();
    formData.append('file', file);
    return this.http.post<CityCoordinateImportResponse>(`${this.baseUrl}/coordinates/import`, formData);
  }
}
