import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, forkJoin, map } from 'rxjs';
import type { BotChangeResponse, CredentialRevealResponse, ManagerOverdueOrders, WorkerActionResponse, WorkerActivitySource, WorkerBoard, WorkerBoardQuery } from './api.service';
import { mobileEnvironment } from './mobile-environment';

@Injectable({ providedIn: 'root' })
export class WorkerApi {
  private readonly http = inject(HttpClient);
  private apiUrl(path: string): string { return mobileEnvironment.apiBaseUrl + path; }
  updateManagerOrderClientWaiting(orderId: number, waitingForClient: boolean): Observable<void> {
    return this.http.post<void>(this.apiUrl(`/api/worker/orders/${orderId}/client-waiting`), { waitingForClient });
  }

  getWorkerBoard(query: WorkerBoardQuery = {}): Observable<WorkerBoard> {
    let params = new HttpParams()
      .set('section', query.section ?? 'all')
      .set('keyword', query.keyword?.trim() ?? '')
      .set('pageNumber', String(query.pageNumber ?? 0))
      .set('pageSize', String(query.pageSize ?? 10))
      .set('sortDirection', query.sortDirection ?? 'desc');

    if (query.workerId) {
      params = params.set('workerId', String(query.workerId));
    }

    return this.http.get<WorkerBoard>(this.apiUrl('/api/worker/board'), { params });
  }

  getWorkerOverdueOrders(): Observable<ManagerOverdueOrders> {
    return this.http.get<ManagerOverdueOrders>(this.apiUrl('/api/worker/overdue-orders'));
  }

  updateWorkerOrderStatus(orderId: number, status: string): Observable<void> {
    return this.http.post<void>(this.apiUrl(`/api/worker/orders/${orderId}/status`), { status });
  }

  updateWorkerOrderClientWaiting(orderId: number, waitingForClient: boolean): Observable<void> {
    return this.http.post<void>(this.apiUrl(`/api/worker/orders/${orderId}/client-waiting`), { waitingForClient });
  }

  updateWorkerOrderNote(orderId: number, orderComments: string): Observable<void> {
    return this.http.put<void>(this.apiUrl(`/api/worker/orders/${orderId}/note`), { orderComments });
  }

  updateWorkerOrderCompanyNote(orderId: number, companyComments: string): Observable<void> {
    return this.http.put<void>(this.apiUrl(`/api/worker/orders/${orderId}/company-note`), { companyComments });
  }

  changeWorkerReviewBot(reviewId: number, source?: WorkerActivitySource): Observable<BotChangeResponse> {
    return this.http.post<BotChangeResponse>(this.apiUrl(`/api/worker/reviews/${reviewId}/change-bot`), source ?? {});
  }

  deactivateWorkerReviewBot(reviewId: number, botId: number, source?: WorkerActivitySource): Observable<void> {
    return this.http.post<void>(this.apiUrl(`/api/worker/reviews/${reviewId}/bots/${botId}/deactivate`), source ?? {});
  }

  publishWorkerReview(reviewId: number): Observable<void> {
    return this.http.post<void>(this.apiUrl(`/api/worker/reviews/${reviewId}/publish`), {});
  }

  nagulWorkerReview(reviewId: number): Observable<WorkerActionResponse> {
    return this.http.post<WorkerActionResponse>(this.apiUrl(`/api/worker/reviews/${reviewId}/nagul`), {});
  }

  completeWorkerBadReviewTask(taskId: number): Observable<void> {
    return this.http.post<void>(this.apiUrl(`/api/worker/bad-review-tasks/${taskId}/complete`), {});
  }

  updateWorkerBadReviewTask(taskId: number, taskText: string, scheduledDate?: string | null): Observable<void> {
    return this.http.put<void>(this.apiUrl(`/api/worker/bad-review-tasks/${taskId}`), {
      taskText,
      scheduledDate: scheduledDate || null
    });
  }

