import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import type { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';
import type { CommonInvoiceDetailsResponse } from './common-billing.api';

export interface CommonManualPaymentAttributionModeResponse {
  attributionRequired: boolean;
}

export type CommonActualRecipientType = 'SPECIALIST' | 'MANAGER' | 'OWNER';
export type CommonManualPaymentMode = 'STANDARD' | 'TBANK_FALLBACK';
export type CommonPaymentCashDestinationKind = 'OWNER' | 'CONTRACTOR_PROFILE' | 'MANUAL_PAYMENT_TASK';
export type CommonManualPaymentTaskTargetKind = 'EXTERNAL_TASK' | 'OWNER' | 'SPECIALIST' | 'MANAGER';

export interface CommonManualPaymentRecipientCandidate {
  key: string;
  cashDestinationKind?: CommonPaymentCashDestinationKind | null;
  recipientType?: CommonActualRecipientType | null;
  recipientProfileId?: number | null;
  recipientUserId?: number | null;
  label: string;
  originalRecipient: boolean;
  currentParticipant: boolean;
  profileEnabled: boolean;
  availableKopecks?: number | null;
  manualPaymentTaskId?: number | null;
  manualPaymentTaskGeneration?: number | null;
  taskTargetKind?: CommonManualPaymentTaskTargetKind | null;
  taskRecipientName?: string | null;
  accountingTargetLabel?: string | null;
  effectText?: string | null;
}

export interface CommonManualPaymentAttributionHistoryItem {
  id: number;
  attributionKey: string;
  accountingMode: 'SHADOW' | 'LIVE';
  originalRecipientType: CommonActualRecipientType | null;
  originalRecipientProfileId?: number | null;
  originalRecipientLabel: string;
  actualRecipientType: CommonActualRecipientType | null;
  actualRecipientProfileId?: number | null;
  actualRecipientLabel: string;
  amountKopecks: number;
  availableBeforeKopecks?: number | null;
  projectedOverrunKopecks: number;
  effectiveAt: string;
  reason: string;
  evidenceReference: string;
  actor: string;
  createdAt: string;
  originalCashDestinationKind?: CommonPaymentCashDestinationKind | null;
  originalManualPaymentTaskId?: number | null;
  originalManualPaymentTaskGeneration?: number | null;
  originalTaskTargetKind?: CommonManualPaymentTaskTargetKind | null;
  actualCashDestinationKind?: CommonPaymentCashDestinationKind | null;
  actualManualPaymentTaskId?: number | null;
  actualManualPaymentTaskGeneration?: number | null;
  actualTaskTargetKind?: CommonManualPaymentTaskTargetKind | null;
}

export interface CommonManualPaymentOptions {
  invoiceId: number;
  remainingKopecks: number;
  defaultRecipientKey: string;
  contractVersion?: 'TASK_RECIPIENT_V1' | string | null;
  routeRevision?: string | null;
  candidates: CommonManualPaymentRecipientCandidate[];
  history: CommonManualPaymentAttributionHistoryItem[];
}

export interface CommonManualPaymentAttributionRowRequest {
  rowKey: string;
  recipientKey: string;
  recipientType: CommonActualRecipientType | null;
  recipientProfileId: number | null;
  amountKopecks: number;
}

export interface CommonManualPaymentAttributionRequest {
  idempotencyKey: string;
  finalAccountingAcknowledged: true;
  paymentReceived: true;
  effectiveAt: string;
  reason: string;
  receiptUrl: string;
  attributions: CommonManualPaymentAttributionRowRequest[];
}

@Injectable({ providedIn: 'root' })
export class CommonManualPaymentAttributionApi {
  private readonly http = inject(HttpClient);

  mode(invoiceId: number): Observable<CommonManualPaymentAttributionModeResponse> {
    return this.http.get<CommonManualPaymentAttributionModeResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/manual-payment-mode`
    );
  }

  options(invoiceId: number): Observable<CommonManualPaymentOptions> {
    return this.http.get<CommonManualPaymentOptions>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/manual-payment-options`
    );
  }

  confirm(
    invoiceId: number,
    mode: CommonManualPaymentMode,
    request: CommonManualPaymentAttributionRequest
  ): Observable<CommonInvoiceDetailsResponse> {
    const action = mode === 'TBANK_FALLBACK'
      ? 'attention/manual-card-paid-with-attributions'
      : 'paid-with-attributions';
    return this.http.post<CommonInvoiceDetailsResponse>(
      `${appEnvironment.apiBaseUrl}/api/common-billing/invoices/${invoiceId}/${action}`,
      request
    );
  }
}
