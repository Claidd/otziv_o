import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';
import { ManagerOverdueOrders, OrderCardItem } from './manager.api';

export type WorkerSection = 'new' | 'correct' | 'nagul' | 'recovery' | 'publish' | 'bad' | 'all';
export type WorkerBoardSectionQuery = WorkerSection | 'current';

export interface WorkerPage<T> {
  content: T[];
  number: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface WorkerBotItem {
  id: number;
  login: string;
  password: string;
  fio: string;
  city: string;
  counter: number;
  workerFio: string;
  status: string;
  active: boolean;
}

export interface WorkerReviewItem {
  id: number;
  companyId: number;
  orderDetailsId?: string;
  orderId: number;
  orderStatus?: string;
  text: string;
  answer: string;
  category: string;
  subCategory: string;
  botId?: number | null;
  botFio: string;
  botLogin: string;
  botPassword: string;
  botCounter: number;
  botActive?: boolean;
  companyTitle: string;
  commentCompany: string;
  orderComments: string;
  filialId?: number | null;
  filialCity: string;
  filialTitle: string;
  filialUrl: string;
  productId?: number | null;
  productTitle: string;
  productPhoto: boolean;
  workerFio: string;
  created: string;
  changed: string;
  publishedDate: string;
  publish: boolean;
  vigul: boolean;
  comment: string;
  price?: number;
  url: string;
  urlPhoto: string;
  badTask?: boolean;
  badTaskId?: number | null;
  sourceReviewId?: number | null;
  originalRating?: number | null;
  targetRating?: number | null;
  badTaskStatus?: string;
  badTaskPrice?: number;
  badTaskScheduledDate?: string;
  badTaskCompletedDate?: string;
  badTaskComment?: string;
  recoveryTask?: boolean;
  recoveryTaskId?: number | null;
  recoveryTaskStatus?: string;
  recoveryTaskScheduledDate?: string;
  recoveryTaskCompletedDate?: string;
}

export interface WorkerMetric {
  label: string;
  value: number;
  delta?: number;
  icon: string;
  tone: 'blue' | 'green' | 'teal' | 'yellow' | 'pink' | 'gray';
  section: WorkerSection;
}

export interface WorkerOption {
  id: number;
  label: string;
}

export interface WorkerPermissions {
  canManageOrderStatuses: boolean;
  canManageClientWaiting: boolean;
  canSeePhoneAndPayment: boolean;
  canManageBots: boolean;
  canAddBot: boolean;
  canSeeMoney: boolean;
  canWorkReviews: boolean;
  canEditNotes: boolean;
}

export interface WorkerBoard {
  section: WorkerSection;
  title: string;
  orders: WorkerPage<OrderCardItem>;
  reviews: WorkerPage<WorkerReviewItem>;
  bots: WorkerBotItem[];
  metrics: WorkerMetric[];
  promoTexts: string[];
  permissions: WorkerPermissions;
  workerOptions?: WorkerOption[];
  selectedWorkerId?: number | null;
  workerFilterAvailable?: boolean;
  message: string;
  warning: boolean;
}

export interface WorkerActionResponse {
  success: boolean;
  message: string;
}

export interface BotChangeResponse {
  oldBotId?: number | null;
  newBotId?: number | null;
}

export interface WorkerActivitySource {
  sourcePage?: string;
  sourceEntry?: string;
  sourceSection?: string;
}

export interface WorkerBoardQuery {
  section: WorkerBoardSectionQuery;
  keyword?: string;
  pageNumber?: number;
  pageSize?: number;
  sortDirection?: 'desc' | 'asc';
  workerId?: number | null;
}

@Injectable({ providedIn: 'root' })
export class WorkerApi {
  constructor(private readonly http: HttpClient) {}

  getBoard(query: WorkerBoardQuery): Observable<WorkerBoard> {
    const params = new HttpParams()
      .set('section', query.section)
      .set('keyword', query.keyword?.trim() ?? '')
      .set('pageNumber', String(query.pageNumber ?? 0))
      .set('pageSize', String(query.pageSize ?? 10))
      .set('sortDirection', query.sortDirection ?? 'desc');

    const requestParams = query.workerId ? params.set('workerId', String(query.workerId)) : params;

    return this.http.get<WorkerBoard>(`${appEnvironment.apiBaseUrl}/api/worker/board`, { params: requestParams });
  }

  getOverdueOrders(): Observable<ManagerOverdueOrders> {
    return this.http.get<ManagerOverdueOrders>(`${appEnvironment.apiBaseUrl}/api/worker/overdue-orders`);
  }

  updateOrderStatus(orderId: number, status: string): Observable<void> {
    return this.http.post<void>(`${appEnvironment.apiBaseUrl}/api/worker/orders/${orderId}/status`, { status });
  }

  updateOrderClientWaiting(orderId: number, waitingForClient: boolean): Observable<void> {
    return this.http.post<void>(
      `${appEnvironment.apiBaseUrl}/api/worker/orders/${orderId}/client-waiting`,
      { waitingForClient }
    );
  }

