import type { WritableSignal } from '@angular/core';
import type {
  CommonBillingApi,
  CommonInvoiceDetailsResponse,
  ManualPaymentConfirmationRequest
} from '../../core/common-billing.api';
import type {
  CommonManualPaymentAttributionApi,
  CommonManualPaymentMode
} from '../../core/common-manual-payment-attribution.api';
import type { OrderCardItem } from '../../core/manager.api';

export type ManagerCommonManualPaymentContext = {
  invoiceId: number;
  order: OrderCardItem;
  mode: CommonManualPaymentMode;
};

export interface ManagerCommonInvoicePaymentFacadeDeps {
  attributionApi: Pick<CommonManualPaymentAttributionApi, 'mode'>;
  commonBillingApi: Pick<CommonBillingApi, 'markPaid'>;
  mutationKey: WritableSignal<string | null>;
  requestLegacyEvidence: (invoiceId: number) => ManualPaymentConfirmationRequest | null;
  openAttribution: (context: ManagerCommonManualPaymentContext) => void;
  completed: (details: CommonInvoiceDetailsResponse, order: OrderCardItem) => void;
  failed: (title: string, message: string) => void;
  errorMessage: (error: unknown, fallback: string) => string;
}

/**
 * Keeps the manager quick action on the same server-authoritative payment mode
 * gate as the full common-invoice screen. Legacy payment is only reachable from
 * an explicit `attributionRequired: false` response.
 */
export class ManagerCommonInvoicePaymentFacade {
  constructor(private readonly deps: ManagerCommonInvoicePaymentFacadeDeps) {}

  start(order: OrderCardItem, invoiceId: number): void {
    this.deps.attributionApi.mode(invoiceId).subscribe({
      next: (mode) => {
        if (mode?.attributionRequired === true) {
          this.deps.openAttribution({
            invoiceId,
            order,
            mode: commonManualPaymentMode(order)
          });
          return;
        }
        if (mode?.attributionRequired !== false) {
          this.failModeCheck(
            'Сервис вернул некорректный режим ручной оплаты. Оплата не отмечена.'
          );
          return;
        }

        const evidence = this.deps.requestLegacyEvidence(invoiceId);
        if (!evidence) {
          this.deps.mutationKey.set(null);
          return;
        }

        this.deps.commonBillingApi.markPaid(invoiceId, evidence).subscribe({
          next: (details) => {
            this.deps.mutationKey.set(null);
            this.deps.completed(details, order);
          },
          error: (error) => {
            this.deps.mutationKey.set(null);
            this.deps.failed(
              'Общий счет не обновлен',
              this.deps.errorMessage(error, 'Не удалось изменить общий счет')
            );
          }
        });
      },
      error: (error) => {
        this.failModeCheck(
          this.deps.errorMessage(
            error,
            'Не удалось проверить режим учета получателей. Оплата не отмечена.'
          )
        );
      }
    });
  }

  private failModeCheck(message: string): void {
    this.deps.mutationKey.set(null);
    this.deps.failed('Режим ручной оплаты не проверен', message);
  }
}

export function commonManualPaymentMode(order: OrderCardItem): CommonManualPaymentMode {
  const fallback = (order.commonInvoiceStatus ?? '').trim().toUpperCase() === 'NEEDS_ATTENTION'
    && (order.commonInvoiceLastError ?? '').trim().toLowerCase()
      .startsWith('standalone_payment_route_conflict');
  return fallback ? 'TBANK_FALLBACK' : 'STANDARD';
}
