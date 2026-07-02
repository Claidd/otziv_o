import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
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
  paymentMethod?: PaymentMethod;
  expiresAt: string;
  payable: boolean;
  paymentPageMode?: TbankPaymentPageMode;
  tpayEnabled?: boolean;
  sberpayEnabled?: boolean;
  mirpayEnabled?: boolean;
  manualPaymentType?: ManualPaymentType | string | null;
  manualPhone?: string | null;
  manualRecipientName?: string | null;
  manualPaymentUrl?: string | null;
  manualPaymentButtonLabel?: string | null;
  manualComment?: string | null;
  receiptStatus?: PaymentReceiptStatus | string | null;
}

export interface PublicPaymentInitResponse {
  paymentUrl: string;
  paymentId: string;
  status: string;
  method?: PaymentMethod;
  qrPayload?: string | null;
  qrImage?: string | null;
}

export interface PublicCommonInvoiceOrder {
  orderId: number;
  companyId: number;
  companyTitle: string;
  filialTitle?: string | null;
  orderStatus: string;
  originalOrderStatus?: string | null;
  amount: number;
  amountKopecks: number;
  ready: boolean;
  paid: boolean;
  unpaid: boolean;
  detachable?: boolean;
  paidAt?: string | null;
}

export interface PublicCommonInvoice {
  token: string;
  title: string;
  accountName: string;
  status: string;
  amount: number;
  paid: number;
  remaining: number;
  amountKopecks: number;
  paidKopecks: number;
  remainingKopecks: number;
  payable: boolean;
  orders: PublicCommonInvoiceOrder[];
}

export interface PublicSbpBank {
  bankId: string;
  nspkBankId?: string | null;
  name: string;
  logoUrl?: string | null;
  order?: number | null;
  featured: boolean;
}

export interface ManagerPaymentLinkResponse {
  token: string;
  url: string;
  orderId?: number | null;
  amount: number;
  amountKopecks: number;
  status: string;
  paymentMethod?: PaymentMethod;
  expiresAt: string;
  instructionText?: string | null;
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
  reservedAmountKopecks?: number | null;
  confirmedAmountKopecks?: number | null;
  status: string;
  paymentMethod?: PaymentMethod;
  paymentProfileCode?: string | null;
  paymentProfileName?: string | null;
  manualSource?: ManualPaymentSource | string | null;
  manualTaskId?: number | null;
  manualTaskTitle?: string | null;
  tbankTerminalKey?: string | null;
  tbankPaymentId?: string | null;
  tbankOrderId?: string | null;
  payerEmail?: string | null;
  paymentUrl?: string | null;
  manualPaymentType?: ManualPaymentType | string | null;
  manualPhone?: string | null;
  manualRecipientName?: string | null;
  manualPaymentUrl?: string | null;
  manualPaymentButtonLabel?: string | null;
  manualComment?: string | null;
  manualReportedAt?: string | null;
  manualConfirmedBy?: string | null;
  manualConfirmedAt?: string | null;
  receiptStatus?: PaymentReceiptStatus | string | null;
  paymentSuccessNotifiedAt?: string | null;
  paymentSuccessNotificationError?: string | null;
  clientChatPlatform?: string | null;
  clientChatReady: boolean;
  clientChatWarning?: string | null;
  lastError?: string | null;
  createdAt: string;
  updatedAt: string;
  expiresAt: string;
  initiatedAt?: string | null;
  paidAt?: string | null;
  sbpQrCreatedAt?: string | null;
  archived: boolean;
  archivedAt?: string | null;
  archiveReason?: string | null;
  refundable: boolean;
}

export type PaymentLinkListSource = 'LIVE' | 'ARCHIVE';

export interface AdminPaymentLinkSummaryResponse {
  totalElements: number;
  totalAmount: number;
  totalAmountKopecks: number;
  paid: number;
  manualPending: number;
  confirmed: number;
  notificationsSent: number;
  notificationErrors: number;
  refundable: number;
  refunded: number;
  rejected: number;
}