  updateOrderNote(orderId: number, orderComments: string): Observable<void> {
    return this.http.put<void>(`${appEnvironment.apiBaseUrl}/api/worker/orders/${orderId}/note`, { orderComments });
  }

  updateOrderCompanyNote(orderId: number, companyComments: string): Observable<void> {
    return this.http.put<void>(`${appEnvironment.apiBaseUrl}/api/worker/orders/${orderId}/company-note`, { companyComments });
  }

  changeReviewBot(reviewId: number, source?: WorkerActivitySource): Observable<BotChangeResponse> {
    return this.http.post<BotChangeResponse>(`${appEnvironment.apiBaseUrl}/api/worker/reviews/${reviewId}/change-bot`, source ?? {});
  }

  deactivateReviewBot(reviewId: number, botId: number, source?: WorkerActivitySource): Observable<void> {
    return this.http.post<void>(`${appEnvironment.apiBaseUrl}/api/worker/reviews/${reviewId}/bots/${botId}/deactivate`, source ?? {});
  }

  publishReview(reviewId: number): Observable<void> {
    return this.http.post<void>(`${appEnvironment.apiBaseUrl}/api/worker/reviews/${reviewId}/publish`, {});
  }

  completeBadReviewTask(taskId: number): Observable<void> {
    return this.http.post<void>(`${appEnvironment.apiBaseUrl}/api/worker/bad-review-tasks/${taskId}/complete`, {});
  }

  updateBadReviewTask(taskId: number, taskText: string, scheduledDate?: string | null): Observable<void> {
    return this.http.put<void>(
      `${appEnvironment.apiBaseUrl}/api/worker/bad-review-tasks/${taskId}`,
      { taskText, scheduledDate: scheduledDate || null }
    );
  }

  updateRecoveryTask(taskId: number, recoveryText: string, scheduledDate?: string | null): Observable<void> {
    return this.http.put<void>(
      `${appEnvironment.apiBaseUrl}/api/worker/recovery-tasks/${taskId}`,
      { recoveryText, scheduledDate: scheduledDate || null }
    );
  }

  completeRecoveryTask(taskId: number): Observable<void> {
    return this.http.post<void>(`${appEnvironment.apiBaseUrl}/api/worker/recovery-tasks/${taskId}/complete`, {});
  }

  changeRecoveryTaskBot(taskId: number): Observable<BotChangeResponse> {
    return this.http.post<BotChangeResponse>(`${appEnvironment.apiBaseUrl}/api/worker/recovery-tasks/${taskId}/change-bot`, {});
  }

  deactivateRecoveryTaskBot(taskId: number, botId: number): Observable<void> {
    return this.http.post<void>(`${appEnvironment.apiBaseUrl}/api/worker/recovery-tasks/${taskId}/bots/${botId}/deactivate`, {});
  }

  changeBadReviewTaskBot(taskId: number): Observable<BotChangeResponse> {
    return this.http.post<BotChangeResponse>(`${appEnvironment.apiBaseUrl}/api/worker/bad-review-tasks/${taskId}/change-bot`, {});
  }

  deactivateBadReviewTaskBot(taskId: number, botId: number): Observable<void> {
    return this.http.post<void>(`${appEnvironment.apiBaseUrl}/api/worker/bad-review-tasks/${taskId}/bots/${botId}/deactivate`, {});
  }

  nagulReview(reviewId: number): Observable<WorkerActionResponse> {
    return this.http.post<WorkerActionResponse>(`${appEnvironment.apiBaseUrl}/api/worker/reviews/${reviewId}/nagul`, {});
  }

  logReviewCopyClick(
    reviewId: number,
    field: 'login' | 'password',
    source?: WorkerActivitySource
  ): Observable<void> {
    return this.http.post<void>(`${appEnvironment.apiBaseUrl}/api/worker/reviews/${reviewId}/copy-click`, {
      field,
      ...source
    });
  }

  deleteBot(botId: number): Observable<void> {
    return this.http.delete<void>(`${appEnvironment.apiBaseUrl}/api/worker/bots/${botId}`);
  }

  updateReviewText(reviewId: number, orderId: number, text: string): Observable<void> {
    return this.http.put<void>(`${appEnvironment.apiBaseUrl}/api/worker/reviews/${reviewId}/text`, { orderId, text });
  }

  updateReviewAnswer(reviewId: number, orderId: number, answer: string): Observable<void> {
    return this.http.put<void>(`${appEnvironment.apiBaseUrl}/api/worker/reviews/${reviewId}/answer`, { orderId, answer });
  }

  updateReviewNote(reviewId: number, orderId: number, comment: string): Observable<void> {
    return this.http.put<void>(`${appEnvironment.apiBaseUrl}/api/worker/reviews/${reviewId}/note`, { orderId, comment });
  }
}
