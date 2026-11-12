import { openPaperInvoiceDialog } from '../shared/paper-invoice-dialog.component';
import { ChangeDetectionStrategy } from '@angular/core';
import {
  commonInvoiceDeliveryWait, commonInvoiceDeliveryWarning, deliveryOperationMessage, DeliveryStatusWatcher
} from '@otziv/client-common/billing-payments';
import { CommonBillingApi } from '../core/common-billing.api';
import { OrderPaymentApi } from '../core/order-payment.api';
import { inject, Component, OnDestroy, OnInit, computed, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { IonContent, IonRefresher, IonRefresherContent, RefresherCustomEvent, ToastController } from '@ionic/angular';
import { Subscription, firstValueFrom } from 'rxjs';
import {
  CommonInvoiceDetailsResponse,
  CommonInvoiceOrderResponse,
  CommonInvoicePaymentRouteChangeContextResponse,
  CommonInvoicePaymentRouteChangeTarget,
  CommonInvoiceSummaryResponse,
  CommonManualPaymentMode,
  ManualPaymentConfirmationRequest,
  OrderItem
} from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { RouteEpochGuard, type RouteEpochTicket } from '../core/route-epoch.guard';
import { displayPhone, normalizePhoneDigits, phoneHref } from '../shared/phone-format';
import { MobileHeaderComponent } from '../shared/mobile-header.component';
import { MobileConfirmService } from '../shared/mobile-confirm.service';
import { MobileCommonManualPaymentFlowService } from '../shared/mobile-common-manual-payment-flow.service';
import { MobilePaymentRecipientPickerFlowService } from '../shared/mobile-payment-recipient-picker-flow.service';
import {
  RETURN_ORDERS_EMPTY,
  RETURN_ORDERS_FIXED,
  RETURN_ORDERS_INTRO,
  canReturnOrdersToCommonInvoice,
  returnOrderConsequence,
  returnOrderLabel,
  returnOrderRefusal
} from '../shared/common-invoice-return-orders';
import type { CommonInvoiceReturnableOrder, CommonInvoiceReturnableOrdersResponse } from '../core/api.service';
import {
  mobilePaymentRecipientAutoHint,
  mobilePaymentRecipientOptions,
  mobilePaymentRecipientOwnerCard,
  type MobilePaymentRecipientChoice,
  type MobilePaymentRecipientIssueOutcome
} from '../shared/payment-recipient-picker';
import { MobileOrderCardComponent } from '../shared/mobile-order-card.component';
import {
  COMMON_INVOICE_NO_PAYMENT_ACTION_LABEL,
  commonInvoiceAttentionPolicy,
  commonInvoiceNoPaymentActionHint,
  commonInvoiceNoPaymentConfirmation,
  commonInvoicePaymentInitInstructions,
  commonInvoicePaymentEvidence,
  commonInvoicePaymentEvidenceConfirmationLines,
  commonInvoicePaymentEvidenceSnapshot
} from '../shared/common-invoice-attention-policy';
import { orderReviewCopyText } from '../shared/order-review-copy-text';
import { buildManualPaymentConfirmationRequest } from '../shared/manual-payment-confirmation';

type InvoiceAction =
  | 'send'
  | 'remind'
  | 'paid'
  | 'unpaid'
  | 'ban'
  | 'repair-payment-route'
  | 'resolve-technical-tail'
  | 'resolve-payment-notification'
  | 'retry'
  | 'resolve'
  | 'late-payment'
  | 'final-cancel-check'
  | 'payment-init-check'
  | 'approve-review-orders';

@Component({
  changeDetection: ChangeDetectionStrategy.Eager,
  selector: 'app-common-billing-mobile',
  imports: [IonContent, IonRefresher, IonRefresherContent, MobileHeaderComponent, MobileOrderCardComponent],
  template: `
    <div class="ion-page">
      <app-mobile-header title="Общий счет" />

      <ion-content fullscreen>
        <ion-refresher slot="fixed" (ionRefresh)="refresh($event)">
          <ion-refresher-content />
        </ion-refresher>

        <main class="common-billing-page">
          @if (loading() && !details()) {
            <p class="state-card">Загружаю общий счет...</p>
          }

          @if (details()?.delivery; as delivery) {
            <p class="state-card" role="status">{{ deliveryStatusText(delivery) }}</p>
          }
          @if (error()) {
            <button class="state-card state-card--error" type="button" (click)="reload()">
              <span class="material-icons-sharp">error</span>
              {{ error() }}
            </button>
          }

          @if (summary(); as invoice) {
            <section class="invoice-hero">
              <div>
                <small>{{ statusLabel(invoice.status) }}</small>
                <h1>{{ invoice.title || invoice.accountName || 'Общий счет' }}</h1>
              </div>
              <button type="button" class="icon-button" (click)="reload()" [disabled]="loading()" aria-label="Обновить">
                <span class="material-icons-sharp">refresh</span>
              </button>
            </section>

            <section class="invoice-stats" aria-label="Сводка общего счета">
              <article>
                <span>Сумма</span>
                <strong>{{ kopecks(invoice.amountKopecks) }}</strong>
              </article>
              <article>
                <span>Оплачено</span>
                <strong>{{ kopecks(invoice.paidKopecks) }}</strong>
              </article>
              <article>
                <span>Остаток</span>
                <strong>{{ kopecks(invoice.remainingKopecks) }}</strong>
              </article>
              <article>
                <span>Готово</span>
                <strong>{{ invoice.readyOrders }}/{{ invoice.totalOrders }}</strong>
              </article>
            </section>

            @if (invoice.invoicePaymentMode === 'OWNER_PAPER_INVOICE') {
              <section class="invoice-mode-card invoice-mode-card--paper">
                <span class="material-icons-sharp" aria-hidden="true">description</span>
                <div>
                  <strong>Бумажный счёт владельца</strong>
                  <p>Реквизиты сотрудников и банковская ссылка не отправляются. После передачи документа отметьте «Счёт отправлен».</p>
                </div>
              </section>
            } @else if (invoice.paymentRouteSelectedAt) {
              <section class="invoice-mode-card">
                <span class="material-icons-sharp" aria-hidden="true">account_balance_wallet</span>
                <div>
                  <strong>{{ paymentRouteLabel(invoice) }}</strong>
                  <p>Текущий способ оплаты общего счёта.</p>
                </div>
              </section>
            }

            <section class="invoice-actions" aria-label="Действия общего счета">
              @if (invoice.status !== 'NEEDS_ATTENTION'
                && invoice.invoicePaymentMode !== 'OWNER_PAPER_INVOICE'
                && invoice.paymentRouteSelectedAt
                && invoice.status !== 'PAID') {
                <button type="button" (click)="changePaymentRoute(cardNumberRouteTarget(invoice.paymentRouteType))" [disabled]="!!mutating()"
                  [title]="cardNumberRouteTarget(invoice.paymentRouteType) === 'EMPLOYEE_REQUISITES' ? 'Ссылка → номер карты: получатель может остаться прежним' : 'Номер карты другого человека: нынешний получатель исключается'">Выдать номер карты</button>
                @if (!isBankPaymentRoute(invoice.paymentRouteType)) {
                  <button type="button" (click)="changePaymentRoute('OWNER_TBANK')" [disabled]="!!mutating()">Выдать ссылку банка</button>
                }
                @if (isBankPaymentRoute(invoice.paymentRouteType)) {
                  <button type="button" (click)="reissueOwnerBankRoute()" [disabled]="!!mutating()">Выдать новую ссылку банка</button>
                }
                <p class="invoice-action-hint">{{ cardNumberRouteHint(invoice.paymentRouteType) }}</p>
              }
              @if (canManagePaperInvoices()
                && (invoice.status !== 'NEEDS_ATTENTION' || paperModeSwitchNeedsRetry(invoice))
                && (invoice.status !== 'PAID' || invoice.invoicePaymentMode === 'OWNER_PAPER_INVOICE')) {
                <button type="button" (click)="changePaperInvoiceMode()" [disabled]="!!mutating()">
                  {{ invoice.invoicePaymentMode === 'OWNER_PAPER_INVOICE'
                    ? 'Счёт и акт'
                    : (paperModeSwitchNeedsRetry(invoice) ? 'Повторить бумажный счёт' : 'Бумажный счёт') }}
                </button>
              }
              @if (canManagePaperInvoices()
                && invoice.invoicePaymentMode === 'OWNER_PAPER_INVOICE'
                && invoice.sentAt
                && !invoice.paperInvoiceIssuedAt) {
                <button type="button" class="success" (click)="markPaperInvoiceIssued()" [disabled]="!!mutating()">Счёт отправлен</button>
              }
              @if (invoice.status === 'NEEDS_ATTENTION') {
                @if (manualAttributionRequired() && invoice.paidKopecks === 0 && invoice.invoicePaymentMode !== 'OWNER_PAPER_INVOICE'
                    && (invoice.lastError?.startsWith('bad_review_supplement_required:') || invoice.lastError?.startsWith('Ожидаем закрытия прежней банковской ссылки.'))) {
                  <button type="button" (click)="runInvoiceAction('paid')" [disabled]="!!mutating()">Оплата конкретному специалисту</button>
                }
                @if (attentionPolicy(invoice).latePayment) {
                  <button type="button" (click)="runInvoiceAction('late-payment')" [disabled]="!!mutating()">Поздняя оплата</button>
                }
                @if (attentionPolicy(invoice).finalCancelCheck) {
                  <button type="button" (click)="runInvoiceAction('final-cancel-check')" [disabled]="!!mutating()">Сверка отмены</button>
                }
                @if (attentionPolicy(invoice).paymentInitCheck) {
                  <button type="button" (click)="runInvoiceAction('payment-init-check')" [disabled]="!!mutating() || !paymentInitCheckReady()">{{ paymentInitNoPaymentActionLabel }}</button>
                  <p class="invoice-action-hint">{{ paymentInitNoPaymentActionHint }}</p>
                }
                @if (attentionPolicy(invoice).standaloneRouteConflict) {
                  <button type="button" (click)="runInvoiceAction('repair-payment-route')" [disabled]="!!mutating()">Починить маршрут</button>
                  <button type="button" class="success" (click)="reportManualCardPayment()" [disabled]="!!mutating() || manualAttributionRequired() == null">
                    Оплата переводом
                  </button>
                  <p class="invoice-action-hint">Укажите, кому фактически поступили деньги; выбор сразу изменит расчёты.</p>
                }
                @if (!attentionPolicy(invoice).requiresManualCheck) {
                  <button type="button" (click)="runInvoiceAction('retry')" [disabled]="!!mutating()">Повторить</button>
                  <button type="button" (click)="runInvoiceAction('resolve')" [disabled]="!!mutating()">Закрыть проверку</button>
                }
              } @else {
                @if (canResolveTechnicalTail(invoice)) {
                  <button type="button" class="success" (click)="runInvoiceAction('resolve-technical-tail')" [disabled]="!!mutating()">Закрыть хвост</button>
                } @else if (canResolvePaymentNotification(invoice)) {
                  <button type="button" class="success" (click)="runInvoiceAction('resolve-payment-notification')" [disabled]="!!mutating()">Уведомление обработано</button>
                } @else {
                  <button type="button" (click)="runInvoiceAction('send')" [disabled]="!!mutating() || !canSendInvoice(invoice)">Отправить</button>
                }
                <button type="button" (click)="copyPublicUrl(invoice)" [disabled]="!invoice.publicUrl || invoice.invoicePaymentMode === 'OWNER_PAPER_INVOICE'">Ссылка</button>
                <button type="button" (click)="runInvoiceAction('approve-review-orders')" [disabled]="!!mutating() || !canApproveReviewOrders()">Одобрить все</button>
                @if (invoice.contractorPaymentRoute) {
                  @if (manualAttributionRequired() && canMarkInvoicePaid(invoice)) {
                    <button type="button" (click)="runInvoiceAction('paid')" [disabled]="!!mutating()">Оплата конкретному специалисту</button>
                  }
                  <button type="button" class="success" (click)="confirmContractorPaymentSource()" [disabled]="!!mutating() || !canConfirmContractorSource(invoice)">Сверить поступление</button>
                } @else {
                  <button type="button" class="success" (click)="runInvoiceAction('paid')" [disabled]="!!mutating() || !canMarkInvoicePaid(invoice)">Оплачен</button>
                }
                @if (manualUnpaidAllowed()) {
                  <button type="button" class="danger" (click)="runInvoiceAction('unpaid')" [disabled]="!!mutating() || !canMarkInvoiceUnpaid(invoice)">Не оплачен</button>
                }
                <button type="button" (click)="runInvoiceAction('remind')" [disabled]="!!mutating() || !canRemindInvoice(invoice)">Напомнить</button>
                <button type="button" class="danger" (click)="runInvoiceAction('ban')" [disabled]="!!mutating() || !canMarkInvoiceBan(invoice)">Бан</button>
                @if (canArchiveInvoice(invoice)) {
                  <button type="button" class="danger" (click)="archiveInvoice()" [disabled]="!!mutating()">В архив</button>
                }
                @if (canDeleteInvoiceWithOrders(invoice)) {
                  <button type="button" class="danger" (click)="deleteInvoiceWithOrders(invoice)" [disabled]="!!mutating()">Удалить</button>
                }
                @if (invoiceActionHint(invoice)) {
                  <p class="invoice-action-hint">{{ invoiceActionHint(invoice) }}</p>
                }
              }
            </section>

            @if (invoice.paymentRouteSelectedAt || invoice.sentAt || invoice.paperInvoiceIssuedAt || invoice.lastReminderAt || invoice.nextReminderAt || invoice.lastError || invoice.paymentSuccessNotificationError) {
              <section class="invoice-timeline">
                @if (invoice.paymentRouteSelectedAt) {
                  <span>Маршрут: {{ paymentRouteLabel(invoice) }} · {{ dateTime(invoice.paymentRouteSelectedAt) }}</span>
                }
                @if (invoice.sentAt) {
                  <span>Отправлен: {{ dateTime(invoice.sentAt) }}</span>
                }
                @if (invoice.lastReminderAt) {
                  <span>Напоминание: {{ dateTime(invoice.lastReminderAt) }}</span>
                }
                @if (invoice.paperInvoiceIssuedAt) {
                  <span>Бумажный документ отправлен: {{ dateTime(invoice.paperInvoiceIssuedAt) }}</span>
                }
                @if (invoice.nextReminderAt) {
                  <span>Следующее: {{ dateTime(invoice.nextReminderAt) }}</span>
                }
                @if (deliveryWait(invoice.lastError, details()?.delivery); as waiting) {
                  <span>{{ waiting }}</span>
                } @else if (invoice.lastError) {
                  <span class="error-line">{{ invoice.lastError }}</span>
                }
                @if (invoice.paymentSuccessNotificationError) {
                  <span class="error-line">Уведомление: {{ invoice.paymentSuccessNotificationError }}</span>
                }
              </section>
            }

            @if (attentionPolicy(invoice).paymentInitCheck) {
              <section class="payment-evidence" aria-label="Банковские реквизиты для ручной сверки">
                <header>
                  <span>Как проверить платёж в банке</span>
                  <strong>{{ paymentEvidence().length }}</strong>
                </header>
                <ol class="payment-check-guide">
                  @for (step of paymentInitInstructions; track step) {
                    <li>{{ step }}</li>
                  }
                </ol>
                @for (evidence of paymentEvidence(); track evidence.key) {
                  <article>
                    <strong>{{ evidence.label }}</strong>
                    <span>OrderId: <samp>{{ evidence.orderId }}</samp></span>
                    <span>PaymentId: <samp>{{ evidence.paymentId }}</samp></span>
                    <span>Сумма: <samp>{{ evidence.amountKopecks === null ? 'не сохранена' : kopecks(evidence.amountKopecks) }}</samp></span>
                    <span>Статус: <samp>{{ evidence.status || 'не сохранён' }}</samp></span>
                    <span>Причина: <samp>{{ evidence.reason || 'не сохранена' }}</samp></span>
                    <span>Терминал: <samp>{{ evidence.terminalLabel }}</samp></span>
                    <span>TerminalKey: <samp>{{ evidence.terminalKey }}</samp></span>
                  </article>
                }
                @if (!paymentEvidence().length) {
                  <p class="payment-evidence-empty">Идентификаторы не сохранены. Ищите операцию по сумме, времени попытки и терминалу.</p>
                }
              </section>
            }

            <section class="invoice-orders" aria-label="Заказы общего счета">
              <header>
                <span>Состав</span>
                <strong>{{ orders().length }}</strong>
              </header>

              @if (canReturnOrders()) {
                <div class="return-orders">
                  <button type="button" class="return-orders-toggle" (click)="toggleReturnOrders()" [disabled]="!!mutating()">
                    {{ returnOrdersOpen() ? 'Скрыть заказы для возврата' : 'Вернуть заказ в счёт' }}
                  </button>
                  @if (!returnOrdersOpen()) {
                    <p class="invoice-action-hint">Заказ исключили, а клиент просит вернуть его. Работает, пока счёт собирается и не отправлен клиенту.</p>
                  } @else {
                    <p class="invoice-action-hint">{{ returnOrdersIntro }}</p>
                    @if (returnOrdersLoading()) {
                      <p class="state-card">Загружаем заказы…</p>
                    } @else if (returnOrdersError(); as loadError) {
                      <p class="state-card state-card--error" role="alert">{{ loadError }}</p>
                      <button type="button" class="return-orders-toggle" (click)="loadReturnableOrders()">Повторить</button>
                    } @else if (returnableOrders(); as answer) {
                      @if (!answer.canReturn) {
                        <p class="state-card state-card--error" role="alert">
                          Вернуть заказ в этот счёт нельзя, счёт не изменён. {{ answer.blockReason || returnOrdersFixed }}
                        </p>
                      } @else if (!answer.orders.length) {
                        <p class="state-card" role="status">{{ returnOrdersEmpty }}</p>
                      } @else {
                        <ul class="return-orders-list" aria-label="Заказы, которые можно вернуть в счёт">
                          @for (candidate of answer.orders; track candidate.orderId) {
                            <li>
                              <span>
                                <strong>{{ candidate.companyTitle || 'Компания №' + candidate.companyId }}</strong>
                                <small>{{ returnOrderLabel(candidate) }}</small>
                              </span>
                              <button type="button" (click)="returnOrder(candidate)" [disabled]="!!mutating()"
                                [attr.aria-label]="'Вернуть заказ №' + candidate.orderId + ' в счёт'">
                                {{ mutating() === 'return-order-' + candidate.orderId ? 'Возвращаем…' : 'Вернуть' }}
                              </button>
                            </li>
                          }
                        </ul>
                      }
                    }
                    @if (returnOrderRefusal(); as refusal) {
                      <p class="state-card state-card--error" role="alert">{{ refusal }}</p>
                    }
                  }
                </div>
              }

              @if (manualAttributionRequired()) {
                <p class="invoice-action-hint">В новом режиме частичную ручную оплату не закрывают по одному заказу: распределите весь остаток через кнопку «Оплачен».</p>
              }
              @for (order of orderCards(); track order.id) {
                <div class="invoice-order-card">
                  <app-mobile-order-card
                    [order]="order"
                    [statusActions]="[]"
                    [copiedKey]="copiedKey()"
                    [mutationKey]="mutating()"
                    [title]="orderTitle(order)"
                    [titleHref]="orderChatUrl(order)"
                    [toneClass]="orderTone(order)"
                    [statusLabel]="order.status || '-'"
                    [amountLabel]="orderMoney(orderPayableSum(order))"
                    [phoneLabel]="orderPhone(order)"
                    [phoneHref]="orderChatUrl(order)"
                    [reviewHref]="orderReviewUrl(order)"
                    [filialHref]="orderFilialUrl(order)"
                    [phoneCopyKey]="'invoice-order-phone-' + order.id"
                    [reviewCopyKey]="'invoice-order-review-' + order.id"
                    [paymentCopyKey]="'invoice-order-payment-' + order.id"
                    [progress]="orderProgress(order)"
                    [cityLabel]="orderCity(order)"
                    [workerLabel]="workerLabel(order)"
                    [workerTitle]="order.workerUserFio || 'Исполнитель не назначен'"
                    [showNoteEditor]="false"
                    [canManageOrderStatuses]="false"
                    [canManageClientWaiting]="false"
                    [unchangedDays]="orderUnchangedDays(order)"
                    [unchangedAlert]="isOrderUnchangedAlert(order)"
                    (copyPhone)="copyOrderPhone(order)"
                    (copyText)="copyOrderText(order, $event)"
                    (details)="openOrderCard(order)"
                    (workerClick)="openOrderCard(order)"
                  />
                  @if (invoiceOrderForCard(order); as invoiceOrder) {
                    <div class="order-actions">
                      <button type="button" (click)="openOrder(invoiceOrder)">Открыть</button>
                      <button type="button" (click)="markOrderPaid(invoiceOrder)" [disabled]="!!mutating() || invoiceOrder.paid || summary()?.status === 'NEEDS_ATTENTION' || manualAttributionRequired() !== false">
                        Оплачен
                      </button>
                      <button type="button" class="danger" (click)="detachOrder(invoiceOrder)" [disabled]="!!mutating() || !invoiceOrder.detachable || summary()?.status === 'NEEDS_ATTENTION'">
                        Отключить
                      </button>
                    </div>
                  }
                </div>
              } @empty {
                <p class="state-card">В общем счете пока нет заказов.</p>
              }
            </section>
          }
        </main>
      </ion-content>
    </div>
  `,
  styles: [`
    .common-billing-page {
      display: grid;
      gap: 0.7rem;
      min-height: 100%;
      padding: 0.7rem var(--otziv-page-padding-x, 0.68rem) calc(var(--otziv-tabbar-height, 4.2rem) + 1rem);
      background: var(--otziv-page-background);
    }

    .state-card,
    .invoice-hero,
    .invoice-stats,
    .invoice-mode-card,
    .invoice-actions,
    .invoice-timeline,
    .payment-evidence,
    .invoice-orders {
      border: 1px solid rgba(103, 116, 131, 0.14);
      border-radius: 0.9rem;
      background: rgba(255, 255, 255, 0.94);
      box-shadow: 0 0.55rem 1.3rem rgba(132, 139, 200, 0.12);
    }

    .state-card {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 0.4rem;
      min-height: 3.2rem;
      margin: 0;
      padding: 0.75rem;
      color: var(--otziv-info);
      font: 900 0.82rem/1.2 var(--otziv-card-title-font);
    }

    .state-card--error {
      color: var(--otziv-danger);
    }

    .invoice-hero {
      display: grid;
      grid-template-columns: minmax(0, 1fr) 2.55rem;
      align-items: center;
      gap: 0.6rem;
      padding: 0.8rem;
    }

    .invoice-hero small,
    .invoice-stats span,
    .invoice-timeline span,
    .invoice-orders small {
      color: var(--otziv-info);
      font: 900 0.66rem/1.2 var(--otziv-card-title-font);
    }

    .invoice-hero h1 {
      margin: 0.18rem 0 0;
      color: var(--otziv-dark);
      font: 1000 1.05rem/1.08 var(--otziv-card-title-font);
    }

    .icon-button {
      display: grid;
      min-width: 2.35rem;
      min-height: 2.35rem;
      place-items: center;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 0.8rem;
      color: var(--otziv-info);
      background: var(--otziv-muted-surface);
    }

    .invoice-stats {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.5rem;
      padding: 0.65rem;
    }

    .invoice-stats article {
      display: grid;
      gap: 0.25rem;
      min-height: 4.1rem;
      border: 1px solid rgba(103, 116, 131, 0.12);
      border-radius: 0.75rem;
      padding: 0.6rem;
      background: linear-gradient(145deg, var(--otziv-white) 0%, var(--otziv-tone-work-surface) 100%);
    }

    .invoice-stats strong {
      color: var(--otziv-dark);
      font: 1000 1rem/1 var(--otziv-card-title-font);
    }

    .invoice-mode-card {
      display: grid;
      grid-template-columns: 2.2rem minmax(0, 1fr);
      align-items: start;
      gap: 0.6rem;
      padding: 0.72rem;
      color: var(--otziv-dark);
    }

    .invoice-mode-card > span {
      display: grid;
      width: 2.2rem;
      height: 2.2rem;
      place-items: center;
      border-radius: 0.72rem;
      color: var(--otziv-accent, #df432f);
      background: var(--otziv-tone-warning-surface);
    }

    .invoice-mode-card strong {
      display: block;
      font: 1000 0.8rem/1.2 var(--otziv-card-title-font);
    }

    .invoice-mode-card p {
      margin: 0.2rem 0 0;
      color: var(--otziv-info);
      font: 800 0.66rem/1.3 var(--otziv-card-title-font);
    }

    .invoice-mode-card--paper {
      border-color: rgba(223, 67, 47, 0.22);
      background: linear-gradient(145deg, var(--otziv-white) 0%, var(--otziv-tone-correction-surface) 100%);
    }

    .invoice-actions,
    .order-actions {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.45rem;
      padding: 0.65rem;
    }

    .invoice-actions button,
    .order-actions button {
      min-height: 2.15rem;
      border: 1px solid rgba(103, 116, 131, 0.2);
      border-radius: 999px;
      color: var(--otziv-dark);
      background: var(--otziv-white);
      font: 900 0.72rem/1 var(--otziv-card-title-font);
    }

    .invoice-actions .success,
    .order-actions button:not(.danger):nth-child(2) {
      color: var(--otziv-success);
      background: var(--otziv-tone-success-surface);
    }

    .invoice-actions .danger,
    .order-actions .danger {
      color: var(--otziv-danger);
      background: var(--otziv-tone-correction-surface);
    }

    .invoice-actions button:disabled,
    .order-actions button:disabled {
      opacity: 0.48;
    }

    .return-orders {
      display: grid;
      gap: 0.45rem;
      padding: 0.65rem;
    }

    .return-orders-toggle,
    .return-orders-list button {
      min-height: 2.15rem;
      padding: 0 0.9rem;
      border: 1px solid rgba(103, 116, 131, 0.2);
      border-radius: 999px;
      color: var(--otziv-dark);
      background: var(--otziv-white);
      font: 900 0.72rem/1 var(--otziv-card-title-font);
    }

    .return-orders-toggle:disabled,
    .return-orders-list button:disabled {
      opacity: 0.48;
    }

    .return-orders-list {
      display: grid;
      gap: 0.4rem;
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .return-orders-list li {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      align-items: center;
      gap: 0.5rem;
      padding: 0.55rem 0.65rem;
      border: 1px solid rgba(103, 116, 131, 0.18);
      border-radius: 0.9rem;
      background: var(--otziv-white);
    }

    .return-orders-list li span {
      display: grid;
      gap: 0.15rem;
      min-width: 0;
    }

    .return-orders-list strong {
      color: var(--otziv-dark);
      font: 900 0.78rem/1.2 var(--otziv-card-title-font);
      overflow-wrap: anywhere;
    }

    .return-orders-list small {
      color: var(--otziv-info);
      font: 800 0.66rem/1.25 var(--otziv-card-title-font);
      overflow-wrap: anywhere;
    }

    .return-orders-list button {
      color: var(--otziv-success);
      background: var(--otziv-tone-success-surface);
    }

    .invoice-action-hint {
      grid-column: 1 / -1;
      margin: 0.05rem 0 0;
      color: var(--otziv-info);
      font: 800 0.66rem/1.25 var(--otziv-card-title-font);
      text-align: center;
    }

    .invoice-timeline {
      display: grid;
      gap: 0.36rem;
      padding: 0.72rem;
    }

    .invoice-timeline .error-line {
      color: var(--otziv-danger);
    }

    .payment-evidence {
      display: grid;
      gap: 0.48rem;
      padding: 0.72rem;
    }

    .payment-evidence header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.5rem;
      color: var(--otziv-dark);
      font: 1000 0.78rem/1.2 var(--otziv-card-title-font);
    }

    .payment-evidence article {
      display: grid;
      gap: 0.22rem;
      border: 1px solid rgba(103, 116, 131, 0.14);
      border-radius: 0.72rem;
      padding: 0.58rem;
      color: var(--otziv-info);
      background: var(--otziv-field-background);
      font: 800 0.65rem/1.3 var(--otziv-card-title-font);
      overflow-wrap: anywhere;
    }

    .payment-evidence article > strong {
      color: var(--otziv-dark);
      font-size: 0.72rem;
    }

    .payment-check-guide {
      display: grid;
      gap: 0.38rem;
      margin: 0;
      padding: 0.62rem 0.62rem 0.62rem 1.75rem;
      border-radius: 0.72rem;
      color: var(--otziv-dark);
      background: var(--otziv-tone-warning-surface);
      font: 800 0.66rem/1.35 var(--otziv-card-title-font);
    }

    .payment-evidence-empty {
      margin: 0;
      color: var(--otziv-danger);
      font: 900 0.66rem/1.35 var(--otziv-card-title-font);
    }

    .invoice-orders {
      display: grid;
      gap: 0.55rem;
      padding: 0.7rem;
    }

    .invoice-orders header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      color: var(--otziv-dark);
      font: 1000 0.9rem/1 var(--otziv-card-title-font);
    }

    .invoice-order-card {
      display: grid;
      gap: 0.48rem;
      border: 1px solid rgba(103, 116, 131, 0.14);
      border-radius: 0.8rem;
      padding: 0.65rem;
      background: var(--otziv-white);
    }

    .invoice-orders strong,
    .invoice-orders b {
      color: var(--otziv-dark);
      font: 1000 0.86rem/1.15 var(--otziv-card-title-font);
    }

    .order-money,
    .order-flags {
      display: flex;
      flex-wrap: wrap;
      gap: 0.36rem;
    }

    .order-money span,
    .order-money b,
    .order-flags span {
      display: inline-flex;
      min-height: 1.7rem;
      align-items: center;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 999px;
      padding: 0 0.55rem;
      background: var(--otziv-field-background);
      font: 900 0.68rem/1 var(--otziv-card-title-font);
    }

    .order-actions {
      grid-template-columns: repeat(3, minmax(0, 1fr));
      padding: 0;
    }

    .invoice-order-card app-mobile-order-card {
      display: block;
      min-height: 31rem;
    }

    :host-context(body.otziv-dark-theme) .state-card,
    :host-context(body.otziv-dark-theme) .invoice-hero,
    :host-context(body.otziv-dark-theme) .invoice-stats,
    :host-context(body.otziv-dark-theme) .invoice-mode-card,
    :host-context(body.otziv-dark-theme) .invoice-actions,
    :host-context(body.otziv-dark-theme) .invoice-timeline,
    :host-context(body.otziv-dark-theme) .invoice-orders {
      border-color: rgba(163, 189, 204, 0.18);
      background: linear-gradient(155deg, rgba(32, 37, 40, 0.98) 0%, rgba(24, 29, 33, 0.98) 100%);
      box-shadow: none;
    }

    :host-context(body.otziv-dark-theme) .invoice-stats article,
    :host-context(body.otziv-dark-theme) .invoice-order-card {
      border-color: rgba(163, 189, 204, 0.16);
      background: linear-gradient(145deg, rgba(37, 43, 47, 0.98) 0%, rgba(27, 32, 36, 0.98) 100%);
    }

    :host-context(body.otziv-dark-theme) .invoice-mode-card--paper {
      border-color: rgba(255, 128, 93, 0.28);
      background: linear-gradient(145deg, rgba(60, 35, 34, 0.96) 0%, rgba(31, 29, 31, 0.98) 100%);
    }

    :host-context(body.otziv-dark-theme) .invoice-actions button,
    :host-context(body.otziv-dark-theme) .order-actions button {
      border-color: rgba(163, 189, 204, 0.2);
      background: rgba(21, 26, 30, 0.86);
    }

    :host-context(body.otziv-dark-theme) .invoice-actions .success,
    :host-context(body.otziv-dark-theme) .order-actions button:not(.danger):nth-child(2) {
      background: rgba(74, 198, 177, 0.13);
    }

    :host-context(body.otziv-dark-theme) .invoice-actions .danger,
    :host-context(body.otziv-dark-theme) .order-actions .danger {
      background: rgba(255, 91, 143, 0.12);
    }
  `]
})
export class CommonBillingPage implements OnInit, OnDestroy {
  readonly deliveryStatusText = deliveryOperationMessage;
  /** The text shown while the client message is still in the queue: a wait, not an error. */
  readonly deliveryWait = commonInvoiceDeliveryWait;
  private readonly deliveryWatcher = new DeliveryStatusWatcher();
  private readonly commonBillingApi = inject(CommonBillingApi);
  private readonly orderPaymentApi = inject(OrderPaymentApi);
  private readonly recipientPickerFlow = inject(MobilePaymentRecipientPickerFlowService);
  /** Closes the «Выдать номер карты» window when this invoice screen is left. */
  private recipientPickerLeave?: AbortController;
  private readonly toast = inject(ToastController);
  readonly paymentInitNoPaymentActionLabel = COMMON_INVOICE_NO_PAYMENT_ACTION_LABEL;
  readonly paymentInitNoPaymentActionHint = commonInvoiceNoPaymentActionHint();
  readonly paymentInitInstructions = commonInvoicePaymentInitInstructions();
  private readonly routeGuard = new RouteEpochGuard();
  private routeSubscription?: Subscription;
  private readRun = 0;
  readonly invoiceId = signal<number | null>(null);
  readonly details = signal<CommonInvoiceDetailsResponse | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly mutating = signal<string | null>(null);
  readonly copiedKey = signal<string | null>(null);
  readonly summary = computed(() => this.details()?.summary ?? null);
  readonly orders = computed(() => this.details()?.orders ?? []);
  readonly manualAttributionRequired = signal<boolean | null>(null);
  readonly canReturnOrders = computed(() => canReturnOrdersToCommonInvoice(this.summary()));
  readonly returnOrdersOpen = signal(false);
  readonly returnableOrders = signal<CommonInvoiceReturnableOrdersResponse | null>(null);
  readonly returnOrdersLoading = signal(false);
  readonly returnOrdersError = signal('');
  readonly returnOrderRefusal = signal('');
  readonly returnOrdersIntro = RETURN_ORDERS_INTRO;
  readonly returnOrdersEmpty = RETURN_ORDERS_EMPTY;
  readonly returnOrdersFixed = RETURN_ORDERS_FIXED;
  readonly returnOrderLabel = returnOrderLabel;
  readonly orderCards = computed(() => this.details()?.orderCards ?? []);
  readonly reviewApprovalCount = computed(() => this.orders()
    .filter(order => order.orderStatus === 'В проверку' || order.orderStatus === 'На проверке')
    .length);
  readonly paymentEvidence = computed(() => {
    const invoice = this.summary();
    if (!invoice) {
      return [];
    }
    const policy = this.attentionPolicy(invoice);
    if (!policy.paymentInitCheck && !policy.migrationQuarantine) {
      return [];
    }
    return commonInvoicePaymentEvidence(
      invoice,
      this.details()?.paymentRefs,
      policy.migrationQuarantine
    );
  });
  readonly paymentInitCheckReady = computed(() => !this.loading() && commonInvoicePaymentEvidenceSnapshot(
    this.details(),
    this.invoiceId()
  ) !== null);

  constructor(
    private readonly auth: AuthService,
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly confirm: MobileConfirmService,
    private readonly commonManualPaymentFlow: MobileCommonManualPaymentFlowService
  ) {}

  ngOnInit(): void {
    this.routeSubscription = this.route.paramMap.subscribe((params) => {
      const value = Number(params.get('invoiceId') || 0);
      const invoiceId = Number.isSafeInteger(value) && value > 0 ? value : null;
      if (!this.routeGuard.change(invoiceId == null ? null : String(invoiceId))) {
        return;
      }
      this.closeRecipientPicker();
      this.readRun += 1;
      this.invoiceId.set(invoiceId);
      this.details.set(null);
      this.error.set(null);
      this.loading.set(false);
      this.mutating.set(null);
      this.manualAttributionRequired.set(null);
      void this.load();
    });
  }

  ngOnDestroy(): void {
    this.closeRecipientPicker();
    this.deliveryWatcher.cancel();
    this.readRun += 1;
    this.routeGuard.destroy();
    this.routeSubscription?.unsubscribe();
  }

  ionViewWillLeave(): void {
    this.closeRecipientPicker();
  }

  async refresh(event: RefresherCustomEvent): Promise<void> {
    await this.load();
    event.target.complete();
  }

  reload(): void {
    void this.load();
  }

  async runInvoiceAction(action: InvoiceAction): Promise<void> {
    const invoiceId = this.invoiceId();
    const ticket = this.routeGuard.capture();
    if (!invoiceId || !ticket || this.mutating()) {
      return;
    }

    const invoice = this.summary();
    if (invoice) {
      const hint = this.invoiceActionHint(invoice, action);
      if (hint) {
        this.error.set(hint);
        return;
      }
    }

    const paymentSnapshot = action === 'payment-init-check'
      ? commonInvoicePaymentEvidenceSnapshot(this.details(), invoiceId)
      : null;
    if (action === 'payment-init-check' && (this.loading() || !paymentSnapshot)) {
      this.error.set('Полные платежные реквизиты не загружены. Обновите карточку счета и повторите проверку.');
      return;
    }
    if (action === 'paid') {
      if (invoice?.invoicePaymentMode === 'OWNER_PAPER_INVOICE') {
        const evidence = await this.requestManualPaymentEvidence(
          'Подтверждение оплаты бумажного счёта',
          `Подтвердите поступление денег по бумажному счёту №${invoiceId}. Заказы будут закрыты без начисления получателю реквизитов.`
        );
        if (!evidence || !this.routeGuard.accepts(ticket)) {
          return;
        }
        await this.runOrderMutation(
          ticket,
          'paper-invoice-paid',
          () => this.commonBillingApi.markCommonInvoicePaperInvoicePaid(invoiceId, evidence)
        );
        return;
      }
      const required = this.manualAttributionRequired();
      if (required == null) {
        this.error.set('Режим учёта получателей не загружен. Обновите карточку счёта.');
        return;
      }
      if (required) {
        await this.runAttributedPayment(ticket, invoiceId, this.isBankPaymentRoute(invoice?.paymentRouteType) ? 'TBANK_FALLBACK' : 'STANDARD');
        return;
      }
    }

    const manualPaymentEvidence = action === 'paid'
      ? await this.requestManualPaymentEvidence(
        'Подтверждение поступления денег',
        `Отметьте общий счёт №${invoiceId} оплаченным только после проверки фактического поступления денег получателю.`
      )
      : null;
    const confirmed = action === 'paid'
      ? manualPaymentEvidence !== null
      : await this.confirm.confirm({
        message: paymentSnapshot
          ? this.paymentInitCheckConfirmation(paymentSnapshot.evidence)
          : this.invoiceActionConfirm(action),
        danger: action === 'ban' || action === 'unpaid'
      });
    if (!confirmed || !this.routeGuard.accepts(ticket)) {
      return;
    }

    this.readRun += 1;
    this.mutating.set(action);
    try {
      const details = await firstValueFrom(this.invoiceActionRequest(
        invoiceId,
        action,
        paymentSnapshot?.evidenceToken ?? null,
        manualPaymentEvidence
      ));
      if (!this.routeGuard.accepts(ticket)) {
        return;
      }
      this.details.set(details);
      this.deliveryWatcher.watch(details, () => firstValueFrom(this.commonBillingApi.getCommonInvoice(invoiceId)),
        updated => this.details.set(updated), () => this.routeGuard.accepts(ticket));
      // A message still in the queue is a wait the invoice card already shows, not an error of this action.
      this.error.set((action === 'send' || action === 'remind') && !details.delivery
        && !commonInvoiceDeliveryWait(details.summary.lastError)
        ? commonInvoiceDeliveryWarning(details.summary.lastError) : null);
    } catch (error) {
      if (this.routeGuard.accepts(ticket)) {
        this.error.set(this.errorMessage(error, 'Не удалось обновить общий счет.'));
      }
    } finally {
      if (this.routeGuard.accepts(ticket) && this.mutating() === action) {
        this.mutating.set(null);
      }
    }
  }

  async markOrderPaid(order: CommonInvoiceOrderResponse): Promise<void> {
    const invoiceId = this.invoiceId();
    const ticket = this.routeGuard.capture();
    if (!invoiceId || !ticket || this.mutating()) {
      return;
    }
    if (this.manualAttributionRequired() !== false) {
      this.error.set('В новом режиме фиксируйте ручную оплату целиком через кнопку «Оплачен» и список фактических получателей.');
      return;
    }

    const evidence = await this.requestManualPaymentEvidence(
      'Подтверждение поступления денег',
      `Отметьте заказ #${order.orderId} оплаченным внутри общего счёта только после проверки фактического поступления денег.`
    );
    if (!evidence || !this.routeGuard.accepts(ticket)) {
      return;
    }

    await this.runOrderMutation(
      ticket,
      `paid-${order.orderId}`,
      () => this.commonBillingApi.markCommonInvoiceOrderPaid(invoiceId, order.orderId, evidence)
    );
  }

  async reportManualCardPayment(): Promise<void> {
    const invoice = this.summary();
    const invoiceId = this.invoiceId();
    const ticket = this.routeGuard.capture();
    if (!invoice || !invoiceId || !ticket || this.mutating()
      || !this.attentionPolicy(invoice).standaloneRouteConflict) {
      return;
    }
    const required = this.manualAttributionRequired();
    if (required == null) {
      this.error.set('Режим учёта получателей не загружен. Обновите карточку счёта.');
      return;
    }
    if (required) {
      await this.runAttributedPayment(ticket, invoiceId, 'TBANK_FALLBACK');
      return;
    }
    const reason = window.prompt('Клиент оплатил общий счёт переводом на карту. Укажите краткую причину:')?.trim();
    if (!reason) {
      return;
    }
    await this.runOrderMutation(
      ticket,
      'manual-card-paid',
      () => this.commonBillingApi.reportCommonInvoiceManualCardPayment(invoiceId, reason)
    );
  }

  async changePaymentRoute(targetOverride?: CommonInvoicePaymentRouteChangeTarget): Promise<void> {
    const invoice = this.summary();
    const invoiceId = this.invoiceId();
    const ticket = this.routeGuard.capture();
    if (!invoice || !invoiceId || !ticket || this.mutating()
      || invoice.status === 'NEEDS_ATTENTION'
      || invoice.invoicePaymentMode === 'OWNER_PAPER_INVOICE') {
      return;
    }

    this.readRun += 1;
    this.mutating.set('load-payment-route-change');
    try {
      const context = await firstValueFrom(this.commonBillingApi.getCommonInvoicePaymentRouteChangeContext(invoiceId));
      if (!this.routeGuard.accepts(ticket)) {
        return;
      }
      if (!context.canChange && !((targetOverride === 'DIFFERENT_RECIPIENT' || targetOverride === 'EMPLOYEE_REQUISITES') && context.bankRecipientChangeAvailable)) {
        this.error.set(context.blockReason || 'Способ оплаты нельзя изменить без ручной сверки.');
        return;
      }
      const target: CommonInvoicePaymentRouteChangeTarget = targetOverride ?? (context.currentTarget === 'OWNER_TBANK'
        ? 'EMPLOYEE_REQUISITES'
        : 'OWNER_TBANK');
      if (target === 'EMPLOYEE_REQUISITES' || target === 'DIFFERENT_RECIPIENT') {
        // A card number is issued from the window: the system's own choice or a person the manager names.
        this.mutating.set('change-payment-route');
        await this.issueCardNumber(ticket, invoiceId, invoice, target, context);
        return;
      }
      const recipient = context.currentRecipient?.trim()
        ? ` Получатель сейчас: ${context.currentRecipient}.`
        : '';
      const confirmed = await this.confirm.confirm({
        title: 'Смена способа оплаты',
        message: `Переключить общий счёт на банковскую ссылку владельца?${recipient} Старый резерв будет освобождён, новый маршрут создан заново, клиенту уйдёт обновлённое сообщение. Продолжайте только если клиент ещё не платил.`,
        danger: true
      });
      if (!confirmed || !this.routeGuard.accepts(ticket)) {
        return;
      }
      this.mutating.set('change-payment-route');
      const details = await firstValueFrom(this.commonBillingApi.changeCommonInvoicePaymentRoute(
        invoiceId,
        target,
        context.paymentEvidenceToken
      ));
      if (this.routeGuard.accepts(ticket)) {
        this.details.set(details);
        this.error.set(null);
      }
    } catch (error) {
      if (this.routeGuard.accepts(ticket)) {
        const message = this.errorMessage(error, 'Не удалось изменить способ оплаты.');
        if (error instanceof HttpErrorResponse && error.status === 423) {
          this.error.set(null);
          const toast = await this.toast.create({ message, duration: 5000 });
          if (this.routeGuard.accepts(ticket)) await toast.present();
        } else {
          this.error.set(message);
        }
      }
    } finally {
      if (this.routeGuard.accepts(ticket)) {
        this.mutating.set(null);
      }
    }
  }

  /** The «Выдать номер карты» window: the people, the choice and what the server answered. */
  private async issueCardNumber(
    ticket: RouteEpochTicket,
    invoiceId: number,
    invoice: CommonInvoiceSummaryResponse,
    target: CommonInvoicePaymentRouteChangeTarget,
    context: CommonInvoicePaymentRouteChangeContextResponse
  ): Promise<void> {
    const before = this.paymentRouteLabel(invoice);
    let after = before;
    const leave = new AbortController();
    this.recipientPickerLeave = leave;
    try {
      const outcome = await this.recipientPickerFlow.open({
        heading: `Общий счёт №${invoiceId}`,
        amountKopecks: Math.max(0, (invoice.amountKopecks ?? 0) - (invoice.paidKopecks ?? 0)),
        currentRecipient: context.currentRecipient?.trim() ?? '',
        autoHint: mobilePaymentRecipientAutoHint(target),
        autoHintWithOwnerCard: mobilePaymentRecipientAutoHint(target, true),
        signal: leave.signal,
        loadRecipients: async () => {
          try {
            const response = await firstValueFrom(this.commonBillingApi.getCommonInvoicePaymentRouteRecipients(invoiceId));
            return { recipients: mobilePaymentRecipientOptions(response.recipients), ownerCard: mobilePaymentRecipientOwnerCard(response.ownerCard) };
          } catch (error) {
            throw new Error(this.errorMessage(error, 'Не удалось загрузить сотрудников.'));
          }
        },
        issue: async (choice) => {
          const answer = await this.submitRecipientChoice(ticket, invoiceId, target, context.paymentEvidenceToken, choice);
          if (answer.details) {
            after = this.paymentRouteLabel(answer.details.summary);
          }
          return answer.outcome;
        }
      });
      if (!outcome || !this.routeGuard.accepts(ticket)) {
        return;
      }
      const message = outcome.status === 'accepted'
        ? outcome.message
        : after === before
          ? 'Запрос принят. Получатель сменится, когда банк подтвердит закрытие прежней ссылки.'
          : `Получатель изменён: ${before} → ${after}`;
      const toast = await this.toast.create({ message, duration: 5000 });
      if (this.routeGuard.accepts(ticket)) await toast.present();
    } finally {
      if (this.recipientPickerLeave === leave) {
        this.recipientPickerLeave = undefined;
      }
    }
  }

  /** Issues the card number: the system's own choice, or the person the manager named. */
  private async submitRecipientChoice(
    ticket: RouteEpochTicket,
    invoiceId: number,
    target: CommonInvoicePaymentRouteChangeTarget,
    paymentEvidenceToken: string,
    choice: MobilePaymentRecipientChoice
  ): Promise<{ outcome: MobilePaymentRecipientIssueOutcome; details?: CommonInvoiceDetailsResponse }> {
    if (!this.routeGuard.accepts(ticket)) {
      return { outcome: { status: 'left' } };
    }
    try {
      const details = await firstValueFrom(this.commonBillingApi.changeCommonInvoicePaymentRoute(
        invoiceId,
        target,
        paymentEvidenceToken,
        undefined,
        choice.profileId
      ));
      if (!this.routeGuard.accepts(ticket)) {
        return { outcome: { status: 'left' } };
      }
      this.details.set(details);
      this.error.set(null);
      return { outcome: { status: 'issued' }, details };
    } catch (error) {
      if (!this.routeGuard.accepts(ticket)) {
        return { outcome: { status: 'left' } };
      }
      if (error instanceof HttpErrorResponse && error.status === 423) {
        // The bank has to close the old link first; the request is saved and will finish by itself.
        this.error.set(null);
        return { outcome: {
          status: 'accepted',
          message: this.errorMessage(error, 'Запрос принят. Получатель сменится, когда банк подтвердит закрытие прежней ссылки.')
        } };
      }
      // The window stays open with the refusal, so another person can be chosen at once.
      return { outcome: { status: 'refused', message: this.errorMessage(error, 'Не удалось выдать номер карты.') } };
    }
  }

  private closeRecipientPicker(): void {
    this.recipientPickerLeave?.abort();
    this.recipientPickerLeave = undefined;
  }

  async reissueOwnerBankRoute(): Promise<void> {
    const invoice = this.summary();
    const invoiceId = this.invoiceId();
    const ticket = this.routeGuard.capture();
    if (!invoice || !invoiceId || !ticket || this.mutating()
      || !this.isBankPaymentRoute(invoice.paymentRouteType)
      || invoice.status === 'NEEDS_ATTENTION'
      || invoice.invoicePaymentMode === 'OWNER_PAPER_INVOICE') {
      return;
    }

    this.readRun += 1;
    this.mutating.set('load-owner-bank-reissue');
    try {
      const context = await firstValueFrom(this.commonBillingApi.getCommonInvoicePaymentRouteChangeContext(invoiceId));
      if (!this.routeGuard.accepts(ticket)) {
        return;
      }
      if (!context.canReissueOwnerBank) {
        this.error.set(context.ownerBankReissueBlockReason || 'Банковский профиль сейчас нельзя обновить.');
        return;
      }
      const provider = this.paymentRouteProviderLabel(context.ownerBankTargetProvider);
      const profile = context.ownerBankTargetPaymentProfileName?.trim();
      const target = profile ? `${provider} · ${profile}` : provider;
      const confirmed = await this.confirm.confirm({
        title: 'Обновить банк общего счёта',
        message: `Переиздать способ оплаты на «${target}»? Это разрешено только пока клиент не начинал оплату. Старая ссылка будет закрыта, а клиенту уйдёт новый способ оплаты.`,
        confirmText: 'Переиздать',
        danger: true
      });
      if (!confirmed || !this.routeGuard.accepts(ticket)) {
        return;
      }
      this.mutating.set('reissue-owner-bank-route');
      const details = await firstValueFrom(this.commonBillingApi.changeCommonInvoicePaymentRoute(
        invoiceId,
        'OWNER_BANK_REISSUE',
        context.paymentEvidenceToken,
        context.ownerBankTargetPaymentProfileId
      ));
      if (this.routeGuard.accepts(ticket)) {
        this.details.set(details);
        this.error.set(null);
      }
    } catch (error) {
      if (this.routeGuard.accepts(ticket)) {
        this.error.set(this.errorMessage(error, 'Не удалось обновить банк общего счёта.'));
      }
    } finally {
      if (this.routeGuard.accepts(ticket)) {
        this.mutating.set(null);
      }
    }
  }

  async changePaperInvoiceMode(legacyPaper = false): Promise<void> {
    if (!legacyPaper && this.invoiceId()) { openPaperInvoiceDialog({kind: 'COMMON_INVOICE', id: this.invoiceId()!}, () => { void this.changePaperInvoiceMode(true); }); return; }
    const invoice = this.summary();
    const invoiceId = this.invoiceId();
    const ticket = this.routeGuard.capture();
    if (!invoice || !invoiceId || !ticket || this.mutating() || !this.canManagePaperInvoices()) {
      return;
    }
    const paperEnabled = invoice.invoicePaymentMode === 'OWNER_PAPER_INVOICE';
    const confirmed = !paperEnabled || await this.confirm.confirm({
      title: 'Вернуть авто-распределение',
      message: 'Если бумажный счёт уже отправлен, подтвердите его отзыв и отсутствие поступления по выписке.',
      danger: true
    });
    if (!confirmed || !this.routeGuard.accepts(ticket)) {
      return;
    }
    await this.runOrderMutation(
      ticket,
      'change-paper-invoice-mode',
      () => this.commonBillingApi.changeCommonInvoicePaymentMode(
        invoiceId,
        paperEnabled ? 'AUTO_ROUTING' : 'OWNER_PAPER_INVOICE', !!invoice.paperInvoiceIssuedAt
      )
    );
  }

  async markPaperInvoiceIssued(): Promise<void> {
    const invoice = this.summary();
    const invoiceId = this.invoiceId();
    const ticket = this.routeGuard.capture();
    if (!invoice || !invoiceId || !ticket || this.mutating() || !this.canManagePaperInvoices()
      || invoice.invoicePaymentMode !== 'OWNER_PAPER_INVOICE' || invoice.paperInvoiceIssuedAt) {
      return;
    }
    const confirmed = await this.confirm.confirm({
      title: 'Бумажный счёт отправлен',
      message: 'Подтвердить, что документ уже передан клиенту? После отметки начнутся автоматические напоминания.'
    });
    if (!confirmed || !this.routeGuard.accepts(ticket)) {
      return;
    }
    await this.runOrderMutation(
      ticket,
      'paper-invoice-issued',
      () => this.commonBillingApi.markCommonInvoicePaperInvoiceIssued(invoiceId)
    );
  }

  async confirmContractorPaymentSource(): Promise<void> {
    const invoice = this.summary();
    const invoiceId = this.invoiceId();
    const ticket = this.routeGuard.capture();
    if (!invoice || !invoiceId || !ticket || this.mutating() || !this.canConfirmContractorSource(invoice)) {
      return;
    }
    const confirmed = await this.confirm.confirm({
      title: 'Сверка поступления',
      message: 'Подтвердите только после проверки выписки именно того сотрудника, чьи реквизиты указаны в этом счёте.'
    });
    if (!confirmed || !this.routeGuard.accepts(ticket)) {
      return;
    }
    const rawAmount = window.prompt('Подтверждённая сумма по этому источнику накопительным итогом, ₽:');
    if (rawAmount === null) {
      return;
    }
    const confirmedTotalKopecks = Math.round(Number(rawAmount.replace(',', '.')) * 100);
    if (!Number.isSafeInteger(confirmedTotalKopecks) || confirmedTotalKopecks <= 0) {
      this.error.set('Укажите корректную положительную сумму поступления.');
      return;
    }
    const reason = window.prompt('Основание сверки:', 'Поступление найдено в выписке получателя')?.trim() ?? '';
    if (!reason) {
      this.error.set('Укажите основание сверки поступления.');
      return;
    }
    await this.runOrderMutation(
      ticket,
      'contractor-source-confirmation',
      () => this.commonBillingApi.confirmCommonInvoiceContractorSource(invoiceId, {
        recipientStatementChecked: true,
        paymentReceived: true,
        confirmedTotalKopecks,
        reason
      })
    );
  }

  async archiveInvoice(): Promise<void> {
    const invoice = this.summary();
    const invoiceId = this.invoiceId();
    const ticket = this.routeGuard.capture();
    if (!invoice || !invoiceId || !ticket || this.mutating() || !this.canArchiveInvoice(invoice)) {
      return;
    }
    this.readRun += 1;
    this.mutating.set('archive-preview');
    try {
      const preview = await firstValueFrom(this.commonBillingApi.getCommonInvoiceArchivePreview(invoiceId));
      if (!this.routeGuard.accepts(ticket)) {
        return;
      }
      if (!preview.allowed) {
        this.error.set(preview.blockers.join('; ') || 'Общий счёт сейчас нельзя архивировать.');
        return;
      }
      const confirmed = await this.confirm.confirm({
        title: 'Архив общего счёта',
        message: `Архивировать общий счёт №${invoiceId} и ${preview.totalOrders} заказов внутри?`,
        danger: true
      });
      if (!confirmed || !this.routeGuard.accepts(ticket)) {
        return;
      }
      this.mutating.set('archive-invoice');
      const details = await firstValueFrom(this.commonBillingApi.archiveCommonInvoice(invoiceId));
      if (this.routeGuard.accepts(ticket)) {
        this.details.set(details);
        this.error.set(null);
      }
    } catch (error) {
      if (this.routeGuard.accepts(ticket)) {
        this.error.set(this.errorMessage(error, 'Не удалось архивировать общий счёт.'));
      }
    } finally {
      if (this.routeGuard.accepts(ticket)) {
        this.mutating.set(null);
      }
    }
  }

  /** Opens or hides the list of orders that may be returned to this collecting invoice. */
  toggleReturnOrders(): void {
    if (this.returnOrdersOpen()) {
      this.closeReturnOrders();
      return;
    }
    this.returnOrdersOpen.set(true);
    this.returnOrderRefusal.set('');
    void this.loadReturnableOrders();
  }

  async loadReturnableOrders(): Promise<void> {
    const invoiceId = this.invoiceId();
    const ticket = this.routeGuard.capture();
    if (!invoiceId || !ticket) {
      return;
    }
    this.returnOrdersLoading.set(true);
    this.returnOrdersError.set('');
    try {
      const answer = await firstValueFrom(this.commonBillingApi.getCommonInvoiceReturnableOrders(invoiceId));
      if (this.routeGuard.accepts(ticket) && this.invoiceId() === invoiceId) {
        this.returnableOrders.set(answer);
      }
    } catch (error) {
      if (this.routeGuard.accepts(ticket)) {
        this.returnableOrders.set(null);
        this.returnOrdersError.set(this.errorMessage(error, 'Не удалось загрузить заказы для возврата.'));
      }
    } finally {
      if (this.routeGuard.accepts(ticket)) {
        this.returnOrdersLoading.set(false);
      }
    }
  }

  /**
   * Returns the order after the manager confirmed what will happen. Success hides the list and shows the
   * invoice with the order; a refusal stays in the list and says that nothing changed and what to do.
   */
  async returnOrder(order: CommonInvoiceReturnableOrder): Promise<void> {
    const invoiceId = this.invoiceId();
    const ticket = this.routeGuard.capture();
    if (!invoiceId || !ticket || this.mutating()) {
      return;
    }
    const confirmed = await this.confirm.confirm({ message: returnOrderConsequence(order, invoiceId) });
    if (!confirmed || !this.routeGuard.accepts(ticket)) {
      return;
    }
    this.returnOrderRefusal.set('');
    this.readRun += 1;
    this.mutating.set(`return-order-${order.orderId}`);
    try {
      const details = await firstValueFrom(this.commonBillingApi.returnCommonInvoiceOrder(invoiceId, order.orderId));
      if (!this.routeGuard.accepts(ticket)) {
        return;
      }
      this.details.set(details);
      this.error.set(null);
      this.closeReturnOrders();
    } catch (error) {
      if (this.routeGuard.accepts(ticket)) {
        this.returnOrderRefusal.set(returnOrderRefusal(this.errorMessage(error, '')));
        // A refusal often means the page is stale: show the list as it is now.
        void this.loadReturnableOrders();
      }
    } finally {
      if (this.routeGuard.accepts(ticket)) {
        this.mutating.set(null);
      }
    }
  }

  private closeReturnOrders(): void {
    this.returnOrdersOpen.set(false);
    this.returnableOrders.set(null);
    this.returnOrdersLoading.set(false);
    this.returnOrdersError.set('');
    this.returnOrderRefusal.set('');
  }

  async detachOrder(order: CommonInvoiceOrderResponse): Promise<void> {
    const invoiceId = this.invoiceId();
    const ticket = this.routeGuard.capture();
    if (!invoiceId || !ticket || this.mutating()) {
      return;
    }

    const confirmed = await this.confirm.confirm({
      message: `Отключить заказ #${order.orderId} от общего счета?`,
      danger: true
    });
    if (!confirmed || !this.routeGuard.accepts(ticket)) {
      return;
    }

    await this.runOrderMutation(ticket, `detach-${order.orderId}`, () => this.commonBillingApi.detachCommonInvoiceOrder(invoiceId, order.orderId));
  }

  async deleteInvoiceWithOrders(invoice: CommonInvoiceSummaryResponse): Promise<void> {
    const invoiceId = this.invoiceId();
    const ticket = this.routeGuard.capture();
    if (!invoiceId || !ticket || this.mutating() || !this.canDeleteInvoiceWithOrders(invoice)) {
      return;
    }

    const confirmed = await this.confirm.confirm({
      message: `Удалить общий счет #${invoice.id} и все связанные с ним заказы (${invoice.totalOrders})? Действие нельзя отменить.`,
      danger: true
    });
    if (!confirmed || !this.routeGuard.accepts(ticket)) {
      return;
    }

    this.readRun += 1;
    this.mutating.set(`delete-invoice-${invoice.id}`);
    try {
      await firstValueFrom(this.commonBillingApi.deleteCommonInvoiceWithOrders(invoice.id));
      if (!this.routeGuard.accepts(ticket)) {
        return;
      }
      this.details.set(null);
      this.error.set(null);
      void this.router.navigate(['/tabs/orders']);
    } catch (error) {
      if (this.routeGuard.accepts(ticket)) {
        this.error.set(this.errorMessage(error, 'Общий счет не удален.'));
      }
    } finally {
      if (this.routeGuard.accepts(ticket)) {
        this.mutating.set(null);
      }
    }
  }

  openOrder(order: CommonInvoiceOrderResponse): void {
    void this.router.navigate(['/tabs/orders', order.companyId, order.orderId]);
  }

  openOrderCard(order: OrderItem): void {
    if (!order.companyId || !order.id) {
      return;
    }
    void this.router.navigate(['/tabs/orders', order.companyId, order.id]);
  }

  async copyPublicUrl(invoice: CommonInvoiceSummaryResponse): Promise<void> {
    const value = (invoice.publicUrl ?? '').trim();
    if (!value) {
      return;
    }
    try {
      await navigator.clipboard.writeText(value);
      this.error.set(null);
    } catch {
      this.error.set('Не удалось скопировать ссылку общего счета.');
    }
  }

  async copyOrderPhone(order: OrderItem): Promise<void> {
    await this.copyText(normalizePhoneDigits(order.companyTelephone), `invoice-order-phone-${order.id}`, 'Не удалось скопировать телефон.');
  }

  async copyOrderText(order: OrderItem, kind: 'review' | 'payment'): Promise<void> {
    if (kind === 'review') {
      await this.copyText(
        orderReviewCopyText(order, this.orderReviewUrl(order)),
        `invoice-order-review-${order.id}`,
        'Не удалось скопировать текст проверки отзывов.'
      );
      return;
    }
    if (this.invoiceOrderForCard(order)?.ordinaryAfterPaperCancel) {
      try {
        const result=await firstValueFrom(this.orderPaymentApi.createManagerOrderPaymentLink(order.id));
        await this.copyText(result.copyText || result.url, `invoice-order-payment-${order.id}`, 'Не удалось скопировать реквизиты заказа.');
      } catch (error) { this.error.set(this.errorMessage(error,'Не удалось подготовить реквизиты заказа')); }
      return;
    }
    await this.copyText(this.summary()?.publicUrl ?? '', `invoice-order-payment-${order.id}`, 'Не удалось скопировать ссылку общего счета.');
  }

  invoiceOrderForCard(order: OrderItem): CommonInvoiceOrderResponse | null {
    return this.orders().find((item) => item.orderId === order.id) ?? null;
  }

  orderTitle(order: OrderItem): string {
    return [order.companyTitle || 'Без компании', order.filialTitle || 'Без филиала'].filter(Boolean).join(' - ');
  }

  orderPayableSum(order: OrderItem): number {
    return order.totalSumWithBadReviews ?? order.sum ?? 0;
  }

  orderMoney(value?: number): string {
    return `${new Intl.NumberFormat('ru-RU').format(value ?? 0)} руб.`;
  }

  orderPhone(order: OrderItem): string {
    return displayPhone(order.companyTelephone);
  }

  orderChatUrl(order: OrderItem): string {
    const digits = normalizePhoneDigits(order.companyTelephone);
    return (order.telegramBotInviteUrl ?? '').trim()
      || (order.maxBotInviteUrl ?? '').trim()
      || (order.companyUrlChat ?? '').trim()
      || (digits ? `tel:+${digits}` : '');
  }

  orderReviewUrl(order: OrderItem): string {
    const detailsId = (order.orderDetailsId ?? '').trim();
    if (!detailsId) {
      return '';
    }
    const origin = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1'
      ? 'https://o-ogo.ru'
      : window.location.origin;
    return new URL(`/${detailsId}`, origin).toString();
  }

  orderFilialUrl(order: OrderItem): string {
    return (order.filialUrl ?? '').trim() || phoneHref(order.companyTelephone);
  }

  orderCity(order: OrderItem): string {
    return (order.filialCity ?? '').trim() || 'Город не указан';
  }

  orderProgress(order: OrderItem): number {
    if (!order.amount || !order.counter) {
      return 0;
    }
    return Math.max(0, Math.min(100, Math.round((order.counter / order.amount) * 100)));
  }

  orderUnchangedDays(order: OrderItem): number {
    return Math.max(0, order.dayToChangeStatusAgo ?? 0);
  }

  isOrderUnchangedAlert(order: OrderItem): boolean {
    return this.orderUnchangedDays(order) >= 2;
  }

  workerLabel(order: OrderItem): string {
    const fio = (order.workerUserFio ?? '').trim();
    if (!fio) {
      return '-';
    }
    const [first, second] = fio.split(/\s+/);
    return second ? `${first} ${second.charAt(0)}.` : first;
  }

  orderTone(order: OrderItem): string {
    switch (order.status) {
      case 'Новый':
      case 'На проверке':
      case 'Напоминание':
        return 'lead-card--new';
      case 'Коррекция':
      case 'Публикация':
        return 'lead-card--send';
      case 'Опубликовано':
      case 'Оплачено':
        return 'lead-card--work';
      default:
        return '';
    }
  }

  kopecks(value?: number | null): string {
    const rubles = Math.round((value ?? 0) / 100);
    return `${new Intl.NumberFormat('ru-RU').format(rubles)} ₽`;
  }

  dateTime(value?: string | null): string {
    if (!value) {
      return '-';
    }
    return new Intl.DateTimeFormat('ru-RU', {
      day: '2-digit',
      month: '2-digit',
      hour: '2-digit',
      minute: '2-digit'
    }).format(new Date(value));
  }

  statusLabel(status?: string | null): string {
    switch (status) {
      case 'COLLECTING':
        return 'Собирается';
      case 'READY':
        return 'Готов к счету';
      case 'INVOICED':
        return 'Выставлен';
      case 'REMINDER':
        return 'Напоминание';
      case 'PARTIALLY_PAID':
        return 'Частично оплачен';
      case 'PAID':
        return 'Оплачен';
      case 'UNPAID':
        return 'Не оплачен';
      case 'BAN':
        return 'Бан';
      case 'NEEDS_ATTENTION':
        return 'Требует внимания';
      case 'DISABLED':
        return 'Отключен';
      default:
        return status || 'Общий счет';
    }
  }

  paymentRouteLabel(invoice: CommonInvoiceSummaryResponse): string {
    if (invoice.invoicePaymentMode === 'OWNER_PAPER_INVOICE') {
      return 'Бумажный счёт владельца';
    }
    const profile = invoice.paymentRouteProfileName?.trim();
    switch (invoice.paymentRouteType) {
      case 'BANK_LINK':
      case 'TBANK_LINK':
      case 'TOCHKA_LINK': {
        const provider = this.paymentRouteProviderLabel(invoice.paymentRouteProvider);
        return profile ? `${provider} · ${profile}` : `${provider} владельца`;
      }
      case 'MANUAL_EXTERNAL_LINK':
        return invoice.paymentRouteRecipient?.trim()
          ? `Внешняя ссылка · ${invoice.paymentRouteRecipient.trim()}`
          : invoice.paymentRouteManualTaskId
          ? `Внешняя ссылка · задание #${invoice.paymentRouteManualTaskId}`
          : 'Внешняя ссылка';
      case 'MANUAL_MOBILE_BANK':
        return invoice.paymentRouteRecipient?.trim()
          ? `Реквизиты · ${invoice.paymentRouteRecipient.trim()}`
          : invoice.paymentRouteManualTaskId
          ? `Реквизиты сотрудника · задание #${invoice.paymentRouteManualTaskId}`
          : (profile ? `Реквизиты сотрудника · ${profile}` : 'Реквизиты сотрудника');
      case 'MANAGER_TEXT':
        return 'Текст менеджера';
      default:
        return 'Маршрут не выбран';
    }
  }

  /**
   * One «Выдать номер карты» action replaces «реквизиты без ссылки» and «сменить получателя»:
   * from a link any transfer number fits, from a number the client wants another person.
   */
  cardNumberRouteTarget(routeType?: string | null): 'EMPLOYEE_REQUISITES' | 'DIFFERENT_RECIPIENT' {
    return this.isBankPaymentRoute(routeType) || routeType === 'MANUAL_EXTERNAL_LINK' ? 'EMPLOYEE_REQUISITES' : 'DIFFERENT_RECIPIENT';
  }

  /** What «Выдать номер карты» is for and that it opens the window, said under the buttons. */
  cardNumberRouteHint(routeType?: string | null): string {
    return (this.cardNumberRouteTarget(routeType) === 'EMPLOYEE_REQUISITES'
      ? '«Выдать номер карты» — клиент не хочет платить по ссылке. '
      : '«Выдать номер карты» — клиент просит счёт на другого человека. ')
      + 'Откроется окно: система подберёт получателя сама, или выберите сотрудника — можно и сверх резерва.';
  }

  isBankPaymentRoute(routeType?: string | null): boolean {
    const route = (routeType ?? '').trim().toUpperCase();
    return route === 'BANK_LINK' || route === 'TBANK_LINK' || route === 'TOCHKA_LINK';
  }

  private paymentRouteProviderLabel(provider?: string | null): string {
    switch ((provider ?? '').trim().toUpperCase()) {
      case 'TOCHKA':
        return 'Точка';
      case 'T_BANK':
      case 'TBANK':
        return 'Т-Банк';
      default:
        return 'Банк';
    }
  }

  canManagePaperInvoices(): boolean {
    return this.auth.hasAnyRealmRole(['ADMIN', 'OWNER', 'MANAGER']);
  }

  paperModeSwitchNeedsRetry(invoice: CommonInvoiceSummaryResponse): boolean {
    const error = (invoice.lastError ?? '').trim().toLowerCase();
    return error.startsWith('paper_invoice_mode_switch_retry:')
      || error.startsWith('paper_invoice_mode_switch_in_progress:')
      || error.startsWith('paper_invoice_mode_switch_state_changed:');
  }

  canSendInvoice(invoice: CommonInvoiceSummaryResponse): boolean {
    return this.allOrdersReady(invoice) && ['READY', 'INVOICED', 'REMINDER', 'PARTIALLY_PAID'].includes(invoice.status);
  }

  canRemindInvoice(invoice: CommonInvoiceSummaryResponse): boolean {
    return this.allOrdersReady(invoice)
      && (invoice.invoicePaymentMode !== 'OWNER_PAPER_INVOICE' || Boolean(invoice.paperInvoiceIssuedAt))
      && ['INVOICED', 'REMINDER', 'PARTIALLY_PAID'].includes(invoice.status);
  }

  canMarkInvoicePaid(invoice: CommonInvoiceSummaryResponse): boolean {
    return ['READY', 'INVOICED', 'REMINDER', 'PARTIALLY_PAID', 'UNPAID'].includes(invoice.status)
      || (invoice.status === 'COLLECTING' && this.allOrdersReady(invoice));
  }

  /** The autoresponder sets «Не оплачен»; the manual button stays with the owner and administrators. */
  manualUnpaidAllowed(): boolean {
    return this.auth.hasAnyRealmRole(['ADMIN', 'OWNER']);
  }

  canMarkInvoiceUnpaid(invoice: CommonInvoiceSummaryResponse): boolean {
    return this.manualUnpaidAllowed()
      && invoice.totalOrders > 0
      && invoice.paidOrders < invoice.totalOrders
      && ['INVOICED', 'REMINDER', 'PARTIALLY_PAID'].includes(invoice.status);
  }

  canMarkInvoiceBan(invoice: CommonInvoiceSummaryResponse): boolean {
    return invoice.status === 'UNPAID';
  }

  canConfirmContractorSource(invoice: CommonInvoiceSummaryResponse): boolean {
    return invoice.contractorPaymentRoute
      && this.auth.hasAnyRealmRole(['ADMIN', 'OWNER'])
      && ['READY', 'INVOICED', 'REMINDER', 'PARTIALLY_PAID', 'UNPAID'].includes(invoice.status);
  }

  canArchiveInvoice(invoice: CommonInvoiceSummaryResponse): boolean {
    return this.auth.hasAnyRealmRole(['ADMIN', 'OWNER'])
      && invoice.status === 'COLLECTING'
      && invoice.totalOrders > 0
      && invoice.paidKopecks <= 0
      && invoice.paidOrders <= 0;
  }

  canResolveTechnicalTail(invoice: CommonInvoiceSummaryResponse): boolean {
    const error = (invoice.lastError ?? '').trim().toLowerCase();
    return invoice.status === 'DISABLED'
      && invoice.paidOrders >= invoice.totalOrders
      && (error.startsWith('disabled:') || error.startsWith('empty:') || error.startsWith('merged_into:'));
  }

  canResolvePaymentNotification(invoice: CommonInvoiceSummaryResponse): boolean {
    return Boolean(invoice.paymentSuccessNotificationError?.trim());
  }

  canDeleteInvoiceWithOrders(invoice: CommonInvoiceSummaryResponse): boolean {
    return this.auth.hasAnyRealmRole(['ADMIN', 'OWNER'])
      && !['PAID', 'PARTIALLY_PAID'].includes(invoice.status)
      && invoice.paidKopecks <= 0
      && invoice.paidOrders <= 0;
  }

  canApproveReviewOrders(): boolean {
    return this.summary()?.status !== 'NEEDS_ATTENTION' && this.reviewApprovalCount() > 0;
  }

  invoiceActionHint(invoice: CommonInvoiceSummaryResponse, action?: InvoiceAction): string | null {
    if (invoice.status === 'NEEDS_ATTENTION') {
      const policy = this.attentionPolicy(invoice);
      if ((action === 'retry' || action === 'resolve') && policy.requiresManualCheck) {
        return 'Эта ошибка требует ручной сверки; повтор или обычное закрытие проверки недоступны.';
      }
      if (action === 'payment-init-check' && !policy.paymentInitCheck) {
        return 'Этот платежный конфликт нельзя закрыть из карточки.';
      }
      return null;
    }
    if ((action === 'send' || action === 'remind') && !this.allOrdersReady(invoice)) {
      return `Сначала должны быть готовы все заказы: сейчас ${invoice.readyOrders}/${invoice.totalOrders}.`;
    }
    if (action === 'send' && !this.canSendInvoice(invoice)) {
      return 'Отправка доступна только для готового открытого общего счета.';
    }
    if (action === 'remind' && !this.canRemindInvoice(invoice)) {
      return 'Напоминание доступно только после выставления счета.';
    }
    if (action === 'paid' && !this.canMarkInvoicePaid(invoice)) {
      return invoice.status === 'READY' || invoice.status === 'COLLECTING'
        ? `Сначала должны быть готовы все заказы: сейчас ${invoice.readyOrders}/${invoice.totalOrders}.`
        : 'Этот общий счет сейчас нельзя отметить оплаченным.';
    }
    if (action === 'unpaid' && !this.canMarkInvoiceUnpaid(invoice)) {
      return 'В Не оплачен можно перевести только уже выставленный счет с неоплаченными заказами.';
    }
    if (action === 'ban' && !this.canMarkInvoiceBan(invoice)) {
      return 'Бан доступен только после статуса Не оплачен.';
    }
    if (action === 'approve-review-orders' && !this.canApproveReviewOrders()) {
      return 'В составе нет заказов в статусе "В проверку" или "На проверке".';
    }
    if (action === 'approve-review-orders') {
      return null;
    }
    if (!this.allOrdersReady(invoice)) {
      return `Сначала должны быть готовы все заказы: сейчас ${invoice.readyOrders}/${invoice.totalOrders}.`;
    }
    return null;
  }

  private allOrdersReady(invoice: CommonInvoiceSummaryResponse): boolean {
    return invoice.totalOrders > 0 && invoice.readyOrders >= invoice.totalOrders;
  }

  private async load(): Promise<void> {
    const invoiceId = this.invoiceId();
    const ticket = this.routeGuard.capture();
    if (!invoiceId || !ticket) {
      this.error.set('Не указан ID общего счета.');
      return;
    }
    if (this.mutating()) {
      return;
    }

    const readRun = ++this.readRun;
    this.loading.set(true);
    try {
      const details = await firstValueFrom(this.commonBillingApi.getCommonInvoice(invoiceId));
      if (!this.acceptsRead(ticket, readRun)) {
        return;
      }
      this.details.set(details);
      try {
        const mode = await firstValueFrom(this.commonBillingApi.getCommonManualPaymentMode(invoiceId));
        if (!this.acceptsRead(ticket, readRun)) {
          return;
        }
        this.manualAttributionRequired.set(mode.attributionRequired);
        this.error.set(null);
      } catch (modeError) {
        if (this.acceptsRead(ticket, readRun)) {
          this.manualAttributionRequired.set(null);
          this.error.set(this.errorMessage(modeError, 'Счёт загружен, но режим учёта получателей недоступен; ручная оплата заблокирована.'));
        }
      }
    } catch (error) {
      if (this.acceptsRead(ticket, readRun)) {
        this.error.set(this.errorMessage(error, 'Не удалось загрузить общий счет.'));
      }
    } finally {
      if (this.acceptsRead(ticket, readRun)) {
        this.loading.set(false);
      }
    }
  }

  private async runAttributedPayment(
    ticket: RouteEpochTicket,
    invoiceId: number,
    mode: CommonManualPaymentMode
  ): Promise<void> {
    this.readRun += 1;
    const mutation = mode === 'TBANK_FALLBACK' ? 'manual-card-paid' : 'paid';
    this.mutating.set(mutation);
    try {
      const details = await this.commonManualPaymentFlow.confirm(invoiceId, mode);
      if (!this.routeGuard.accepts(ticket) || !details) {
        return;
      }
      this.details.set(details);
      this.error.set(null);
    } catch (error) {
      if (this.routeGuard.accepts(ticket)) {
        this.error.set(this.errorMessage(error, 'Не удалось открыть подтверждение фактических получателей.'));
      }
    } finally {
      if (this.routeGuard.accepts(ticket) && this.mutating() === mutation) {
        this.mutating.set(null);
      }
    }
  }

  private async runOrderMutation(
    ticket: RouteEpochTicket,
    key: string,
    request: () => ReturnType<CommonBillingApi['getCommonInvoice']>
  ): Promise<void> {
    this.readRun += 1;
    this.mutating.set(key);
    try {
      const details = await firstValueFrom(request());
      if (!this.routeGuard.accepts(ticket)) {
        return;
      }
      this.details.set(details);
      this.error.set(null);
    } catch (error) {
      if (this.routeGuard.accepts(ticket)) {
        if (key === 'change-paper-invoice-mode' && error instanceof HttpErrorResponse && error.status === 423) {
          this.error.set(null);
          const toast = await this.toast.create({ message: this.errorMessage(error, 'Ожидаем закрытия ссылки банком'), duration: 5000 });
          if (this.routeGuard.accepts(ticket)) await toast.present();
        } else {
          this.error.set(this.errorMessage(error, 'Не удалось изменить заказ общего счета.'));
        }
      }
    } finally {
      if (this.routeGuard.accepts(ticket) && this.mutating() === key) {
        this.mutating.set(null);
      }
    }
  }

  private acceptsRead(ticket: RouteEpochTicket, readRun: number): boolean {
    return readRun === this.readRun && this.routeGuard.accepts(ticket);
  }

  private async copyText(value: string, key: string, fallback: string): Promise<void> {
    const text = value.trim();
    if (!text) {
      return;
    }

    try {
      await navigator.clipboard.writeText(text);
      this.copiedKey.set(key);
      window.setTimeout(() => {
        if (this.copiedKey() === key) {
          this.copiedKey.set(null);
        }
      }, 1200);
      this.error.set(null);
    } catch {
      this.error.set(fallback);
    }
  }

  private invoiceActionRequest(
    invoiceId: number,
    action: InvoiceAction,
    paymentEvidenceToken: string | null = null,
    manualPaymentEvidence: ManualPaymentConfirmationRequest | null = null
  ): ReturnType<CommonBillingApi['getCommonInvoice']> {
    switch (action) {
      case 'send':
        return this.commonBillingApi.sendCommonInvoice(invoiceId);
      case 'remind':
        return this.commonBillingApi.remindCommonInvoice(invoiceId);
      case 'paid':
        if (!manualPaymentEvidence) {
          throw new Error('Для ручного подтверждения оплаты нужны данные сверки.');
        }
        return this.commonBillingApi.markCommonInvoicePaid(invoiceId, manualPaymentEvidence);
      case 'unpaid':
        return this.commonBillingApi.markCommonInvoiceUnpaid(invoiceId);
      case 'ban':
        return this.commonBillingApi.markCommonInvoiceBan(invoiceId);
      case 'repair-payment-route':
        return this.commonBillingApi.repairCommonInvoicePaymentRoute(invoiceId);
      case 'resolve-technical-tail':
        return this.commonBillingApi.resolveCommonInvoiceTechnicalTail(invoiceId);
      case 'resolve-payment-notification':
        return this.commonBillingApi.resolveCommonInvoicePaymentNotification(invoiceId);
      case 'retry':
        return this.commonBillingApi.retryCommonInvoiceAttention(invoiceId);
      case 'resolve':
        return this.commonBillingApi.resolveCommonInvoiceAttention(invoiceId);
      case 'late-payment':
        return this.commonBillingApi.applyCommonInvoiceLatePayment(invoiceId);
      case 'final-cancel-check':
        return this.commonBillingApi.confirmCommonInvoiceFinalPaymentCancelCheck(invoiceId);
      case 'payment-init-check':
        return this.commonBillingApi.confirmCommonInvoicePaymentInitCheck(
          invoiceId,
          paymentEvidenceToken
        );
      case 'approve-review-orders':
        return this.commonBillingApi.approveCommonInvoiceReviewOrders(invoiceId);
    }
  }

  private async requestManualPaymentEvidence(
    title: string,
    message: string
  ): Promise<ManualPaymentConfirmationRequest | null> {
    const confirmed = await this.confirm.confirm({
      title,
      message: `${message} Сообщение клиента «Я оплатил» само по себе не подтверждает поступление.`,
      confirmText: 'Деньги поступили'
    });
    if (!confirmed) {
      return null;
    }
    const comment = window.prompt(
      'Комментарий к проверке оплаты (например: «сверено по выписке, 04.08 15:30»). Если есть только чек — оставьте пустым.'
    );
    if (comment === null) {
      return null;
    }
    const receiptUrl = window.prompt(
      'Ссылка на чек или платёжный документ (необязательно, если заполнен комментарий):'
    );
    if (receiptUrl === null) {
      return null;
    }
    const evidence = buildManualPaymentConfirmationRequest(comment, receiptUrl);
    if (!evidence) {
      this.error.set('Оплата не отмечена: укажите комментарий по проверке или ссылку на чек.');
      return null;
    }
    return evidence;
  }

  private invoiceActionConfirm(action: InvoiceAction): string {
    switch (action) {
      case 'send':
        return 'Отправить общий счет клиенту?';
      case 'remind':
        return 'Отправить напоминание по общему счету?';
      case 'paid':
        return 'Отметить общий счет оплаченным?';
      case 'unpaid':
        return 'Отметить общий счет не оплаченным?';
      case 'ban':
        return 'Перевести общий счет в бан?';
      case 'repair-payment-route':
        return 'Перепроверить и восстановить единый платежный маршрут общего счета?';
      case 'resolve-technical-tail':
        return 'Закрыть технический хвост после проверки связи и переноса заказов?';
      case 'resolve-payment-notification':
        return 'Закрыть ошибку только после ручной отправки уведомления или исправления её причины?';
      case 'retry':
        return 'Повторить обработку общего счета?';
      case 'resolve':
        return 'Закрыть ручную проверку общего счета?';
      case 'late-payment':
        return 'Распределить поздний платеж по заказам общего счета?';
      case 'final-cancel-check':
        return 'Подтвердить сверку отмены финального платежа?';
      case 'payment-init-check':
        return 'Подтвердить сверку создания платежа?';
      case 'approve-review-orders':
        return `Одобрить ${this.reviewApprovalCount()} заказ(ов) и перевести их в публикацию?`;
    }
  }

  attentionPolicy(invoice: CommonInvoiceSummaryResponse) {
    return commonInvoiceAttentionPolicy(invoice.lastError);
  }

  private paymentInitCheckConfirmation(
    evidenceItems: ReturnType<typeof commonInvoicePaymentEvidence>
  ): string {
    const evidence = commonInvoicePaymentEvidenceConfirmationLines(
      evidenceItems,
      amountKopecks => this.kopecks(amountKopecks)
    );
    return commonInvoiceNoPaymentConfirmation(evidence
        ? `\n\n${evidence}`
        : '\n\nПлатежные реквизиты не сохранены; проверьте операцию по сумме, времени попытки и терминалу.');
  }

  private errorMessage(error: unknown, fallback: string): string {
    if (error instanceof HttpErrorResponse) {
      const body = error.error;
      if (typeof body === 'string') {
        const trimmed = body.trim();
        if (trimmed) {
          return trimmed;
        }
      }
      if (body && typeof body === 'object') {
        const message = this.readErrorField(body, 'message')
          || this.readErrorField(body, 'reason')
          || this.readErrorField(body, 'detail')
          || this.readErrorField(body, 'title')
          || this.readErrorField(body, 'error');
        if (message) {
          return message;
        }
      }
      if (error.status === 409) {
        return 'Действие сейчас недоступно для текущего статуса общего счета.';
      }
      return error.status ? `${fallback} Код ${error.status}.` : fallback;
    }
    if (typeof error === 'object' && error && 'message' in error) {
      return String((error as { message?: unknown }).message || fallback);
    }
    return fallback;
  }

  private readErrorField(body: object, key: string): string | null {
    const value = (body as Record<string, unknown>)[key];
    return typeof value === 'string' && value.trim() ? value.trim() : null;
  }
}
