import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import type { CredentialRevealResponse, OrderDetailsPayload, OrderReviewItem, WorkerActivitySource } from './api.service';
import { mobileEnvironment } from './mobile-environment';

/** Stateless feature transport; authentication and error handling stay in Angular interceptors. */
@Injectable({ providedIn: 'root' })
export class ManagerReviewActionsApi {
  private readonly http = inject(HttpClient);
  private apiUrl(path: string): string { return mobileEnvironment.apiBaseUrl + path; }

  addManagerOrderReview(orderId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(this.apiUrl(`/api/manager/orders/${orderId}/reviews`), {});
  }

  publishManagerOrderReview(orderId: number, reviewId: number, source?: WorkerActivitySource): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(this.apiUrl(`/api/manager/orders/${orderId}/reviews/${reviewId}/publish`), source ?? {});
  }

  changeManagerOrderReviewText(orderId: number, reviewId: number): Observable<OrderReviewItem> {
    return this.http.post<OrderReviewItem>(this.apiUrl(`/api/manager/orders/${orderId}/reviews/${reviewId}/change-text`), {});
  }

  assignManagerOrderReviewNewAccount(orderId: number, reviewId: number, source?: WorkerActivitySource): Observable<OrderReviewItem> {
    return this.http.post<OrderReviewItem>(this.apiUrl(`/api/manager/orders/${orderId}/reviews/${reviewId}/new-account`), source ?? {});
  }

  changeManagerOrderReviewBot(orderId: number, reviewId: number, source?: WorkerActivitySource): Observable<OrderReviewItem> {
    return this.http.post<OrderReviewItem>(this.apiUrl(`/api/manager/orders/${orderId}/reviews/${reviewId}/change-bot`), source ?? {});
  }

  deactivateManagerOrderReviewBot(orderId: number, reviewId: number, botId: number, source?: WorkerActivitySource): Observable<OrderReviewItem> {
    return this.http.post<OrderReviewItem>(
      this.apiUrl(`/api/manager/orders/${orderId}/reviews/${reviewId}/bots/${botId}/deactivate`),
      source ?? {}
    );
  }

  revealManagerOrderReviewCredential(
    orderId: number,
    reviewId: number,
    field: 'login' | 'password',
    source?: WorkerActivitySource
  ): Observable<CredentialRevealResponse> {
    return this.http.post<CredentialRevealResponse>(
      this.apiUrl(`/api/manager/orders/${orderId}/reviews/${reviewId}/credential-reveal`),
      { ...source, field }
    );
  }

  createManagerReviewHelpDrafts(orderId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(this.apiUrl(`/api/manager/orders/${orderId}/reviews/help-drafts`), {});
  }

  createManagerReviewHelpDraftForCard(orderId: number, reviewId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(
      this.apiUrl(`/api/manager/orders/${orderId}/reviews/${reviewId}/help-drafts`),
      {}
    );
  }
}
