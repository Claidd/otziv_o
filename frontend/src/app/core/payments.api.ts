import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { appEnvironment } from './app-environment';
import { SKIP_AUTH_REDIRECT_ON_401 } from './auth-http-context';

export interface PublicPaymentLink {
  token: string;
  orderId?: number | null;
  companyTitle: string;
  filialTitle: string;
  serviceTitle: string;
  amount: number;
  amountKopecks: number;
  description: string;
  payerEmail?: string | null;
  status: string;
  expiresAt: string;
  payable: boolean;
  paymentPageMode?: TbankPaymentPageMode;
  tpayEnabled?: boolean;
  sberpayEnabled?: boolean;
  mirpayEnabled?: boolean;
}

export interface PublicPaymentInitResponse {
  paymentUrl: string;
  paymentId: string;
  status: string;
  method?: 'BANK_FORM' | 'SBP_QR' | string;
  qrPayload?: string | null;
  qrImage?: string | null;
}

export interface ManagerPaymentLinkResponse {
  token: string;
  url: string;
  orderId?: number | null;
  amount: number;
  amountKopecks: number;
  status: string;
  expiresAt: string;
  copyText: string;
}

export interface AdminPaymentLinkResponse {
  id: number;
  token: string;
  publicUrl: string;
  orderId?: number | null;
  companyTitle: string;
  filialTitle: string;
  description: string;
  amount: number;
  amountKopecks: number;
  status: string;
  paymentMethod?: 'BANK_FORM' | 'SBP_QR' | string;
  paymentProfileCode?: string | null;
  paymentProfileName?: string | null;
  tbankTerminalKey?: string | null;
  tbankPaymentId?: string | null;
  tbankOrderId?: string | null;
  payerEmail?: string | null;
  paymentUrl?: string | null;
  lastError?: string | null;
  createdAt: string;
  updatedAt: string;
  expiresAt: string;
  initiatedAt?: string | null;
  paidAt?: string | null;
  sbpQrCreatedAt?: string | null;
  refundable: boolean;
}

export interface PaymentProfileResponse {
  id: number;
  code: string;
  provider: string;
  name: string;
  terminalKey: string;
  passwordEnvKey?: string | null;
  enabled: boolean;
  defaultProfile: boolean;
  testMode: boolean;
  hasPassword: boolean;
}

export interface ManagerPaymentProfileResponse {
  managerId: number;
  managerTitle: string;
  username: string;
  paymentProfileId?: number | null;
  paymentProfileName?: string | null;
}

export interface TbankPaymentProfilesResponse {
  profiles: PaymentProfileResponse[];
  managers: ManagerPaymentProfileResponse[];
}

export interface TbankClientPaymentMode {
  enabled: boolean;
  paymentInstructionSource: PaymentInstructionSource;
}

export type TbankRuntimeMode = 'TEST' | 'LIVE';
export type PaymentInstructionSource = 'MANAGER_TEXT' | 'TBANK_LINK';
export type TbankPaymentPageMode = 'SBP_PRIMARY' | 'BANK_PRIMARY' | 'SBP_ONLY' | 'BANK_ONLY';

export interface TbankRuntimeSettings {
  runtimeMode: TbankRuntimeMode;
  testMode: boolean;
  tbankEnabled: boolean;
  paymentLinksEnabled: boolean;
  managerUiEnabled: boolean;
  applyConfirmedPayments: boolean;
  paymentInstructionSource: PaymentInstructionSource;
  clientTbankEnabled: boolean;
  paymentPageMode: TbankPaymentPageMode;
  tpayEnabled: boolean;
  sberpayEnabled: boolean;
  mirpayEnabled: boolean;
}

export interface UpdateTbankRuntimeSettingsRequest {
  runtimeMode?: TbankRuntimeMode;
  tbankEnabled?: boolean;
  paymentLinksEnabled?: boolean;
  managerUiEnabled?: boolean;
  applyConfirmedPayments?: boolean;
  paymentInstructionSource?: PaymentInstructionSource;
  paymentPageMode?: TbankPaymentPageMode;
  tpayEnabled?: boolean;
  sberpayEnabled?: boolean;
  mirpayEnabled?: boolean;
}

export interface ManagerPaymentProfileAssignmentRequest {
  managerId: number;
  paymentProfileId?: number | null;
}

export interface TbankPaymentStatus {
  enabled: boolean;
  paymentLinksEnabled: boolean;
  managerUiEnabled: boolean;
  applyConfirmedPayments: boolean;
  hasCredentials: boolean;
  testMode: boolean;
  runtimeMode: TbankRuntimeMode;
  baseUrl: string;
  publicBaseUrl: string;
  notificationUrl: string;
  successUrl: string;
  failUrl: string;
}

