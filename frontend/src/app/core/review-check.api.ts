import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { SKIP_AUTH_REDIRECT_ON_401, SKIP_AUTH_TOKEN } from './auth-http-context';
import { appEnvironment } from './app-environment';

export interface ReviewCheckPermissions {
  authenticated: boolean;
  canSeeInternalInfo: boolean;
  canSeeBot: boolean;
  canApprovePublication: boolean;
  canSave: boolean;
  canSendCorrection: boolean;
  canSendToCheck: boolean;
  canMarkPaid: boolean;
  canOpenManagerLinks: boolean;
  canEditNotes: boolean;
}

export interface ReviewCheckReview {
  id: number;
  text: string;
  answer: string;
  botName: string;
  comment: string;
  orderComments: string;
  commentCompany: string;
  productTitle: string;
  productPhoto: boolean;
  url: string;
  publishedDate: string;
  publish: boolean;
}

export interface ReviewCheckPayload {
  orderDetailId: string;
  orderId?: number | null;
  companyId?: number | null;
  companyTitle: string;
  filialTitle: string;
  status: string;
  workerFio: string;
  orderComments: string;
  companyComments: string;
  comment: string;
  amount: number;
  counter: number;
  sum?: number;
  approved: boolean;
  reviews: ReviewCheckReview[];
  permissions: ReviewCheckPermissions;
}

export interface ReviewCheckReviewUpdate {
  id: number;
  text: string;
  answer: string;
  publish: boolean;
  publishedDate: string | null;
  url: string;
}

export interface ReviewCheckUpdateRequest {
  comment: string;
  reviews: ReviewCheckReviewUpdate[];
}

export interface ReviewCheckNotes {
  orderComments: string;
  companyComments: string;
}

@Injectable({ providedIn: 'root' })
export class ReviewCheckApi {
  private readonly publicContext = new HttpContext()
    .set(SKIP_AUTH_REDIRECT_ON_401, true)
    .set(SKIP_AUTH_TOKEN, true);

  constructor(private readonly http: HttpClient) {}

  getReviewCheck(orderDetailId: string): Observable<ReviewCheckPayload> {
    return this.http.get<ReviewCheckPayload>(
      `${appEnvironment.apiBaseUrl}/api/review-check/${orderDetailId}`,
      { context: this.publicContext }
    );
  }

  saveReviews(orderDetailId: string, request: ReviewCheckUpdateRequest): Observable<ReviewCheckPayload> {
    return this.http.put<ReviewCheckPayload>(
      `${appEnvironment.apiBaseUrl}/api/review-check/${orderDetailId}`,
      request,
      { context: this.publicContext }
    );
  }

  updateReviewText(orderDetailId: string, reviewId: number, text: string): Observable<ReviewCheckReview> {
    return this.http.put<ReviewCheckReview>(
      `${appEnvironment.apiBaseUrl}/api/review-check/${orderDetailId}/reviews/${reviewId}/text`,
      { text },
      { context: this.publicContext }
    );
  }

  updateReviewAnswer(orderDetailId: string, reviewId: number, answer: string): Observable<ReviewCheckReview> {
    return this.http.put<ReviewCheckReview>(
      `${appEnvironment.apiBaseUrl}/api/review-check/${orderDetailId}/reviews/${reviewId}/answer`,
      { answer },
      { context: this.publicContext }
    );
  }

  approveReviews(orderDetailId: string, request: ReviewCheckUpdateRequest): Observable<ReviewCheckPayload> {
    return this.http.post<ReviewCheckPayload>(
      `${appEnvironment.apiBaseUrl}/api/review-check/${orderDetailId}/approve`,
      request,
      { context: this.publicContext }
    );
  }

  sendToCorrection(orderDetailId: string, request: ReviewCheckUpdateRequest): Observable<ReviewCheckPayload> {
    return this.http.post<ReviewCheckPayload>(
      `${appEnvironment.apiBaseUrl}/api/review-check/${orderDetailId}/correction`,
      request,
      { context: this.publicContext }
    );
  }

  sendToCheck(orderDetailId: string, request: ReviewCheckUpdateRequest): Observable<ReviewCheckPayload> {
    return this.http.post<ReviewCheckPayload>(
      `${appEnvironment.apiBaseUrl}/api/review-check/${orderDetailId}/send-to-check`,
      request,
      { context: this.publicContext }
    );
  }

  markPaid(orderDetailId: string): Observable<ReviewCheckPayload> {
    return this.http.post<ReviewCheckPayload>(
      `${appEnvironment.apiBaseUrl}/api/review-check/${orderDetailId}/pay-ok`,
      {},
      { context: this.publicContext }
    );
  }

  updateReviewNote(orderDetailId: string, reviewId: number, comment: string): Observable<ReviewCheckReview> {
    return this.http.put<ReviewCheckReview>(
      `${appEnvironment.apiBaseUrl}/api/review-check/${orderDetailId}/reviews/${reviewId}/note`,
      { comment },
      { context: this.publicContext }
    );
  }

  updateOrderNote(orderDetailId: string, orderComments: string): Observable<ReviewCheckNotes> {
    return this.http.put<ReviewCheckNotes>(
      `${appEnvironment.apiBaseUrl}/api/review-check/${orderDetailId}/order-note`,
      { orderComments },
      { context: this.publicContext }
    );
  }

  updateCompanyNote(orderDetailId: string, companyComments: string): Observable<ReviewCheckNotes> {
    return this.http.put<ReviewCheckNotes>(
      `${appEnvironment.apiBaseUrl}/api/review-check/${orderDetailId}/company-note`,
      { companyComments },
      { context: this.publicContext }
    );
  }
}
