import { HttpClient, HttpContext, HttpHeaders } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { OPTIONAL_AUTH_TOKEN, SKIP_AUTH_REDIRECT_ON_401, SKIP_AUTH_TOKEN } from './auth-http-context';
import { appEnvironment } from './app-environment';

const OPAQUE_REVIEW_CAPABILITY = /^rc1_[A-Za-z0-9_-]{43}$/;

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
    .set(OPTIONAL_AUTH_TOKEN, true);
  private readonly capabilityContext = new HttpContext()
    .set(SKIP_AUTH_REDIRECT_ON_401, true)
    .set(SKIP_AUTH_TOKEN, true);

  constructor(private readonly http: HttpClient) {}

  getReviewCheck(orderDetailId: string, capabilityToken?: string | null): Observable<ReviewCheckPayload> {
    return this.http.get<ReviewCheckPayload>(
      this.endpoint(orderDetailId, capabilityToken),
      this.options(capabilityToken)
    );
  }

  saveReviews(orderDetailId: string, request: ReviewCheckUpdateRequest, capabilityToken?: string | null): Observable<ReviewCheckPayload> {
    return this.http.put<ReviewCheckPayload>(
      this.endpoint(orderDetailId, capabilityToken),
      request,
      this.options(capabilityToken)
    );
  }

  updateReviewText(orderDetailId: string, reviewId: number, text: string, capabilityToken?: string | null): Observable<ReviewCheckReview> {
    return this.http.put<ReviewCheckReview>(
      this.endpoint(orderDetailId, capabilityToken, `/reviews/${reviewId}/text`),
      { text },
      this.options(capabilityToken)
    );
  }

  updateReviewAnswer(orderDetailId: string, reviewId: number, answer: string, capabilityToken?: string | null): Observable<ReviewCheckReview> {
    return this.http.put<ReviewCheckReview>(
      this.endpoint(orderDetailId, capabilityToken, `/reviews/${reviewId}/answer`),
      { answer },
      this.options(capabilityToken)
    );
  }

  approveReviews(orderDetailId: string, request: ReviewCheckUpdateRequest, capabilityToken?: string | null): Observable<ReviewCheckPayload> {
    return this.http.post<ReviewCheckPayload>(
      this.endpoint(orderDetailId, capabilityToken, '/approve'),
      request,
      this.options(capabilityToken)
    );
  }

  sendToCorrection(orderDetailId: string, request: ReviewCheckUpdateRequest, capabilityToken?: string | null): Observable<ReviewCheckPayload> {
    return this.http.post<ReviewCheckPayload>(
      this.endpoint(orderDetailId, capabilityToken, '/correction'),
      request,
      this.options(capabilityToken)
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

  private endpoint(orderDetailId: string, capabilityToken?: string | null, suffix = ''): string {
    if (capabilityToken) {
      return `${appEnvironment.apiBaseUrl}/api/review-capability${suffix}`;
    }
    return `${appEnvironment.apiBaseUrl}/api/review-check/${orderDetailId}${suffix}`;
  }

  private options(capabilityToken?: string | null): { context: HttpContext; headers?: HttpHeaders } {
    if (!capabilityToken || !OPAQUE_REVIEW_CAPABILITY.test(capabilityToken)) {
      return { context: this.publicContext };
    }
    return {
      context: this.capabilityContext,
      headers: new HttpHeaders({ 'X-Review-Capability': capabilityToken })
    };
  }
}
