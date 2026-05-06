import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';

export type ManagerSection = 'companies' | 'orders';

export interface ManagerPage<T> {
  content: T[];
  number: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface CompanyCardItem {
  id: number;
  title: string;
  urlChat?: string;
  telephone?: string;
  countFilials: number;
  urlFilial?: string;
  status: string;
  manager?: string;
  commentsCompany?: string;
  city?: string;
  dateNewTry?: string;
  groupId?: string;
}

export interface OrderCardItem {
  id: number;
  companyId: number;
  orderDetailsId?: string;
  companyTitle: string;
  filialTitle?: string;
  filialUrl?: string;
  status: string;
  sum?: number;
  totalSumWithBadReviews?: number;
  badReviewTasksSum?: number;
  badReviewTasksTotal?: number;
  badReviewTasksPending?: number;
  badReviewTasksDone?: number;
  badReviewTasksCanceled?: number;
  companyUrlChat?: string;
  companyTelephone?: string;
  orderComments?: string;
  companyComments?: string;
  managerPayText?: string;
  amount?: number;
  counter?: number;
  workerUserFio?: string;
  categoryTitle?: string;
  subCategoryTitle?: string;
  created?: string;
  changed?: string;
  payDay?: string;
  dayToChangeStatusAgo?: number;
  groupId?: string;
}

export interface ManagerMetric {
  label: string;
  value: number;
  icon: string;
  tone: 'blue' | 'green' | 'teal' | 'yellow' | 'pink' | 'gray';
  section: ManagerSection;
  status: string;
}

export interface ManagerBoard {
  section: ManagerSection;
  status: string;
  companies: ManagerPage<CompanyCardItem>;
  orders: ManagerPage<OrderCardItem>;
  companyStatuses: string[];
  orderStatuses: string[];
  metrics: ManagerMetric[];
  promoTexts: string[];
}

export interface ManagerOption {
  id: number;
  label: string;
}

export interface ProductOption {
  id: number;
  label: string;
  photo: boolean;
}

export interface OrderProductOption {
  id: number;
  label: string;
  price?: number;
  photo: boolean;
}

export interface CompanyFilialEditItem {
  id: number;
  title: string;
  url: string;
  cityId?: number | null;
  city: string;
}

export interface CompanyFilialUpdateRequest {
  title: string;
  url: string;
  cityId: number | null;
}

export interface CompanyEditPayload {
  id: number;
  title: string;
  urlChat: string;
  telephone: string;
  city: string;
  email: string;
  commentsCompany: string;
  active: boolean;
  createDate: string;
  updateStatus: string;
  dateNewTry: string;
  status?: ManagerOption | null;
  category?: ManagerOption | null;
  subCategory?: ManagerOption | null;
  manager?: ManagerOption | null;
  categories: ManagerOption[];
  subCategories: ManagerOption[];
  statuses: ManagerOption[];
  managers: ManagerOption[];
  workers: ManagerOption[];
  currentWorkers: ManagerOption[];
  filials: CompanyFilialEditItem[];
  cities: ManagerOption[];
  canChangeManager: boolean;
}

export interface CompanyUpdateRequest {
  title: string;
  urlChat: string;
  telephone: string;
  city: string;
  email: string;
  categoryId: number | null;
  subCategoryId: number | null;
  statusId: number | null;
  managerId: number | null;
  commentsCompany: string;
  active: boolean;
  newWorkerId: number | null;
  newFilialCityId: number | null;
  newFilialTitle: string;
  newFilialUrl: string;
}

export interface CompanyOrderCreatePayload {
  companyId: number;
  companyTitle: string;
  products: OrderProductOption[];
  amounts: number[];
  workers: ManagerOption[];
  filials: CompanyFilialEditItem[];
  defaultProductId?: number | null;
  defaultAmount?: number | null;
  defaultWorkerId?: number | null;
  defaultFilialId?: number | null;
}

export interface CompanyOrderCreateRequest {
  productId: number | null;
  amount: number;
  workerId: number | null;
  filialId: number | null;
}

export interface CompanyOrderCreateResult {
  companyId: number;
  companyTitle: string;
  productId: number;
  productTitle: string;
  amount: number;
}

export interface OrderEditPayload {
  id: number;
  companyId: number;
  companyTitle: string;
  status: string;
  sum?: number;
  amount?: number;
  counter?: number;
  created: string;
  changed: string;
  payDay: string;
  orderComments: string;
  commentsCompany: string;
  complete: boolean;
  filial?: ManagerOption | null;
  manager?: ManagerOption | null;
  worker?: ManagerOption | null;
  filials: ManagerOption[];
  managers: ManagerOption[];
  workers: ManagerOption[];
  canComplete: boolean;
  canDelete: boolean;
}

export interface OrderUpdateRequest {
  filialId: number | null;
  workerId: number | null;
  managerId: number | null;
  counter: number;
  orderComments: string;
  commentsCompany: string;
  complete: boolean;
}

export interface OrderReviewItem {
  id: number;
  companyId: number;
  orderDetailsId?: string;
  orderId: number;
  text: string;
  answer: string;
  category: string;
  subCategory: string;
  botId?: number | null;
  botFio: string;
  botLogin: string;
  botPassword: string;
  botCounter: number;
  companyTitle: string;
  commentCompany: string;
  orderComments: string;
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
}

export interface BadReviewSummary {
  total: number;
  pending: number;
  done: number;
  canceled: number;
  doneSum?: number;
  pendingSum?: number;
  totalSumWithBadReviews?: number;
}

export interface BadReviewTaskItem {
  id: number;
  sourceReviewId?: number | null;
  status: string;
  statusCode: string;
  originalRating?: number | null;
  targetRating?: number | null;
  price?: number;
  scheduledDate?: string;
  completedDate?: string;
  workerFio?: string;
  botId?: number | null;
  botFio?: string;
  comment?: string;
}

export interface OrderDetailsPayload {
  orderId: number;
  companyId?: number | null;
  orderDetailsId?: string | null;
  title: string;
  companyTitle: string;
  productTitle: string;
  status: string;
  amount?: number;
  counter?: number;
  sum?: number;
  totalSumWithBadReviews?: number;
  badReviewSummary?: BadReviewSummary;
  orderComments: string;
  companyComments: string;
  created: string;
  changed: string;
  reviews: OrderReviewItem[];
  badReviewTasks: BadReviewTaskItem[];
  products: ProductOption[];
  canEditReviews: boolean;
  canSendToCheck: boolean;
  canEditReviewDates: boolean;
  canEditReviewPublish: boolean;
  canEditReviewVigul: boolean;
  canDeleteReviews: boolean;
}

export interface ReviewUpdateRequest {
  text: string;
  answer: string;
  comment: string;
  created: string | null;
  changed: string | null;
  publishedDate: string | null;
  publish: boolean;
  vigul: boolean;
  botName: string;
  botPassword: string;
  productId: number | null;
  url: string;
}

export interface ManagerBoardQuery {
  section: ManagerSection;
  status?: string;
  keyword?: string;
  companyId?: number;
  pageNumber?: number;
  pageSize?: number;
  sortDirection?: 'desc' | 'asc';
}

@Injectable({ providedIn: 'root' })
export class ManagerApi {
  constructor(private readonly http: HttpClient) {}

