import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import type { BadReviewTaskUpdateRequest, CredentialRevealResponse, OrderDetailsPayload, ReviewRecoveryTaskUpdateRequest, WorkerActivitySource } from './api.service';
import { mobileEnvironment } from './mobile-environment';

/** Stateless feature transport; authentication and error handling stay in Angular interceptors. */
@Injectable({ providedIn: 'root' })
export class ManagerReviewTasksApi {
  private readonly http = inject(HttpClient);
  private apiUrl(path: string): string { return mobileEnvironment.apiBaseUrl + path; }

  revealManagerBadReviewTaskCredential(
    orderId: number,
    taskId: number,
    field: 'login' | 'password',
    source?: WorkerActivitySource
  ): Observable<CredentialRevealResponse> {
    return this.http.post<CredentialRevealResponse>(
      this.apiUrl(`/api/manager/orders/${orderId}/bad-review-tasks/${taskId}/credential-reveal`),
      { ...source, field }
    );
  }

  revealManagerRecoveryTaskCredential(
    orderId: number,
    taskId: number,
    field: 'login' | 'password',
    source?: WorkerActivitySource
  ): Observable<CredentialRevealResponse> {
    return this.http.post<CredentialRevealResponse>(
      this.apiUrl(`/api/manager/orders/${orderId}/recovery-tasks/${taskId}/credential-reveal`),
      { ...source, field }
    );
  }

  cancelManagerBadReviewTask(orderId: number, taskId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(
      this.apiUrl(`/api/manager/orders/${orderId}/bad-review-tasks/${taskId}/cancel`),
      {}
    );
  }

  completeManagerBadReviewTask(orderId: number, taskId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(
      this.apiUrl(`/api/manager/orders/${orderId}/bad-review-tasks/${taskId}/complete`),
      {}
    );
  }

  updateManagerBadReviewTask(
    orderId: number,
    taskId: number,
    request: BadReviewTaskUpdateRequest
  ): Observable<OrderDetailsPayload> {
    return this.http.put<OrderDetailsPayload>(
      this.apiUrl(`/api/manager/orders/${orderId}/bad-review-tasks/${taskId}`),
      request
    );
  }

  changeManagerBadReviewTaskBot(orderId: number, taskId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(
      this.apiUrl(`/api/manager/orders/${orderId}/bad-review-tasks/${taskId}/change-bot`),
      {}
    );
  }

  createManagerReviewRecoveryTask(orderId: number, reviewId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(
      this.apiUrl(`/api/manager/orders/${orderId}/reviews/${reviewId}/recovery-tasks`),
      {}
    );
  }

  updateManagerReviewRecoveryTask(
    orderId: number,
    taskId: number,
    request: ReviewRecoveryTaskUpdateRequest
  ): Observable<OrderDetailsPayload> {
    return this.http.put<OrderDetailsPayload>(
      this.apiUrl(`/api/manager/orders/${orderId}/recovery-tasks/${taskId}`),
      request
    );
  }

  completeManagerReviewRecoveryTask(orderId: number, taskId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(
      this.apiUrl(`/api/manager/orders/${orderId}/recovery-tasks/${taskId}/complete`),
      {}
    );
  }

  deleteManagerReviewRecoveryTask(orderId: number, taskId: number): Observable<OrderDetailsPayload> {
    return this.http.delete<OrderDetailsPayload>(
      this.apiUrl(`/api/manager/orders/${orderId}/recovery-tasks/${taskId}`)
    );
  }

  markManagerRecoveryClientNotified(orderId: number, batchId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(
      this.apiUrl(`/api/manager/orders/${orderId}/recovery-batches/${batchId}/client-notified`),
      {}
    );
  }
}