export interface AdminPaymentLinksPageResponse {
  items: AdminPaymentLinkResponse[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  source: PaymentLinkListSource | string;
  summary: AdminPaymentLinkSummaryResponse;
}

export interface PaymentLinkArchiveRunResponse {
  eligible: number;
  archived: number;
  deleted: number;
  dryRun: boolean;
  reason: string;
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
  paymentPolicy: PaymentPolicy;
  manualPaymentType?: ManualPaymentType | string | null;
  manualPhone?: string | null;
  manualRecipientName?: string | null;
  manualPaymentUrl?: string | null;
  manualPaymentButtonLabel?: string | null;
  manualComment?: string | null;
  manualMonthlySoftLimitKopecks?: number | null;
  manualMonthlyHardLimitKopecks?: number | null;
  manualMonthlyUsedKopecks: number;
  manualMonthlyConfirmedKopecks: number;
  manualMonthlyPendingAmountKopecks: number;
  manualMonthlyPendingCount: number;
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
export type TbankPaymentPageMode = 'SBP_PRIMARY' | 'BANK_PRIMARY' | 'SBP_PAY_ONLY' | 'SBP_ONLY' | 'BANK_ONLY';
export type PaymentPolicy = 'T_BANK_ONLY' | 'MANUAL_UNTIL_LIMIT_THEN_TBANK';
export type PaymentMethod = 'BANK_FORM' | 'SBP_QR' | 'MANUAL_MOBILE_BANK' | 'MANUAL_EXTERNAL_LINK' | string;
export type PaymentReceiptStatus = 'PENDING' | 'MARKED';
export type ManualPaymentSource = 'PROFILE_MONTHLY_LIMIT' | 'MANUAL_TASK';
export type ManualPaymentType = 'MOBILE_BANK' | 'EXTERNAL_LINK';
export type ManualPaymentTaskStatus = 'ACTIVE' | 'PAUSED' | 'COMPLETED' | 'CANCELED';

export interface ManualPaymentTaskResponse {
  id: number;
  managerId?: number | null;
  managerTitle: string;
  username: string;
  paymentProfileId?: number | null;
  paymentProfileName: string;
  status: ManualPaymentTaskStatus | string;
  manualPaymentType?: ManualPaymentType | string | null;
  manualPhone?: string | null;
  manualRecipientName?: string | null;
  manualPaymentUrl?: string | null;
  manualPaymentButtonLabel?: string | null;
  targetAmountKopecks: number;
  reservedAmountKopecks: number;
  confirmedAmountKopecks: number;
  pendingAmountKopecks: number;
  remainingAmountKopecks: number;
  pendingCount: number;
  comment?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
  completedAt?: string | null;
  routable: boolean;
}

export interface ManualPaymentRecipientMonthlySummaryItem {
  manualRecipientName: string;
  manualPhone?: string | null;
  manualPaymentUrl?: string | null;
  manualPaymentButtonLabel?: string | null;
  paymentProfileName?: string | null;
  manualSource?: ManualPaymentSource | string | null;
  manualPaymentType?: ManualPaymentType | string | null;
  paymentCount: number;
  amountKopecks: number;
  firstConfirmedAt?: string | null;
  lastConfirmedAt?: string | null;
}

export interface ManualPaymentRecipientMonthlySummaryResponse {
  month: string;
  from: string;
  toExclusive: string;
  totalRecipients: number;
  totalPayments: number;
  totalAmountKopecks: number;
  totalAmount: number;
  items: ManualPaymentRecipientMonthlySummaryItem[];
}

export interface CreateManualPaymentTaskRequest {
  managerId?: number | null;
  manualPaymentType?: ManualPaymentType | string | null;
  manualPhone?: string | null;
  manualRecipientName?: string | null;
  manualPaymentUrl?: string | null;
  manualPaymentButtonLabel?: string | null;
  targetAmountKopecks: number;
  comment?: string | null;
}

export interface UpdateManualPaymentTaskRequest {
  manualPaymentType?: ManualPaymentType | string | null;
  manualPhone?: string | null;
  manualRecipientName?: string | null;
  manualPaymentUrl?: string | null;
  manualPaymentButtonLabel?: string | null;
  targetAmountKopecks: number;
  comment?: string | null;
}

export interface UpdateManualPaymentTaskStatusRequest {
  status: ManualPaymentTaskStatus;
}

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

export interface PaymentProfilePolicyRequest {
  profileId: number;
  paymentPolicy: PaymentPolicy;
  manualPaymentType?: ManualPaymentType | string | null;
  manualPhone?: string | null;
  manualRecipientName?: string | null;
  manualPaymentUrl?: string | null;
  manualPaymentButtonLabel?: string | null;
  manualComment?: string | null;
  manualMonthlySoftLimitKopecks?: number | null;
  manualMonthlyHardLimitKopecks?: number | null;
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