  getBoard(query: ManagerBoardQuery): Observable<ManagerBoard> {
    let params = new HttpParams()
      .set('section', query.section)
      .set('status', query.status?.trim() || 'Все')
      .set('keyword', query.keyword?.trim() ?? '')
      .set('pageNumber', String(query.pageNumber ?? 0))
      .set('pageSize', String(query.pageSize ?? 10))
      .set('sortDirection', query.sortDirection ?? 'desc');

    if (query.companyId != null) {
      params = params.set('companyId', String(query.companyId));
    }

    return this.http.get<ManagerBoard>(`${appEnvironment.apiBaseUrl}/api/manager/board`, { params });
  }

  getCompanyEdit(companyId: number): Observable<CompanyEditPayload> {
    return this.http.get<CompanyEditPayload>(`${appEnvironment.apiBaseUrl}/api/manager/companies/${companyId}/edit`);
  }

  getCompanyOrderCreate(companyId: number): Observable<CompanyOrderCreatePayload> {
    return this.http.get<CompanyOrderCreatePayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/companies/${companyId}/order-create`
    );
  }

  createCompanyOrder(companyId: number, request: CompanyOrderCreateRequest): Observable<CompanyOrderCreateResult> {
    return this.http.post<CompanyOrderCreateResult>(
      `${appEnvironment.apiBaseUrl}/api/manager/companies/${companyId}/orders`,
      request
    );
  }

  updateCompany(companyId: number, request: CompanyUpdateRequest): Observable<CompanyEditPayload> {
    return this.http.put<CompanyEditPayload>(`${appEnvironment.apiBaseUrl}/api/manager/companies/${companyId}`, request);
  }

  updateCompanyNote(companyId: number, companyComments: string): Observable<void> {
    return this.http.put<void>(
      `${appEnvironment.apiBaseUrl}/api/manager/companies/${companyId}/note`,
      { companyComments }
    );
  }

  deleteCompanyWorker(companyId: number, workerId: number): Observable<CompanyEditPayload> {
    return this.http.delete<CompanyEditPayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/companies/${companyId}/workers/${workerId}`
    );
  }

