import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import type { OrderReviewItem, OrderDetailsPayload, ReviewUpdateRequest, OrderNotesResponse } from './api.service';
import { mobileEnvironment } from './mobile-environment';
@Injectable({ providedIn: 'root' })
export class OrderReviewsApi {
  private readonly http = inject(HttpClient);
  private apiUrl(path: string): string { return mobileEnvironment.apiBaseUrl + path; }
  updateManagerOrderReviewText(orderId: number, reviewId: number, text: string): Observable<OrderReviewItem> {
    return this.http.put<OrderReviewItem>(this.apiUrl(`/api/manager/orders/${orderId}/reviews/${reviewId}/text`), { text });
  }

  updateManagerOrderReviewAnswer(orderId: number, reviewId: number, answer: string): Observable<OrderReviewItem> {
    return this.http.put<OrderReviewItem>(this.apiUrl(`/api/manager/orders/${orderId}/reviews/${reviewId}/answer`), { answer });
  }

  updateManagerOrderReview(orderId: number, reviewId: number, request: ReviewUpdateRequest): Observable<OrderReviewItem> {
    return this.http.put<OrderReviewItem>(this.apiUrl(`/api/manager/orders/${orderId}/reviews/${reviewId}`), request);
  }

  uploadManagerOrderReviewPhoto(orderId: number, reviewId: number, file: File): Observable<OrderReviewItem> {
    const formData = new FormData();
    formData.append('file', file);

    return this.http.post<OrderReviewItem>(
      this.apiUrl(`/api/manager/orders/${orderId}/reviews/${reviewId}/photo`),
      formData
    );
  }

  deleteManagerOrderReview(orderId: number, reviewId: number): Observable<OrderDetailsPayload> {
    return this.http.delete<OrderDetailsPayload>(this.apiUrl(`/api/manager/orders/${orderId}/reviews/${reviewId}`));
  }
  updateManagerOrderReviewNote(orderId: number, reviewId: number, comment: string): Observable<OrderReviewItem> {
    return this.http.put<OrderReviewItem>(this.apiUrl(`/api/manager/orders/${orderId}/reviews/${reviewId}/note`), { comment });
  }

  updateManagerOrderNote(orderId: number, orderComments: string): Observable<OrderNotesResponse> {
    return this.http.put<OrderNotesResponse>(this.apiUrl(`/api/manager/orders/${orderId}/note`), { orderComments });
  }

  updateManagerOrderCompanyNote(orderId: number, companyComments: string): Observable<OrderNotesResponse> {
    return this.http.put<OrderNotesResponse>(this.apiUrl(`/api/manager/orders/${orderId}/company-note`), { companyComments });
  }
}
