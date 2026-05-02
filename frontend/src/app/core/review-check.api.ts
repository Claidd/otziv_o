import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
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
  orderId: number;
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

@Injectable({ providedIn: 'root' })
export class ReviewCheckApi {
  constructor(private readonly http: HttpClient) {}

  getReviewCheck(orderDetailId: string): Observable<ReviewCheckPayload> {
    return this.http.get<ReviewCheckPayload>(`${appEnvironment.apiBaseUrl}/api/review-check/${orderDetailId}`);
  }

  saveReviews(orderDetailId: string, request: ReviewCheckUpdateRequest): Observable<ReviewCheckPayload> {
    return this.http.put<ReviewCheckPayload>(`${appEnvironment.apiBaseUrl}/api/review-check/${orderDetailId}`, request);
  }

  approveReviews(orderDetailId: string, request: ReviewCheckUpdateRequest): Observable<ReviewCheckPayload> {
    return this.http.post<ReviewCheckPayload>(
      `${appEnvironment.apiBaseUrl}/api/review-check/${orderDetailId}/approve`,
      request
    );
  }

  sendToCorrection(orderDetailId: string, request: ReviewCheckUpdateRequest): Observable<ReviewCheckPayload> {
    return this.http.post<ReviewCheckPayload>(
      `${appEnvironment.apiBaseUrl}/api/review-check/${orderDetailId}/correction`,
      request
    );
  }

  sendToCheck(orderDetailId: string): Observable<ReviewCheckPayload> {
    return this.http.post<ReviewCheckPayload>(
      `${appEnvironment.apiBaseUrl}/api/review-check/${orderDetailId}/send-to-check`,
      {}
    );
  }

  markPaid(orderDetailId: string): Observable<ReviewCheckPayload> {
    return this.http.post<ReviewCheckPayload>(
      `${appEnvironment.apiBaseUrl}/api/review-check/${orderDetailId}/pay-ok`,
      {}
    );
  }

  updateReviewNote(orderDetailId: string, reviewId: number, comment: string): Observable<ReviewCheckPayload> {
    return this.http.put<ReviewCheckPayload>(
      `${appEnvironment.apiBaseUrl}/api/review-check/${orderDetailId}/reviews/${reviewId}/note`,
      { comment }
    );
  }

  updateOrderNote(orderDetailId: string, orderComments: string): Observable<ReviewCheckPayload> {
    return this.http.put<ReviewCheckPayload>(
      `${appEnvironment.apiBaseUrl}/api/review-check/${orderDetailId}/order-note`,
      { orderComments }
    );
  }

  updateCompanyNote(orderDetailId: string, companyComments: string): Observable<ReviewCheckPayload> {
    return this.http.put<ReviewCheckPayload>(
      `${appEnvironment.apiBaseUrl}/api/review-check/${orderDetailId}/company-note`,
      { companyComments }
    );
  }
}