  deleteCompanyFilial(companyId: number, filialId: number): Observable<CompanyEditPayload> {
    return this.http.delete<CompanyEditPayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/companies/${companyId}/filials/${filialId}`
    );
  }

  updateCompanyFilial(
    companyId: number,
    filialId: number,
    request: CompanyFilialUpdateRequest
  ): Observable<CompanyEditPayload> {
    return this.http.put<CompanyEditPayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/companies/${companyId}/filials/${filialId}`,
      request
    );
  }

  getCompanySubcategories(categoryId: number): Observable<ManagerOption[]> {
    return this.http.get<ManagerOption[]>(
      `${appEnvironment.apiBaseUrl}/api/manager/categories/${categoryId}/subcategories`
    );
  }

  updateCompanyStatus(companyId: number, status: string): Observable<void> {
    return this.http.post<void>(
      `${appEnvironment.apiBaseUrl}/api/manager/companies/${companyId}/status`,
      { status }
    );
  }

  getOrderEdit(orderId: number): Observable<OrderEditPayload> {
    return this.http.get<OrderEditPayload>(`${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/edit`);
  }

  updateOrder(orderId: number, request: OrderUpdateRequest): Observable<OrderEditPayload> {
    return this.http.put<OrderEditPayload>(`${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}`, request);
  }

  deleteOrder(orderId: number): Observable<void> {
    return this.http.delete<void>(`${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}`);
  }

  updateOrderStatus(orderId: number, status: string): Observable<void> {
    return this.http.post<void>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/status`,
      { status }
    );
  }

  getOrderDetails(orderId: number): Observable<OrderDetailsPayload> {
    return this.http.get<OrderDetailsPayload>(`${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/details`);
  }

  addOrderReview(orderId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(`${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/reviews`, {});
  }

  changeOrderReviewText(orderId: number, reviewId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/reviews/${reviewId}/change-text`,
      {}
    );
  }

  changeOrderReviewBot(orderId: number, reviewId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/reviews/${reviewId}/change-bot`,
      {}
    );
  }

  deactivateOrderReviewBot(orderId: number, reviewId: number, botId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/reviews/${reviewId}/bots/${botId}/deactivate`,
      {}
    );
  }

  publishOrderReview(orderId: number, reviewId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/reviews/${reviewId}/publish`,
      {}
    );
  }

  cancelBadReviewTask(orderId: number, taskId: number): Observable<OrderDetailsPayload> {
    return this.http.post<OrderDetailsPayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/bad-review-tasks/${taskId}/cancel`,
      {}
    );
  }

  updateOrderReview(orderId: number, reviewId: number, request: ReviewUpdateRequest): Observable<OrderDetailsPayload> {
    return this.http.put<OrderDetailsPayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/reviews/${reviewId}`,
      request
    );
  }

  uploadOrderReviewPhoto(orderId: number, reviewId: number, file: File): Observable<OrderDetailsPayload> {
    const formData = new FormData();
    formData.append('file', file);

    return this.http.post<OrderDetailsPayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/reviews/${reviewId}/photo`,
      formData
    );
  }

  deleteOrderReview(orderId: number, reviewId: number): Observable<OrderDetailsPayload> {
    return this.http.delete<OrderDetailsPayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/reviews/${reviewId}`
    );
  }

  updateOrderReviewText(orderId: number, reviewId: number, text: string): Observable<OrderDetailsPayload> {
    return this.http.put<OrderDetailsPayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/reviews/${reviewId}/text`,
      { text }
    );
  }

  updateOrderReviewAnswer(orderId: number, reviewId: number, answer: string): Observable<OrderDetailsPayload> {
    return this.http.put<OrderDetailsPayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/reviews/${reviewId}/answer`,
      { answer }
    );
  }

  updateOrderReviewNote(orderId: number, reviewId: number, comment: string): Observable<OrderDetailsPayload> {
    return this.http.put<OrderDetailsPayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/reviews/${reviewId}/note`,
      { comment }
    );
  }

  updateOrderNote(orderId: number, orderComments: string): Observable<OrderDetailsPayload> {
    return this.http.put<OrderDetailsPayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/note`,
      { orderComments }
    );
  }

  updateOrderCompanyNote(orderId: number, companyComments: string): Observable<OrderDetailsPayload> {
    return this.http.put<OrderDetailsPayload>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/company-note`,
      { companyComments }
    );
  }
}
