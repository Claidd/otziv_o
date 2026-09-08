import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';
import type {
  AdminPromoText,
  PromoTextManagementResponse,
  PromoTextRequest,
  PromoTextAssignmentRequest,
  PromoTextAssignment,
  AdminManagerText,
  ManagerTextRequest,
} from './admin-dictionaries.api';

@Injectable({ providedIn: 'root' })
export class AdminCommunicationTextsApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${appEnvironment.apiBaseUrl}/api/admin`;

  getPromoTexts(keyword = ''): Observable<AdminPromoText[]> {
    return this.http.get<AdminPromoText[]>(`${this.baseUrl}/promo-texts`, {
      params: this.keywordParams(keyword),
    });
  }

  getPromoTextManagement(keyword = ''): Observable<PromoTextManagementResponse> {
    return this.http.get<PromoTextManagementResponse>(`${this.baseUrl}/promo-texts/management`, {
      params: this.keywordParams(keyword),
    });
  }

  createPromoText(request: PromoTextRequest): Observable<AdminPromoText> {
    return this.http.post<AdminPromoText>(`${this.baseUrl}/promo-texts`, request);
  }

  updatePromoText(id: number, request: PromoTextRequest): Observable<AdminPromoText> {
    return this.http.put<AdminPromoText>(`${this.baseUrl}/promo-texts/${id}`, request);
  }

  savePromoTextAssignment(request: PromoTextAssignmentRequest): Observable<PromoTextAssignment> {
    return this.http.put<PromoTextAssignment>(`${this.baseUrl}/promo-text-assignments`, request);
  }

  resetPromoTextAssignment(
    managerId: number,
    section: string,
    buttonKey: string,
  ): Observable<void> {
    return this.http.delete<void>(
      `${this.baseUrl}/promo-text-assignments/${managerId}/${section}/${buttonKey}`,
    );
  }

  getManagerTexts(keyword = ''): Observable<AdminManagerText[]> {
    return this.http.get<AdminManagerText[]>(`${this.baseUrl}/manager-texts`, {
      params: this.keywordParams(keyword),
    });
  }

  updateManagerText(managerId: number, request: ManagerTextRequest): Observable<AdminManagerText> {
    return this.http.put<AdminManagerText>(`${this.baseUrl}/manager-texts/${managerId}`, request);
  }

  private keywordParams(keyword: string): HttpParams {
    const value = keyword.trim();
    return value ? new HttpParams().set('keyword', value) : new HttpParams();
  }
}