@Injectable({ providedIn: 'root' })
export class PaymentsApi {
  private readonly publicContext = new HttpContext().set(SKIP_AUTH_REDIRECT_ON_401, true);

  constructor(private readonly http: HttpClient) {}

  getPublicPaymentLink(token: string): Observable<PublicPaymentLink> {
    return this.http.get<PublicPaymentLink>(
      `${appEnvironment.apiBaseUrl}/api/payments/public/${encodeURIComponent(token)}`,
      { context: this.publicContext }
    );
  }

  initPublicPayment(
    token: string,
    email: string,
    offerConsent: boolean,
    privacyConsent: boolean,
    receiptConsent: boolean
  ): Observable<PublicPaymentInitResponse> {
    return this.http.post<PublicPaymentInitResponse>(
      `${appEnvironment.apiBaseUrl}/api/payments/public/${encodeURIComponent(token)}/init`,
      { email, offerConsent, privacyConsent, receiptConsent },
      { context: this.publicContext }
    );
  }

  initPublicSbpPayment(
    token: string,
    email: string,
    offerConsent: boolean,
    privacyConsent: boolean,
    receiptConsent: boolean
  ): Observable<PublicPaymentInitResponse> {
    return this.http.post<PublicPaymentInitResponse>(
      `${appEnvironment.apiBaseUrl}/api/payments/public/${encodeURIComponent(token)}/sbp`,
      { email, offerConsent, privacyConsent, receiptConsent },
      { context: this.publicContext }
    );
  }

  getTbankStatus(): Observable<TbankPaymentStatus> {
    return this.http.get<TbankPaymentStatus>(
      `${appEnvironment.apiBaseUrl}/api/payments/public/tbank-status`,
      { context: this.publicContext }
    );
  }

  createOrderPaymentLink(orderId: number): Observable<ManagerPaymentLinkResponse> {
    return this.http.post<ManagerPaymentLinkResponse>(
      `${appEnvironment.apiBaseUrl}/api/manager/orders/${orderId}/payment-link`,
      {}
    );
  }

  getAdminTbankPaymentLinks(): Observable<AdminPaymentLinkResponse[]> {
    return this.http.get<AdminPaymentLinkResponse[]>(
      `${appEnvironment.apiBaseUrl}/api/admin/payments/tbank-links`
    );
  }

  cancelAdminTbankPaymentLink(linkId: number): Observable<AdminPaymentLinkResponse> {
    return this.http.post<AdminPaymentLinkResponse>(
      `${appEnvironment.apiBaseUrl}/api/admin/payments/tbank-links/${linkId}/cancel`,
      {}
    );
  }

  getAdminTbankPaymentProfiles(): Observable<TbankPaymentProfilesResponse> {
    return this.http.get<TbankPaymentProfilesResponse>(
      `${appEnvironment.apiBaseUrl}/api/admin/payments/tbank-profiles`
    );
  }

  getAdminTbankClientPaymentMode(): Observable<TbankClientPaymentMode> {
    return this.http.get<TbankClientPaymentMode>(
      `${appEnvironment.apiBaseUrl}/api/admin/payments/tbank-client-payment-mode`
    );
  }

  updateAdminTbankClientPaymentMode(enabled: boolean): Observable<TbankClientPaymentMode> {
    return this.http.put<TbankClientPaymentMode>(
      `${appEnvironment.apiBaseUrl}/api/admin/payments/tbank-client-payment-mode`,
      { enabled }
    );
  }

  getAdminTbankRuntimeSettings(): Observable<TbankRuntimeSettings> {
    return this.http.get<TbankRuntimeSettings>(
      `${appEnvironment.apiBaseUrl}/api/admin/payments/tbank-runtime-settings`
    );
  }

  updateAdminTbankRuntimeSettings(
    request: UpdateTbankRuntimeSettingsRequest
  ): Observable<TbankRuntimeSettings> {
    return this.http.put<TbankRuntimeSettings>(
      `${appEnvironment.apiBaseUrl}/api/admin/payments/tbank-runtime-settings`,
      request
    );
  }

  updateAdminTbankPaymentProfileAssignments(
    assignments: ManagerPaymentProfileAssignmentRequest[]
  ): Observable<TbankPaymentProfilesResponse> {
    return this.http.put<TbankPaymentProfilesResponse>(
      `${appEnvironment.apiBaseUrl}/api/admin/payments/tbank-profiles/manager-assignments`,
      { assignments }
    );
  }
}