  getPublicCommonInvoice(token: string): Observable<PublicCommonInvoice> {
    return this.http.get<PublicCommonInvoice>(
      `${appEnvironment.apiBaseUrl}/api/payments/public/group/${encodeURIComponent(token)}`,
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

  initPublicCommonInvoicePayment(
    token: string,
    email: string,
    offerConsent: boolean,
    privacyConsent: boolean,
    receiptConsent: boolean
  ): Observable<PublicPaymentInitResponse> {
    return this.http.post<PublicPaymentInitResponse>(
      `${appEnvironment.apiBaseUrl}/api/payments/public/group/${encodeURIComponent(token)}/init`,
      { email, offerConsent, privacyConsent, receiptConsent },
      { context: this.publicContext }
    );
  }

  initPublicSbpPayment(
    token: string,
    email: string,
    offerConsent: boolean,
    privacyConsent: boolean,
    receiptConsent: boolean,
    sbpBankId?: string | null
  ): Observable<PublicPaymentInitResponse> {
    return this.http.post<PublicPaymentInitResponse>(
      `${appEnvironment.apiBaseUrl}/api/payments/public/${encodeURIComponent(token)}/sbp`,
      { email, offerConsent, privacyConsent, receiptConsent, sbpBankId },
      { context: this.publicContext }
    );
  }

  getPublicSbpBanks(token: string): Observable<PublicSbpBank[]> {
    return this.http.get<PublicSbpBank[]>(
      `${appEnvironment.apiBaseUrl}/api/payments/public/${encodeURIComponent(token)}/sbp/banks`,
      { context: this.publicContext }
    );
  }

  reportPublicManualPayment(token: string): Observable<PublicPaymentLink> {
    return this.http.post<PublicPaymentLink>(
      `${appEnvironment.apiBaseUrl}/api/payments/public/${encodeURIComponent(token)}/manual-paid`,
      {},
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

  getAdminTbankPaymentLinks(params?: {
    page?: number;
    size?: number;
    status?: string;
    search?: string;
    source?: PaymentLinkListSource;
    from?: string;
    to?: string;
  }): Observable<AdminPaymentLinksPageResponse> {
    let httpParams = new HttpParams();
    if (params) {
      Object.entries(params).forEach(([key, value]) => {
        if (value !== undefined && value !== null && String(value).trim() !== '') {
          httpParams = httpParams.set(key, String(value));
        }
      });
    }
    return this.http.get<AdminPaymentLinksPageResponse>(
      `${appEnvironment.apiBaseUrl}/api/admin/payments/tbank-links`,
      { params: httpParams }
    );
  }

  runAdminPaymentLinkArchive(dryRun: boolean, batchSize?: number): Observable<PaymentLinkArchiveRunResponse> {
    let params = new HttpParams().set('dryRun', String(dryRun));
    if (batchSize != null && Number.isFinite(batchSize) && batchSize > 0) {
      params = params.set('batchSize', String(batchSize));
    }
    return this.http.post<PaymentLinkArchiveRunResponse>(
      `${appEnvironment.apiBaseUrl}/api/admin/payments/tbank-links/archive/run`,
      {},
      { params }
    );
  }

  cancelAdminTbankPaymentLink(linkId: number): Observable<AdminPaymentLinkResponse> {
    return this.http.post<AdminPaymentLinkResponse>(
      `${appEnvironment.apiBaseUrl}/api/admin/payments/tbank-links/${linkId}/cancel`,
      {}
    );
  }

  confirmAdminManualPaymentLink(linkId: number): Observable<AdminPaymentLinkResponse> {
    return this.http.post<AdminPaymentLinkResponse>(
      `${appEnvironment.apiBaseUrl}/api/admin/payments/manual-links/${linkId}/confirm`,
      {}
    );
  }

  markAdminManualPaymentReceipt(linkId: number): Observable<AdminPaymentLinkResponse> {
    return this.http.post<AdminPaymentLinkResponse>(
      `${appEnvironment.apiBaseUrl}/api/admin/payments/manual-links/${linkId}/receipt`,
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

  updateAdminPaymentProfilePolicies(
    profiles: PaymentProfilePolicyRequest[]
  ): Observable<TbankPaymentProfilesResponse> {
    return this.http.put<TbankPaymentProfilesResponse>(
      `${appEnvironment.apiBaseUrl}/api/admin/payments/tbank-profiles/policies`,
      { profiles }
    );
  }

  getAdminManualPaymentTasks(): Observable<ManualPaymentTaskResponse[]> {
    return this.http.get<ManualPaymentTaskResponse[]>(
      `${appEnvironment.apiBaseUrl}/api/admin/payments/manual-tasks`
    );
  }

  getAdminManualRecipientMonthlySummary(month: string): Observable<ManualPaymentRecipientMonthlySummaryResponse> {
    const params = month ? new HttpParams().set('month', month) : new HttpParams();
    return this.http.get<ManualPaymentRecipientMonthlySummaryResponse>(
      `${appEnvironment.apiBaseUrl}/api/admin/payments/manual-recipients/monthly-summary`,
      { params }
    );
  }

  createAdminManualPaymentTask(
    request: CreateManualPaymentTaskRequest
  ): Observable<ManualPaymentTaskResponse> {
    return this.http.post<ManualPaymentTaskResponse>(
      `${appEnvironment.apiBaseUrl}/api/admin/payments/manual-tasks`,
      request
    );
  }

  updateAdminManualPaymentTaskStatus(
    taskId: number,
    status: ManualPaymentTaskStatus
  ): Observable<ManualPaymentTaskResponse> {
    return this.http.put<ManualPaymentTaskResponse>(
      `${appEnvironment.apiBaseUrl}/api/admin/payments/manual-tasks/${taskId}/status`,
      { status }
    );
  }

  updateAdminManualPaymentTask(
    taskId: number,
    request: UpdateManualPaymentTaskRequest
  ): Observable<ManualPaymentTaskResponse> {
    return this.http.put<ManualPaymentTaskResponse>(
      `${appEnvironment.apiBaseUrl}/api/admin/payments/manual-tasks/${taskId}`,
      request
    );
  }
}