  changeWorkerBadReviewTaskBot(taskId: number): Observable<BotChangeResponse> {
    return this.http.post<BotChangeResponse>(this.apiUrl(`/api/worker/bad-review-tasks/${taskId}/change-bot`), {});
  }

  deactivateWorkerBadReviewTaskBot(taskId: number, botId: number): Observable<void> {
    return this.http.post<void>(this.apiUrl(`/api/worker/bad-review-tasks/${taskId}/bots/${botId}/deactivate`), {});
  }

  updateWorkerRecoveryTask(
    taskId: number,
    recoveryText: string,
    scheduledDate?: string | null,
    recoveryAnswer?: string | null
  ): Observable<void> {
    return this.http.put<void>(this.apiUrl(`/api/worker/recovery-tasks/${taskId}`), {
      recoveryText,
      recoveryAnswer,
      scheduledDate: scheduledDate || null
    });
  }

  completeWorkerRecoveryTask(taskId: number): Observable<void> {
    return this.http.post<void>(this.apiUrl(`/api/worker/recovery-tasks/${taskId}/complete`), {});
  }

  changeWorkerRecoveryTaskBot(taskId: number): Observable<BotChangeResponse> {
    return this.http.post<BotChangeResponse>(this.apiUrl(`/api/worker/recovery-tasks/${taskId}/change-bot`), {});
  }

  deactivateWorkerRecoveryTaskBot(taskId: number, botId: number): Observable<void> {
    return this.http.post<void>(this.apiUrl(`/api/worker/recovery-tasks/${taskId}/bots/${botId}/deactivate`), {});
  }

  revealWorkerReviewCredential(
    reviewId: number,
    field: 'login' | 'password',
    source?: WorkerActivitySource
  ): Observable<CredentialRevealResponse> {
    return this.http.post<CredentialRevealResponse>(
      this.apiUrl(`/api/worker/reviews/${reviewId}/credential-reveal`),
      { ...source, field }
    );
  }

  revealWorkerBadReviewTaskCredential(
    taskId: number,
    field: 'login' | 'password',
    source?: WorkerActivitySource
  ): Observable<CredentialRevealResponse> {
    return this.http.post<CredentialRevealResponse>(
      this.apiUrl(`/api/worker/bad-review-tasks/${taskId}/credential-reveal`),
      { ...source, field }
    );
  }

  revealWorkerRecoveryTaskCredential(
    taskId: number,
    field: 'login' | 'password',
    source?: WorkerActivitySource
  ): Observable<CredentialRevealResponse> {
    return this.http.post<CredentialRevealResponse>(
      this.apiUrl(`/api/worker/recovery-tasks/${taskId}/credential-reveal`),
      { ...source, field }
    );
  }

  deleteWorkerBot(botId: number): Observable<void> {
    return this.http.delete<void>(this.apiUrl(`/api/worker/bots/${botId}`));
  }

  updateWorkerReviewText(reviewId: number, orderId: number, text: string, source?: WorkerActivitySource): Observable<void> {
    return this.http.put<void>(this.apiUrl(`/api/worker/reviews/${reviewId}/text`), { orderId, text, ...source });
  }

  updateWorkerReviewBotName(reviewId: number, botName: string): Observable<void> {
    return this.http.put<void>(this.apiUrl(`/api/worker/reviews/${reviewId}/bot-name`), { botName });
  }

  updateWorkerReviewAnswer(reviewId: number, orderId: number, answer: string, source?: WorkerActivitySource): Observable<void> {
    return this.http.put<void>(this.apiUrl(`/api/worker/reviews/${reviewId}/answer`), { orderId, answer, ...source });
  }

  updateWorkerReviewNote(reviewId: number, orderId: number, comment: string, source?: WorkerActivitySource): Observable<void> {
    return this.http.put<void>(this.apiUrl(`/api/worker/reviews/${reviewId}/note`), { orderId, comment, ...source });
  }

}
