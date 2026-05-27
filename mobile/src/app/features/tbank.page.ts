import { Component, OnInit, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  IonContent,
  IonModal,
  IonRefresher,
  IonRefresherContent,
  RefresherCustomEvent
} from '@ionic/angular/standalone';
import { firstValueFrom } from 'rxjs';
import {
  AdminPaymentLinkResponse,
  ApiService,
  ManualPaymentTaskResponse,
  ManualPaymentTaskStatus,
  ManualPaymentType,
  ManagerPaymentProfileResponse,
  ManagerPaymentProfileAssignmentRequest,
  PaymentInstructionSource,
  PaymentPolicy,
  PaymentProfilePolicyRequest,
  PaymentProfileResponse,
  TbankPaymentPageMode,
  TbankPaymentStatus,
  TbankRuntimeMode,
  TbankRuntimeSettings,
  UpdateTbankRuntimeSettingsRequest
} from '../core/api.service';
import { MobileHeaderComponent } from '../shared/mobile-header.component';
import { MobileBottomPagerComponent } from '../shared/mobile-bottom-pager.component';
import { MobileConfirmService } from '../shared/mobile-confirm.service';
import { MobileExternalLinkService } from '../shared/mobile-external-link.service';
import { MobileRemindersComponent } from '../shared/mobile-reminders.component';
import { MobileSearchBarComponent } from '../shared/mobile-search-bar.component';
import { MobileStatusSliderComponent, type MobileStatusItem } from '../shared/mobile-status-slider.component';

type TbankMode = 'payments' | 'launch' | 'tasks' | 'profiles' | 'managers';
type PaymentStatusFilter = 'all' | 'active' | 'paid' | 'refunded' | 'failed' | 'created' | 'manual';
type Tone = 'blue' | 'green' | 'yellow' | 'red' | 'teal' | 'gray';

type StatusOption = {
  key: PaymentStatusFilter;
  label: string;
  icon: string;
  tone: Tone;
};

type ProfilePolicyDraft = {
  paymentPolicy: PaymentPolicy;
  manualPaymentType: ManualPaymentType;
  manualPhone: string;
  manualRecipientName: string;
  manualPaymentUrl: string;
  manualPaymentButtonLabel: string;
  manualMonthlyLimitRubles: string;
};

const PAGE_SIZE = 10;
const DEFAULT_MANUAL_MONTHLY_LIMIT_RUBLES = 191000;
const DEFAULT_MANUAL_RECIPIENT_NAME = 'Сивохин И.И.';
const DEFAULT_MANUAL_PAYMENT_URL = 'https://pay.alfabank.ru/sc/EWwpfrArNZotkqOR';
const DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL = 'Оплатить через Альфа-Банк';

@Component({
  selector: 'app-tbank-page',
  imports: [FormsModule, IonContent, IonModal, IonRefresher, IonRefresherContent, MobileBottomPagerComponent, MobileHeaderComponent, MobileRemindersComponent, MobileSearchBarComponent, MobileStatusSliderComponent],
  template: `
    <div class="ion-page">
      <app-mobile-header title="Т Банк" />

      <ion-content fullscreen [scrollY]="false">
        <ion-refresher slot="fixed" (ionRefresh)="refresh($event)">
          <ion-refresher-content />
        </ion-refresher>

        <app-mobile-reminders #reminders />

        <main class="tbank-page">
          <app-mobile-status-slider
            [items]="modeItems()"
            [activeKey]="mode()"
            ariaLabel="Разделы T-Bank"
            (select)="selectModeItem($event)"
          />

          @if (mode() === 'payments') {
            <app-mobile-search-bar
              [value]="search()"
              placeholder="Компания, заказ, PaymentId, e-mail"
              [showRefresh]="false"
              [hasExtraAction]="true"
              (valueChange)="setSearch($event)"
              (searchSubmit)="resetPage()"
            >
              <button
                mobileSearchActions
                class="date-filter-button"
                type="button"
                [class.active]="hasDateFilter()"
                (click)="openDateSheet()"
                [attr.aria-label]="'Период платежей: ' + dateFilterLabel()"
              >
                <span class="material-icons-sharp">date_range</span>
              </button>

              @if (hasFilters()) {
                <button mobileSearchActions type="button" (click)="resetFilters()" aria-label="Сбросить фильтры">
                  <span class="material-icons-sharp">close</span>
                </button>
              }

              <button mobileSearchActions type="button" (click)="load()" [disabled]="loading()" aria-label="Обновить">
                <span class="material-icons-sharp">refresh</span>
              </button>
            </app-mobile-search-bar>

            <app-mobile-status-slider
              [items]="paymentStatusItems()"
              [activeKey]="statusFilter()"
              ariaLabel="Статусы платежей"
              (select)="selectPaymentStatus($event)"
            />
          }

          @if (error()) {
            <button class="inline-alert" type="button" (click)="load()">
              <span class="material-icons-sharp">error</span>
              <span>{{ error() }}</span>
            </button>
          }

          @if (mode() === 'payments') {
            <section class="payment-list" aria-label="Журнал платежей">
              @if (loading()) {
                <div class="empty-state">
                  <span class="material-icons-sharp">hourglass_top</span>
                  <strong>Загружаем платежи</strong>
                </div>
              } @else {
                @for (link of pageLinks(); track link.id) {
                  <article class="payment-card tone-{{ rowTone(link) }}">
                    <header>
                      <div>
                        <h2>{{ paymentTitle(link) }}</h2>
                      </div>
                      <span class="status-pill {{ statusClass(link.status) }}">{{ statusLabel(link.status) }}</span>
                    </header>

                    @if (paymentAddress(link) || paymentCategory(link)) {
                      <div class="payment-context-row">
                        @if (paymentAddress(link)) {
                          <span>
                            <small>Адрес</small>
                            <strong>{{ paymentAddress(link) }}</strong>
                          </span>
                        }
                        @if (paymentCategory(link)) {
                          <span>
                            <small>Категория</small>
                            <strong>{{ paymentCategory(link) }}</strong>
                          </span>
                        }
                      </div>
                    }

                    <div class="amount-row">
                      <span>
                        <small>Сумма</small>
                        <strong>{{ money(link.amount) }}</strong>
                      </span>
                      <span>
                        <small>Заказ</small>
                        <strong>#{{ link.orderId || '-' }}</strong>
                      </span>
                    </div>

                    @if (isManualPayment(link)) {
                      @if (isExternalManualPayment(link)) {
                        <button
                          type="button"
                          class="copy-line manual"
                          (click)="openUrl(link.manualPaymentUrl)"
                          [disabled]="!link.manualPaymentUrl"
                        >
                          <span class="material-icons-sharp">open_in_new</span>
                          {{ link.manualPaymentButtonLabel || 'Ссылка Альфа-Банк' }}
                        </button>
                      } @else {
                        <button
                          type="button"
                          class="copy-line manual"
                          (click)="copy(link.manualPhone, 'manual-phone-' + link.id)"
                          [disabled]="!link.manualPhone"
                        >
                          <span class="material-icons-sharp">{{ copied() === 'manual-phone-' + link.id ? 'check' : 'content_copy' }}</span>
                          {{ link.manualPhone || 'Телефон не указан' }}
                        </button>
                      }
                      <div class="manual-note">
                        <strong>{{ link.manualRecipientName || 'Получатель не указан' }}</strong>
                        <small>{{ manualSourceLabel(link) }} · {{ link.manualComment || 'Комментарий не задан' }}</small>
                      </div>
                    } @else {
                      <button
                        type="button"
                        class="copy-line"
                        (click)="copy(link.tbankPaymentId, 'payment-' + link.id)"
                        [disabled]="!link.tbankPaymentId"
                      >
                        <span class="material-icons-sharp">{{ copied() === 'payment-' + link.id ? 'check' : 'content_copy' }}</span>
                        {{ link.tbankPaymentId || 'PaymentId не получен' }}
                      </button>
                    }

                    <div class="meta-grid">
                      <span>
                        <small>{{ isManualPayment(link) ? 'Метод' : 'OrderId' }}</small>
                        <strong>{{ isManualPayment(link) ? paymentMethodLabel(link) : (link.tbankOrderId || '-') }}</strong>
                      </span>
                      <span>
                        <small>Профиль</small>
                        <strong>{{ link.paymentProfileName || link.tbankTerminalKey || 'Основной' }}</strong>
                      </span>
                      <span>
                        <small>Создан</small>
                        <strong>{{ shortDate(link.createdAt) }}</strong>
                      </span>
                      <span>
                        <small>Истекает</small>
                        <strong>{{ shortDate(link.expiresAt) }}</strong>
                      </span>
                      @if (isManualPayment(link)) {
                        <span>
                          <small>Чек</small>
                          <strong>{{ receiptStatusLabel(link) }}</strong>
                        </span>
                        <span>
                          <small>Сверка</small>
                          <strong>{{ shortDate(link.manualConfirmedAt || link.manualReportedAt) }}</strong>
                        </span>
                      }
                    </div>

                    @if (link.payerEmail) {
                      <button type="button" class="copy-line email" (click)="copy(link.payerEmail, 'email-' + link.id)">
                        <span class="material-icons-sharp">{{ copied() === 'email-' + link.id ? 'check' : 'mail' }}</span>
                        {{ link.payerEmail }}
                      </button>
                    }

                    @if (link.lastError) {
                      <p class="error-text">
                        <span class="material-icons-sharp">priority_high</span>
                        {{ link.lastError }}
                      </p>
                    }

                    <footer>
                      <button type="button" (click)="openUrl(link.publicUrl)" [disabled]="!link.publicUrl">
                        <span class="material-icons-sharp">open_in_new</span>
                        ссылка
                      </button>
                      @if (canConfirmManual(link)) {
                        <button type="button" class="confirm" (click)="confirmManual(link)" [disabled]="mutatingId() === link.id">
                          <span class="material-icons-sharp">done_all</span>
                          {{ mutatingId() === link.id ? '...' : 'подтвердить' }}
                        </button>
                      }
                      @if (canMarkManualReceipt(link)) {
                        <button type="button" class="receipt" (click)="markManualReceipt(link)" [disabled]="mutatingId() === link.id">
                          <span class="material-icons-sharp">receipt</span>
                          чек
                        </button>
                      }
                      @if (!isManualPayment(link)) {
                        <button type="button" (click)="openUrl(link.paymentUrl)" [disabled]="!link.paymentUrl">
                          <span class="material-icons-sharp">credit_card</span>
                          банк
                        </button>
                        <button
                          type="button"
                          class="refund"
                          (click)="cancel(link)"
                          [disabled]="!link.refundable || mutatingId() === link.id"
                        >
                          <span class="material-icons-sharp">undo</span>
                          {{ mutatingId() === link.id ? '...' : 'возврат' }}
                        </button>
                      }
                    </footer>
                  </article>
                } @empty {
                  <div class="empty-state">
                    <span class="material-icons-sharp">search_off</span>
                    <strong>{{ hasFilters() ? 'По фильтрам ничего не найдено' : 'Платежных ссылок пока нет' }}</strong>
                  </div>
                }
              }
            </section>

            <app-mobile-bottom-pager
              class="mobile-page-bottom-pager"
              [pageIndex]="pageIndex()"
              [totalPages]="totalPages()"
              (previous)="previousPage()"
              (next)="nextPage()"
            >
              <button mobilePagerActions class="reminder-hero-button" type="button" (click)="reminders.open()" aria-label="Напоминания">
                <span class="material-icons-sharp">notifications_active</span>
                @if (reminders.activeReminderCount()) {
                  <small>{{ reminders.activeReminderCount() }}</small>
                }
              </button>
              <button
                mobilePagerActions
                type="button"
                [class.active]="sortDirection() === 'asc'"
                (click)="toggleSort()"
                [attr.aria-label]="sortDirection() === 'asc' ? 'Показать сначала новые платежи' : 'Показать сначала старые платежи'"
              >
                <span class="material-icons-sharp">swap_vert</span>
              </button>
            </app-mobile-bottom-pager>
          } @else if (mode() === 'launch') {
            <section class="launch-list" aria-label="Пульт запуска T-Bank">
              @if (runtimeSettings(); as settings) {
                <article class="launch-card launch-summary-card">
                  <header>
                    <span class="material-icons-sharp">{{ settings.runtimeMode === 'TEST' ? 'science' : 'rocket_launch' }}</span>
                    <div>
                      <p>Пульт запуска</p>
                      <h2>{{ launchStateTitle() }}</h2>
                      <small>{{ launchStateDescription() }}</small>
                    </div>
                  </header>
                  @if (savingRuntimeSettings()) {
                    <span class="saving-note">Сохраняю настройки...</span>
                  }
                </article>

                <article class="launch-card">
                  <h3><span>1</span> Контур T-Bank</h3>
                  <div class="segmented two">
                    <button type="button" [class.active]="settings.runtimeMode === 'TEST'" [disabled]="savingRuntimeSettings() || loading()" (click)="setRuntimeMode('TEST')">
                      <span class="material-icons-sharp">science</span>
                      Тестовый
                    </button>
                    <button type="button" [class.active]="settings.runtimeMode === 'LIVE'" [disabled]="savingRuntimeSettings() || loading()" (click)="setRuntimeMode('LIVE')">
                      <span class="material-icons-sharp">verified</span>
                      Боевой
                    </button>
                  </div>
                  <small>В тестовом контуре платежи не засчитываются как реальные.</small>
                </article>

                <article class="launch-card">
                  <h3><span>2</span> Что отправлять клиентам</h3>
                  <div class="segmented two">
                    <button type="button" [class.active]="settings.paymentInstructionSource === 'MANAGER_TEXT'" [disabled]="savingRuntimeSettings() || loading()" (click)="setPaymentInstructionSource('MANAGER_TEXT')">
                      <span class="material-icons-sharp">article</span>
                      Альфа / текст
                    </button>
                    <button type="button" [class.active]="settings.paymentInstructionSource === 'TBANK_LINK'" [disabled]="savingRuntimeSettings() || loading() || settings.runtimeMode === 'TEST'" (click)="setPaymentInstructionSource('TBANK_LINK')">
                      <span class="material-icons-sharp">link</span>
                      T-Bank ссылки
                    </button>
                  </div>
                  <small>Переключает автоответчик и счета после плохого отзыва.</small>
                </article>

                <article class="launch-card">
                  <h3><span>3</span> После подтверждения оплаты</h3>
                  <div class="segmented two">
                    <button type="button" [class.active]="!settings.applyConfirmedPayments" [disabled]="savingRuntimeSettings() || loading()" (click)="setApplyConfirmedPayments(false)">
                      <span class="material-icons-sharp">fact_check</span>
                      Только журнал
                    </button>
                    <button type="button" [class.active]="settings.applyConfirmedPayments" [disabled]="savingRuntimeSettings() || loading() || settings.runtimeMode === 'TEST'" (click)="setApplyConfirmedPayments(true)">
                      <span class="material-icons-sharp">paid</span>
                      Оплачивать заказ
                    </button>
                  </div>
                  <small>Полное включение переводит заказ в оплату по webhook CONFIRMED.</small>
                </article>

                <article class="launch-card">
                  <h3><span>4</span> Способы на /pay</h3>
                  <div class="segmented four">
                    <button type="button" [class.active]="settings.paymentPageMode === 'SBP_PRIMARY'" [disabled]="savingRuntimeSettings() || loading()" (click)="setPaymentPageMode('SBP_PRIMARY')">
                      <span class="material-icons-sharp">account_balance_wallet</span>
                      СБП + карта
                    </button>
                    <button type="button" [class.active]="settings.paymentPageMode === 'BANK_PRIMARY'" [disabled]="savingRuntimeSettings() || loading()" (click)="setPaymentPageMode('BANK_PRIMARY')">
                      <span class="material-icons-sharp">credit_card</span>
                      Карта + СБП
                    </button>
                    <button type="button" [class.active]="settings.paymentPageMode === 'SBP_ONLY'" [disabled]="savingRuntimeSettings() || loading()" (click)="setPaymentPageMode('SBP_ONLY')">
                      <span class="material-icons-sharp">mobile_friendly</span>
                      Только СБП
                    </button>
                    <button type="button" [class.active]="settings.paymentPageMode === 'BANK_ONLY'" [disabled]="savingRuntimeSettings() || loading()" (click)="setPaymentPageMode('BANK_ONLY')">
                      <span class="material-icons-sharp">payments</span>
                      Только банк
                    </button>
                  </div>
                  <small>{{ paymentPageModeDescription() }}</small>
                </article>

                <article class="launch-card">
                  <h3><span>5</span> Быстрые методы банка</h3>
                  <label class="toggle-row">
                    <span><strong>T-Pay</strong><small>Показывать в банковском блоке</small></span>
                    <input type="checkbox" [checked]="settings.tpayEnabled" [disabled]="savingRuntimeSettings() || loading()" (change)="setFastBankMethodSwitch('tpayEnabled', $any($event.target).checked)">
                  </label>
                  <label class="toggle-row">
                    <span><strong>SberPay</strong><small>Показывать в банковском блоке</small></span>
                    <input type="checkbox" [checked]="settings.sberpayEnabled" [disabled]="savingRuntimeSettings() || loading()" (change)="setFastBankMethodSwitch('sberpayEnabled', $any($event.target).checked)">
                  </label>
                  <label class="toggle-row">
                    <span><strong>Mir Pay</strong><small>Показывать в банковском блоке</small></span>
                    <input type="checkbox" [checked]="settings.mirpayEnabled" [disabled]="savingRuntimeSettings() || loading()" (change)="setFastBankMethodSwitch('mirpayEnabled', $any($event.target).checked)">
                  </label>
                  <small>{{ fastBankMethodDescription() }}</small>
                </article>

                <article class="launch-card">
                  <h3><span>6</span> Базовые переключатели</h3>
                  <label class="toggle-row">
                    <span><strong>API</strong><small>Разрешить работу T-Bank backend</small></span>
                    <input type="checkbox" [checked]="settings.tbankEnabled" [disabled]="savingRuntimeSettings() || loading()" (change)="setCoreSwitch('tbankEnabled', $any($event.target).checked)">
                  </label>
                  <label class="toggle-row">
                    <span><strong>Ссылки</strong><small>Создание платежных ссылок</small></span>
                    <input type="checkbox" [checked]="settings.paymentLinksEnabled" [disabled]="savingRuntimeSettings() || loading()" (change)="setCoreSwitch('paymentLinksEnabled', $any($event.target).checked)">
                  </label>
                  <label class="toggle-row">
                    <span><strong>UI менеджера</strong><small>Показывать платежный UI менеджерам</small></span>
                    <input type="checkbox" [checked]="settings.managerUiEnabled" [disabled]="savingRuntimeSettings() || loading()" (change)="setCoreSwitch('managerUiEnabled', $any($event.target).checked)">
                  </label>
                </article>
              } @else {
                <div class="empty-state">
                  <span class="material-icons-sharp">hourglass_top</span>
                  <strong>Настройки запуска загружаются</strong>
                </div>
              }
            </section>
          } @else if (mode() === 'tasks') {
            <section class="task-list" aria-label="Ручные платежные задания">
              <article class="manager-save-card">
                <div>
                  <p>Ручные платежи</p>
                  <h2>Задания менеджерам</h2>
                </div>
                <button type="button" (click)="load()" [disabled]="loading()">
                  <span class="material-icons-sharp">refresh</span>
                  Обновить
                </button>
              </article>

              <article class="profile-card manual-task-form">
                <header>
                  <span class="material-icons-sharp">playlist_add</span>
                  <div>
                    <h2>Создать задание</h2>
                    <small>Приоритетнее лимита профиля и не уменьшает месячный счетчик.</small>
                  </div>
                </header>

                <label class="profile-field">
                  <span>Менеджер</span>
                  <select [ngModel]="adminTaskManagerId()" (ngModelChange)="setAdminTaskManagerId($event)">
                    <option [ngValue]="null">Выберите менеджера</option>
                    @for (manager of managerProfiles(); track manager.managerId) {
                      <option [ngValue]="manager.managerId">
                        {{ manager.managerTitle }} · {{ manager.paymentProfileName || 'по умолчанию' }}
                      </option>
                    }
                  </select>
                </label>

                <div class="segmented two profile-policy-segment">
                  <button
                    type="button"
                    [class.active]="adminTaskPaymentType() === 'MOBILE_BANK'"
                    (click)="setAdminTaskPaymentType('MOBILE_BANK')"
                  >
                    <span class="material-icons-sharp">phone_iphone</span>
                    Телефон
                  </button>
                  <button
                    type="button"
                    [class.active]="adminTaskPaymentType() === 'EXTERNAL_LINK'"
                    (click)="setAdminTaskPaymentType('EXTERNAL_LINK')"
                  >
                    <span class="material-icons-sharp">link</span>
                    Ссылка Альфа
                  </button>
                </div>

                @if (adminTaskPaymentType() === 'EXTERNAL_LINK') {
                  <label class="profile-field">
                    <span>Ссылка оплаты</span>
                    <input
                      type="url"
                      [ngModel]="adminTaskPaymentUrl()"
                      (ngModelChange)="setAdminTaskPaymentUrl($event)"
                      placeholder="https://pay.alfabank.ru/..."
                    >
                  </label>
                  <label class="profile-field">
                    <span>Текст кнопки</span>
                    <input
                      type="text"
                      [ngModel]="adminTaskPaymentButtonLabel()"
                      (ngModelChange)="setAdminTaskPaymentButtonLabel($event)"
                      placeholder="Оплатить через Альфа-Банк"
                    >
                  </label>
                } @else {
                  <label class="profile-field">
                    <span>Телефон</span>
                    <input
                      type="tel"
                      [ngModel]="adminTaskPhone()"
                      (ngModelChange)="setAdminTaskPhone($event)"
                      placeholder="+7..."
                    >
                  </label>
                }

                <label class="profile-field">
                  <span>Получатель</span>
                  <input
                    type="text"
                    [ngModel]="adminTaskRecipient()"
                    (ngModelChange)="setAdminTaskRecipient($event)"
                    placeholder="ФИО получателя"
                  >
                </label>

                <div class="amount-row">
                  <label class="profile-field">
                    <span>Сумма, руб.</span>
                    <input
                      type="number"
                      min="1"
                      step="1"
                      inputmode="numeric"
                      [ngModel]="adminTaskAmountRubles()"
                      (ngModelChange)="setAdminTaskAmountRubles($event)"
                      placeholder="50000"
                    >
                  </label>
                  <label class="profile-field">
                    <span>Комментарий</span>
                    <input
                      type="text"
                      [ngModel]="adminTaskComment()"
                      (ngModelChange)="setAdminTaskComment($event)"
                      placeholder="Необязательно"
                    >
                  </label>
                </div>

                <footer class="task-actions">
                  <button type="button" class="confirm" (click)="createManualTask()" [disabled]="!canCreateManualTask()">
                    <span class="material-icons-sharp">{{ savingManualTask() ? 'hourglass_top' : 'add' }}</span>
                    {{ savingManualTask() ? 'создаю' : 'создать' }}
                  </button>
                </footer>
              </article>

              @for (task of manualTasks(); track task.id) {
                <article class="profile-card manual-task-card" [class.disabled]="task.status !== 'ACTIVE'">
                  <header>
                    <span class="material-icons-sharp">playlist_add_check</span>
                    <div>
                      <h2>{{ manualTaskTitle(task) }}</h2>
                      <small>{{ manualTaskTargetLine(task) }}</small>
                    </div>
                    <span class="status-pill {{ task.status === 'ACTIVE' ? 'manual' : 'neutral' }}">
                      {{ manualTaskStatusLabel(task.status) }}
                    </span>
                  </header>

                  <div class="manual-limit-box">
                    <div>
                      <strong>{{ formatKopecks(task.reservedAmountKopecks) }}</strong>
                      <small>
                        из {{ formatKopecks(task.targetAmountKopecks) }}
                        · подтверждено {{ formatKopecks(task.confirmedAmountKopecks) }}
                        · ждут {{ task.pendingCount }}
                      </small>
                    </div>
                    <div class="limit-bar">
                      <span [style.width.%]="manualTaskProgressPercent(task)"></span>
                    </div>
                  </div>

                  <div class="meta-grid">
                    <span>
                      <small>Профиль</small>
                      <strong>{{ task.paymentProfileName || 'по умолчанию' }}</strong>
                    </span>
                    <span>
                      <small>Менеджер</small>
                      <strong>{{ task.managerTitle || task.username }}</strong>
                    </span>
                  </div>

                  @if (task.comment) {
                    <div class="manual-note">
                      <strong>Комментарий</strong>
                      <small>{{ task.comment }}</small>
                    </div>
                  }

                  <footer class="task-actions">
                    @if (task.status === 'ACTIVE') {
                      <button type="button" (click)="updateManualTaskStatus(task, 'PAUSED')" [disabled]="mutatingTaskId() === task.id">
                        <span class="material-icons-sharp">pause</span>
                        пауза
                      </button>
                    } @else if (task.status === 'PAUSED') {
                      <button type="button" class="confirm" (click)="updateManualTaskStatus(task, 'ACTIVE')" [disabled]="mutatingTaskId() === task.id">
                        <span class="material-icons-sharp">play_arrow</span>
                        включить
                      </button>
                    }
                    @if (task.status !== 'COMPLETED' && task.status !== 'CANCELED') {
                      <button type="button" class="refund" (click)="updateManualTaskStatus(task, 'CANCELED')" [disabled]="mutatingTaskId() === task.id">
                        <span class="material-icons-sharp">close</span>
                        отменить
                      </button>
                    }
                  </footer>
                </article>
              } @empty {
                <div class="empty-state">
                  <span class="material-icons-sharp">playlist_add</span>
                  <strong>Ручных заданий пока нет</strong>
                </div>
              }
            </section>
          } @else if (mode() === 'profiles') {
            <section class="profile-list" aria-label="Платежные профили">
              @if (status(); as bank) {
                <article class="bank-card">
                  <span class="material-icons-sharp">{{ bank.enabled ? 'verified' : 'power_settings_new' }}</span>
                  <div>
                    <p>T-Bank</p>
                    <h2>{{ bank.enabled ? 'API включен' : 'API выключен' }}</h2>
                    <small>
                      {{ bank.testMode ? 'тестовый терминал' : 'боевой терминал' }} ·
                      {{ bank.applyConfirmedPayments ? 'заказы переводятся автоматически' : 'ручной перевод заказов' }}
                    </small>
                  </div>
                  <button type="button" class="icon-button" (click)="load()" [disabled]="loading()" aria-label="Обновить">
                    <span class="material-icons-sharp">refresh</span>
                  </button>
                </article>
              }

              <article class="manager-save-card">
                <div>
                  <p>Маршрутизация оплат</p>
                  <h2>Профили, лимиты и телефон</h2>
                </div>
                <button type="button" (click)="saveProfilePolicies()" [disabled]="savingProfilePolicies() || loading()">
                  <span class="material-icons-sharp">save</span>
                  {{ savingProfilePolicies() ? 'Сохраняю' : 'Сохранить' }}
                </button>
              </article>

              @for (profile of profiles(); track profile.id) {
                <article class="profile-card" [class.disabled]="!profile.enabled">
                  <header>
                    <span class="material-icons-sharp">storefront</span>
                    <div>
                      <h2>{{ profile.name }}</h2>
                      <small>{{ profile.defaultProfile ? 'По умолчанию' : 'Дополнительный' }} · {{ profilePolicyLabel(profile) }}</small>
                    </div>
                    <span class="status-pill {{ profile.hasPassword ? 'paid' : 'failed' }}">
                      {{ profile.hasPassword ? 'секрет есть' : 'нет секрета' }}
                    </span>
                  </header>
                  <div class="meta-grid">
                    <span>
                      <small>Терминал</small>
                      <strong>{{ profile.terminalKey }}</strong>
                    </span>
                    <span>
                      <small>Режим</small>
                      <strong>{{ profile.testMode ? 'тест' : 'боевой' }}</strong>
                    </span>
                    <span>
                      <small>Провайдер</small>
                      <strong>{{ profile.provider }}</strong>
                    </span>
                    <span>
                      <small>Статус</small>
                      <strong>{{ profile.enabled ? 'включен' : 'выключен' }}</strong>
                    </span>
                  </div>

                  <div class="manual-limit-box">
                    <div>
                      <strong>{{ formatKopecks(profile.manualMonthlyUsedKopecks) }}</strong>
                      <small>из {{ formatKopecks(profileManualLimitKopecks(profile)) }} · ожидают {{ profile.manualMonthlyPendingCount }}</small>
                    </div>
                    <div class="limit-bar">
                      <span [style.width.%]="profileManualUsagePercent(profile)"></span>
                    </div>
                  </div>

                  <div class="segmented two profile-policy-segment">
                    <button
                      type="button"
                      [class.active]="profilePolicy(profile.id).paymentPolicy === 'T_BANK_ONLY'"
                      (click)="setProfilePolicy(profile.id, 'T_BANK_ONLY')"
                    >
                      <span class="material-icons-sharp">account_balance</span>
                      Только T-Bank
                    </button>
                    <button
                      type="button"
                      [class.active]="profilePolicy(profile.id).paymentPolicy === 'MANUAL_UNTIL_LIMIT_THEN_TBANK'"
                      (click)="setProfilePolicy(profile.id, 'MANUAL_UNTIL_LIMIT_THEN_TBANK')"
                    >
                      <span class="material-icons-sharp">phone_iphone</span>
                      Телефон до лимита
                    </button>
                  </div>

                  <label class="profile-field">
                    <span>Месячный лимит ручных оплат, руб.</span>
                    <input
                      type="number"
                      min="0"
                      step="1000"
                      [ngModel]="profilePolicy(profile.id).manualMonthlyLimitRubles"
                      (ngModelChange)="setProfileManualLimit(profile.id, $event)"
                    >
                  </label>

                  @if (profilePolicy(profile.id).paymentPolicy === 'MANUAL_UNTIL_LIMIT_THEN_TBANK') {
                    <div class="segmented two profile-policy-segment">
                      <button
                        type="button"
                        [class.active]="profilePolicy(profile.id).manualPaymentType === 'MOBILE_BANK'"
                        (click)="setProfileManualPaymentType(profile.id, 'MOBILE_BANK')"
                      >
                        <span class="material-icons-sharp">phone_iphone</span>
                        Телефон
                      </button>
                      <button
                        type="button"
                        [class.active]="profilePolicy(profile.id).manualPaymentType === 'EXTERNAL_LINK'"
                        (click)="setProfileManualPaymentType(profile.id, 'EXTERNAL_LINK')"
                      >
                        <span class="material-icons-sharp">link</span>
                        Ссылка Альфа
                      </button>
                    </div>

                    @if (profilePolicy(profile.id).manualPaymentType === 'EXTERNAL_LINK') {
                      <label class="profile-field">
                        <span>Ссылка оплаты</span>
                        <input
                          type="url"
                          [ngModel]="profilePolicy(profile.id).manualPaymentUrl"
                          (ngModelChange)="setProfileManualPaymentUrl(profile.id, $event)"
                          placeholder="https://pay.alfabank.ru/..."
                        >
                      </label>
                      <label class="profile-field">
                        <span>Текст кнопки</span>
                        <input
                          type="text"
                          [ngModel]="profilePolicy(profile.id).manualPaymentButtonLabel"
                          (ngModelChange)="setProfileManualPaymentButtonLabel(profile.id, $event)"
                          placeholder="Оплатить через Альфа-Банк"
                        >
                      </label>
                    } @else {
                      <label class="profile-field">
                        <span>Телефон для перевода</span>
                        <input
                          type="text"
                          [ngModel]="profilePolicy(profile.id).manualPhone"
                          (ngModelChange)="setProfileManualPhone(profile.id, $event)"
                          placeholder="+7..."
                        >
                      </label>
                    }

                    <label class="profile-field">
                      <span>Получатель</span>
                      <input
                        type="text"
                        [ngModel]="profilePolicy(profile.id).manualRecipientName"
                        (ngModelChange)="setProfileManualRecipient(profile.id, $event)"
                        placeholder="Имя в банке"
                      >
                    </label>
                  }
                </article>
              } @empty {
                <div class="empty-state">Профили не найдены.</div>
              }
            </section>
          } @else {
            <section class="manager-profile-list" aria-label="Платежные профили менеджеров">
              <article class="manager-save-card">
                <div>
                  <p>Маршрутизация оплат</p>
                  <h2>Профили и менеджеры</h2>
                </div>
                <button type="button" (click)="saveRoutingSettings()" [disabled]="savingRoutingSettings() || loading()">
                  <span class="material-icons-sharp">save</span>
                  {{ savingRoutingSettings() ? 'Сохраняю' : 'Сохранить' }}
                </button>
              </article>

              @for (manager of managerProfiles(); track manager.managerId) {
                <label class="manager-row">
                  <span>
                    <strong>{{ manager.managerTitle }}</strong>
                    <small>ID {{ manager.managerId }} · {{ manager.username || 'без логина' }}</small>
                  </span>
                  <select
                    [ngModel]="selectedProfileId(manager)"
                    (ngModelChange)="setManagerProfile(manager.managerId, $event)"
                    [attr.aria-label]="'Профиль для ' + manager.managerTitle"
                  >
                    <option [ngValue]="null">По умолчанию</option>
                    @for (profile of profiles(); track profile.id) {
                      <option [ngValue]="profile.id">{{ profile.name }}</option>
                    }
                  </select>
                </label>
              } @empty {
                <div class="empty-state">Менеджеры не найдены.</div>
              }
            </section>
          }
        </main>

        <ion-modal class="sheet-modal tbank-date-sheet" [isOpen]="dateSheetOpen()" (didDismiss)="closeDateSheet()">
          <ng-template>
            <form class="sheet-body tbank-date-sheet-body" (ngSubmit)="applyDateSheet()">
              <header class="sheet-head">
                <div>
                  <p class="sheet-note">T-Bank</p>
                  <h2>Период платежей</h2>
                </div>
                <button class="icon-button" type="button" (click)="closeDateSheet()" aria-label="Закрыть">
                  <span class="material-icons-sharp">close</span>
                </button>
              </header>

              <section class="sheet-form-content tbank-date-form">
                <label class="sheet-field">
                  <span>С</span>
                  <input
                    name="tbankDateFrom"
                    type="date"
                    [ngModel]="draftDateFrom()"
                    (ngModelChange)="setDraftDateFrom($event)"
                  >
                </label>

                <label class="sheet-field">
                  <span>По</span>
                  <input
                    name="tbankDateTo"
                    type="date"
                    [ngModel]="draftDateTo()"
                    (ngModelChange)="setDraftDateTo($event)"
                  >
                </label>
              </section>

              <div class="sheet-actions tbank-date-actions">
                <button class="secondary" type="button" (click)="clearDateSheet()">Сбросить</button>
                <button class="secondary" type="button" (click)="closeDateSheet()">Отмена</button>
                <button type="submit">Применить</button>
              </div>
            </form>
          </ng-template>
        </ion-modal>
      </ion-content>
    </div>
  `,
  styles: [`
    :host { display: contents; }

    ion-content {
      --background: var(--otziv-background);
    }

    .tbank-page {
      display: flex;
      height: 100%;
      max-width: 44rem;
      width: 100%;
      min-height: 0;
      margin: 0 auto;
      flex-direction: column;
      gap: var(--otziv-page-gap, 0.65rem);
      padding: var(--otziv-page-padding-y, 0.75rem) var(--otziv-page-padding-x, 0.75rem) calc(var(--otziv-page-padding-bottom, 0.7rem) + env(safe-area-inset-bottom));
      overflow: hidden;
    }

    .mode-scroll,
    .status-scroll,
    .payment-list {
      display: grid;
      grid-auto-flow: column;
      overflow-x: auto;
      scrollbar-width: none;
    }

    .mode-scroll::-webkit-scrollbar,
    .status-scroll::-webkit-scrollbar,
    .payment-list::-webkit-scrollbar {
      display: none;
    }

    .mode-scroll,
    .status-scroll {
      display: flex;
      gap: 0.5rem;
      flex: 0 0 auto;
      margin-inline: -0.15rem;
      overflow-x: auto;
      padding: 0 0.15rem 0.08rem;
      scroll-snap-type: x proximity;
    }

    .metric-tile {
      display: grid;
      grid-template-columns: auto 1fr;
      grid-template-rows: auto auto;
      align-items: center;
      flex: 0 0 7.3rem;
      min-height: 3.45rem;
      min-width: 0;
      border: 1px solid var(--tile-border, rgba(116, 154, 207, 0.28));
      border-radius: 0.9rem;
      padding: 0.48rem 0.6rem;
      color: var(--otziv-dark);
      background: linear-gradient(145deg, var(--tile-bg, #ffffff), rgba(255, 255, 255, 0.96));
      box-shadow: 0 0.5rem 1.2rem rgba(31, 44, 71, 0.055);
      font: inherit;
      scroll-snap-align: start;
      text-align: left;
    }

    .metric-tile.active {
      box-shadow: inset 0 0 0 1px rgba(116, 154, 207, 0.18), 0 0.55rem 1.25rem rgba(31, 44, 71, 0.07);
    }

    .metric-tile .material-icons-sharp {
      grid-row: 1 / 3;
      display: grid;
      width: 1.9rem;
      height: 1.9rem;
      place-items: center;
      border-radius: 0.72rem;
      color: var(--tile-icon, var(--otziv-primary));
      background: var(--tile-icon-bg, rgba(116, 154, 207, 0.14));
      font-size: 1.02rem;
    }

    .metric-tile strong,
    .metric-tile small {
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .metric-tile strong {
      color: var(--otziv-dark);
      align-self: end;
      font-size: 1.02rem;
      font-weight: 900;
      line-height: 1;
    }

    .metric-tile small {
      align-self: start;
      color: var(--otziv-info);
      font-size: 0.63rem;
      font-weight: 900;
      line-height: 1;
    }

    .tone-blue { --tile-border: rgba(116, 154, 207, 0.42); --tile-bg: #f3f8ff; --tile-icon: var(--otziv-primary); --tile-icon-bg: rgba(116, 154, 207, 0.16); }
    .tone-green { --tile-border: rgba(68, 158, 133, 0.34); --tile-bg: #f2fff9; --tile-icon: #3c9d87; --tile-icon-bg: rgba(68, 158, 133, 0.14); }
    .tone-yellow { --tile-border: rgba(218, 168, 36, 0.38); --tile-bg: #fffaf0; --tile-icon: #b7891e; --tile-icon-bg: rgba(218, 168, 36, 0.16); }
    .tone-red { --tile-border: rgba(239, 52, 95, 0.34); --tile-bg: #fff5f7; --tile-icon: var(--otziv-danger); --tile-icon-bg: rgba(239, 52, 95, 0.12); }
    .tone-teal { --tile-border: rgba(47, 159, 149, 0.32); --tile-bg: #f4fffd; --tile-icon: #2f9f95; --tile-icon-bg: rgba(47, 159, 149, 0.13); }
    .tone-gray { --tile-border: rgba(135, 151, 178, 0.26); --tile-bg: #f8fafc; --tile-icon: var(--otziv-info); --tile-icon-bg: rgba(135, 151, 178, 0.13); }

    .search-strip,
    .date-strip,
    .mobile-pagination,
    .launch-card,
    .bank-card,
    .manager-save-card {
      border: 1px solid rgba(135, 151, 178, 0.14);
      border-radius: 1.05rem;
      background: linear-gradient(135deg, rgba(255, 255, 255, 0.96), rgba(244, 247, 252, 0.9));
      box-shadow: 0 0.5rem 1.3rem rgba(31, 44, 71, 0.055);
    }

    .search-strip {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto auto auto;
      align-items: center;
      gap: 0.45rem;
      flex: 0 0 auto;
      border-color: rgba(103, 116, 131, 0.16);
      border-radius: 1rem;
      padding: 0.48rem;
      background: linear-gradient(155deg, var(--otziv-white) 0%, var(--otziv-tone-walk-surface) 100%);
      box-shadow: 0 0.8rem 1.6rem rgba(132, 139, 200, 0.1);
    }

    .search-strip label,
    .date-strip label {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr);
      min-width: 0;
      align-items: center;
      gap: 0.45rem;
      min-height: 2.42rem;
      border: 1px solid rgba(103, 116, 131, 0.14);
      border-radius: 0.82rem;
      padding: 0 0.7rem;
      background: var(--otziv-white);
    }

    .search-strip input,
    .date-strip input,
    .manager-row select {
      min-width: 0;
      width: 100%;
      border: 0;
      outline: 0;
      color: var(--otziv-dark);
      background: transparent;
      font: 900 0.82rem/1 var(--otziv-font-family);
    }

    .search-strip input {
      height: 2.4rem;
    }

    .search-strip .material-icons-sharp,
    .date-strip span {
      color: var(--otziv-info);
      font-size: 1.12rem;
      font-weight: 900;
    }

    .date-strip {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.48rem;
      flex: 0 0 auto;
      padding: 0.5rem;
    }

    .date-strip label {
      height: 2.4rem;
    }

    .date-strip span {
      font-size: 0.66rem;
    }

    .icon-button,
    .page-button {
      display: grid;
      width: 2.45rem;
      height: 2.45rem;
      min-width: 2.45rem;
      place-items: center;
      border: 0;
      border-radius: 0.82rem;
      color: var(--otziv-primary);
      background: linear-gradient(145deg, rgba(108, 155, 207, 0.16) 0%, var(--otziv-white) 92%);
      font: inherit;
      font-weight: 900;
    }

    .date-filter-button.active {
      color: var(--otziv-dark);
      background: linear-gradient(145deg, rgba(247, 208, 96, 0.24) 0%, var(--otziv-white) 92%);
      box-shadow: inset 0 0 0 1px rgba(218, 168, 36, 0.24);
    }

    .inline-alert {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      border: 1px solid rgba(239, 52, 95, 0.24);
      border-radius: 0.9rem;
      padding: 0.65rem 0.75rem;
      color: var(--otziv-danger);
      background: rgba(239, 52, 95, 0.1);
      font: inherit;
      font-size: 0.76rem;
      font-weight: 900;
    }

    .payment-list {
      grid-auto-columns: var(--otziv-board-card-width, minmax(14.3rem, 65vw));
      gap: var(--otziv-list-gap, 0.58rem);
      flex: 1 1 0;
      height: 100%;
      min-height: 0;
      align-items: stretch;
    }

    .payment-card,
    .profile-card,
    .manager-row,
    .empty-state {
      border: 1px solid var(--card-border, rgba(135, 151, 178, 0.2));
      border-radius: 1rem;
      background: linear-gradient(145deg, var(--card-bg, #ffffff), rgba(248, 251, 255, 0.96));
      box-shadow: 0 0.7rem 1.7rem rgba(31, 44, 71, 0.065);
    }

    .payment-card {
      --card-border: var(--tile-border, rgba(135, 151, 178, 0.2));
      --card-bg: var(--tile-bg, #ffffff);
      display: flex;
      height: 100%;
      min-height: 0;
      min-width: 0;
      align-self: stretch;
      flex-direction: column;
      justify-content: flex-start;
      gap: var(--otziv-card-gap, 0.34rem);
      overflow: hidden;
      padding: var(--otziv-card-padding, 0.66rem);
    }

    .payment-card header,
    .profile-card header,
    .bank-card,
    .manager-save-card,
    .manager-row {
      display: grid;
      min-width: 0;
      align-items: center;
      gap: 0.58rem;
    }

    .payment-card header {
      grid-template-columns: minmax(0, 1fr) auto;
    }

    .payment-card h2,
    .profile-card h2,
    .bank-card h2,
    .manager-save-card h2 {
      margin: 0;
      overflow: hidden;
      color: var(--otziv-dark);
      font-family: var(--otziv-card-title-font);
      font-size: 0.98rem;
      font-weight: 900;
      line-height: 1.05;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .payment-card small,
    .profile-card small,
    .bank-card small,
    .manager-row small,
    .manager-save-card p {
      display: block;
      overflow: hidden;
      color: var(--otziv-info);
      font-size: 0.6rem;
      font-weight: 900;
      line-height: 1.25;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .status-pill {
      display: inline-flex;
      min-height: 1.65rem;
      align-items: center;
      justify-content: center;
      border-radius: 999px;
      padding: 0 0.5rem;
      font-size: 0.58rem;
      font-weight: 900;
      white-space: nowrap;
    }

    .status-pill.paid { color: #267d68; background: rgba(68, 158, 133, 0.14); }
    .status-pill.refunded { color: #315f97; background: rgba(116, 154, 207, 0.16); }
    .status-pill.manual { color: #87651d; background: rgba(218, 168, 36, 0.18); }
    .status-pill.failed { color: var(--otziv-danger); background: rgba(239, 52, 95, 0.12); }
    .status-pill.neutral { color: #87651d; background: rgba(218, 168, 36, 0.16); }

    .payment-context-row {
      display: grid;
      grid-template-columns: minmax(0, 1fr);
      gap: 0.42rem;
    }

    .payment-context-row span {
      display: flex;
      min-width: 0;
      min-height: 2.35rem;
      flex-direction: column;
      justify-content: center;
      border: 1px solid rgba(116, 154, 207, 0.18);
      border-radius: 0.75rem;
      padding: 0.34rem 0.48rem;
      background: rgba(255, 255, 255, 0.78);
    }

    .payment-context-row span:only-child {
      grid-column: 1 / -1;
    }

    .amount-row,
    .meta-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.42rem;
    }

    .amount-row span,
    .meta-grid span {
      display: flex;
      min-width: 0;
      min-height: 2.02rem;
      flex-direction: column;
      justify-content: center;
      border: 1px solid rgba(135, 151, 178, 0.18);
      border-radius: 0.75rem;
      padding: 0.34rem 0.48rem;
      background: rgba(255, 255, 255, 0.72);
    }

    .amount-row strong,
    .meta-grid strong,
    .manager-row strong {
      overflow: hidden;
      color: var(--otziv-dark);
      font-size: 0.74rem;
      font-weight: 850;
      line-height: 1.08;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .amount-row small,
    .meta-grid small,
    .payment-context-row small {
      font-size: 0.56rem;
      font-weight: 900;
      line-height: 1.05;
    }

    .payment-context-row strong {
      display: -webkit-box;
      overflow: hidden;
      color: var(--otziv-dark);
      font-size: 0.7rem;
      font-weight: 900;
      line-height: 1.08;
      overflow-wrap: anywhere;
      -webkit-box-orient: vertical;
      -webkit-line-clamp: 2;
    }

    .copy-line {
      display: inline-flex;
      min-height: 1.9rem;
      min-width: 0;
      align-items: center;
      justify-content: center;
      gap: 0.28rem;
      border: 1px solid rgba(135, 151, 178, 0.22);
      border-radius: 999px;
      padding: 0 0.5rem;
      color: #315f97;
      background: linear-gradient(145deg, var(--otziv-white) 0%, var(--otziv-muted-surface) 100%);
      font: inherit;
      font-size: 0.66rem;
      font-weight: 900;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .copy-line.email {
      color: #315f97;
    }

    .copy-line.manual {
      color: #87651d;
      border-color: rgba(218, 168, 36, 0.36);
      background: linear-gradient(145deg, #fffaf0 0%, var(--otziv-white) 100%);
    }

    .copy-line:disabled {
      opacity: 1;
      color: var(--otziv-info);
      background: linear-gradient(145deg, rgba(255, 255, 255, 0.88), rgba(237, 242, 249, 0.86));
    }

    .copy-line .material-icons-sharp {
      flex: 0 0 auto;
      font-size: 0.9rem;
    }

    .error-text {
      display: flex;
      align-items: flex-start;
      gap: 0.32rem;
      margin: 0;
      max-height: 2.2rem;
      overflow: hidden;
      border-radius: 0.7rem;
      padding: 0.38rem 0.48rem;
      color: var(--otziv-danger);
      background: rgba(239, 52, 95, 0.1);
      font-size: 0.58rem;
      font-weight: 900;
      line-height: 1.18;
    }

    .error-text .material-icons-sharp {
      font-size: 0.82rem;
    }

    .manual-note {
      display: grid;
      gap: 0.12rem;
      border: 1px solid rgba(218, 168, 36, 0.22);
      border-radius: 0.75rem;
      padding: 0.42rem 0.5rem;
      background: rgba(255, 250, 240, 0.72);
    }

    .manual-note strong,
    .manual-note small {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .manual-note strong {
      color: var(--otziv-dark);
      font-size: 0.68rem;
      font-weight: 900;
    }

    .manual-note small {
      color: var(--otziv-info);
      font-size: 0.58rem;
      font-weight: 850;
    }

    .payment-card footer {
      display: flex;
      flex-wrap: wrap;
      gap: 0.34rem;
      margin-top: auto;
    }

    .payment-card footer button,
    .manager-save-card button {
      display: inline-flex;
      min-width: 0;
      min-height: 1.82rem;
      align-items: center;
      justify-content: center;
      gap: 0.2rem;
      border: 1px solid rgba(135, 151, 178, 0.24);
      border-radius: 999px;
      padding: 0 0.42rem;
      color: #315f97;
      background: linear-gradient(145deg, var(--otziv-white) 0%, var(--otziv-muted-surface) 100%);
      font: inherit;
      font-size: 0.6rem;
      font-weight: 900;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      flex: 1 1 4.3rem;
    }

    .payment-card footer button:disabled {
      opacity: 1;
      color: var(--otziv-info);
      background: linear-gradient(145deg, rgba(255, 255, 255, 0.82), rgba(237, 242, 249, 0.78));
    }

    .payment-card footer .material-icons-sharp {
      flex: 0 0 auto;
      font-size: 0.88rem;
    }

    .payment-card footer .refund {
      color: #315f97;
      border-color: rgba(218, 168, 36, 0.36);
    }

    .payment-card footer .confirm {
      color: #267d68;
      border-color: rgba(68, 158, 133, 0.34);
    }

    .payment-card footer .receipt {
      color: #315f97;
      border-color: rgba(116, 154, 207, 0.38);
    }

    button:disabled {
      opacity: 0.52;
    }

    .tbank-bottom-controls {
      flex: 0 0 auto;
      border: 1px solid var(--otziv-tone-walk-border);
      background: linear-gradient(155deg, var(--otziv-white) 0%, var(--otziv-tone-walk-surface) 100%);
      box-shadow: 0 0.5rem 1.3rem rgba(31, 44, 71, 0.055);
    }

    .tbank-bottom-controls .expand-list-button,
    .tbank-bottom-controls .lead-pager button {
      background: linear-gradient(145deg, rgba(108, 155, 207, 0.16) 0%, var(--otziv-white) 92%);
    }

    .tbank-bottom-controls .lead-pager span {
      min-width: 3.2rem;
    }

    .tbank-date-sheet-body {
      height: 100%;
      grid-template-rows: auto minmax(0, 1fr) auto;
    }

    .tbank-date-form {
      align-content: start;
      gap: 0.72rem;
    }

    .tbank-date-actions {
      grid-template-columns: repeat(3, minmax(0, 1fr));
    }

    .launch-list,
    .task-list,
    .profile-list,
    .manager-profile-list {
      display: flex;
      flex: 1 1 0;
      min-height: 0;
      flex-direction: column;
      gap: 0.58rem;
      overflow-y: auto;
      padding: 0.02rem 0.02rem 0.25rem;
      scrollbar-width: none;
    }

    .launch-list::-webkit-scrollbar,
    .task-list::-webkit-scrollbar,
    .profile-list::-webkit-scrollbar,
    .manager-profile-list::-webkit-scrollbar {
      display: none;
    }

    .launch-card {
      display: grid;
      flex: 0 0 auto;
      gap: 0.58rem;
      min-width: 0;
      padding: 0.72rem;
    }

    .launch-summary-card header,
    .launch-card h3 {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr);
      align-items: center;
      gap: 0.5rem;
      margin: 0;
    }

    .launch-summary-card header > .material-icons-sharp,
    .launch-card h3 > span {
      display: grid;
      width: 2rem;
      height: 2rem;
      place-items: center;
      border-radius: 0.75rem;
      color: var(--otziv-primary);
      background: rgba(116, 154, 207, 0.14);
      font-size: 1rem;
      font-weight: 950;
    }

    .launch-card h3 {
      color: var(--otziv-dark);
      font-size: 0.82rem;
      font-weight: 950;
      line-height: 1.15;
    }

    .launch-card p,
    .launch-card h2,
    .launch-card small,
    .saving-note {
      margin: 0;
    }

    .launch-card p {
      color: var(--otziv-info);
      font-size: 0.62rem;
      font-weight: 950;
      text-transform: uppercase;
    }

    .launch-card h2 {
      color: var(--otziv-dark);
      font-size: 0.96rem;
      font-weight: 950;
      line-height: 1.08;
    }

    .launch-card small,
    .saving-note {
      color: var(--otziv-info);
      font-size: 0.62rem;
      font-weight: 850;
      line-height: 1.28;
    }

    .saving-note {
      border-radius: 0.72rem;
      padding: 0.42rem 0.55rem;
      background: rgba(116, 154, 207, 0.12);
      text-align: center;
    }

    .segmented {
      display: grid;
      overflow: hidden;
      border: 1px solid rgba(135, 151, 178, 0.22);
      border-radius: 0.78rem;
      background: var(--otziv-field-background);
    }

    .segmented.two {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }

    .segmented.four {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }

    .segmented button {
      display: inline-flex;
      min-width: 0;
      min-height: 2.25rem;
      align-items: center;
      justify-content: center;
      gap: 0.22rem;
      border: 0;
      border-right: 1px solid rgba(135, 151, 178, 0.16);
      border-radius: 0;
      padding: 0 0.36rem;
      color: var(--otziv-info);
      background: transparent;
      font: inherit;
      font-size: 0.64rem;
      font-weight: 900;
      line-height: 1.05;
      text-align: center;
    }

    .segmented.four button:nth-child(2n),
    .segmented button:last-child {
      border-right: 0;
    }

    .segmented.four button:nth-child(n + 3) {
      border-top: 1px solid rgba(135, 151, 178, 0.16);
    }

    .segmented button.active {
      color: #fff;
      background: var(--otziv-primary);
    }

    .segmented button:disabled,
    .toggle-row input:disabled {
      cursor: not-allowed;
      opacity: 0.55;
    }

    .segmented .material-icons-sharp {
      flex: 0 0 auto;
      font-size: 0.9rem;
    }

    .toggle-row {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      align-items: center;
      gap: 0.55rem;
      border: 1px solid rgba(135, 151, 178, 0.16);
      border-radius: 0.78rem;
      padding: 0.52rem 0.58rem;
      background: rgba(255, 255, 255, 0.72);
    }

    .toggle-row > span {
      display: grid;
      gap: 0.06rem;
      min-width: 0;
    }

    .toggle-row strong {
      color: var(--otziv-dark);
      font-size: 0.72rem;
      font-weight: 950;
      line-height: 1.1;
    }

    .toggle-row input {
      width: 1.25rem;
      height: 1.25rem;
      accent-color: var(--otziv-primary);
    }

    .bank-card {
      grid-template-columns: auto minmax(0, 1fr) auto;
      flex: 0 0 auto;
      padding: 0.7rem;
    }

    .bank-card > .material-icons-sharp,
    .profile-card header > .material-icons-sharp {
      display: grid;
      width: 2.15rem;
      height: 2.15rem;
      place-items: center;
      border-radius: 0.78rem;
      color: var(--otziv-primary);
      background: rgba(116, 154, 207, 0.14);
      font-size: 1.1rem;
    }

    .bank-card p,
    .manager-save-card p {
      margin: 0;
      text-transform: uppercase;
    }

    .profile-card {
      display: flex;
      flex-direction: column;
      gap: 0.62rem;
      padding: 0.72rem;
    }

    .profile-card.disabled {
      opacity: 0.68;
    }

    .manual-task-card.disabled {
      opacity: 0.72;
    }

    .profile-card header {
      grid-template-columns: auto minmax(0, 1fr) auto;
    }

    .manager-save-card {
      grid-template-columns: minmax(0, 1fr) auto;
      flex: 0 0 auto;
      padding: 0.72rem;
    }

    .manager-save-card button {
      min-height: 2.35rem;
      border-color: rgba(116, 154, 207, 0.42);
      background: var(--otziv-primary);
      color: #fff;
      padding-inline: 0.78rem;
    }

    .manual-limit-box,
    .profile-field {
      display: grid;
      gap: 0.32rem;
      min-width: 0;
    }

    .manual-limit-box {
      border: 1px solid rgba(135, 151, 178, 0.16);
      border-radius: 0.78rem;
      padding: 0.52rem;
      background: rgba(255, 255, 255, 0.72);
    }

    .manual-limit-box strong {
      color: var(--otziv-dark);
      font-size: 0.74rem;
      font-weight: 950;
    }

    .manual-limit-box small,
    .profile-field span {
      color: var(--otziv-info);
      font-size: 0.6rem;
      font-weight: 900;
      line-height: 1.2;
    }

    .limit-bar {
      overflow: hidden;
      height: 0.42rem;
      border-radius: 999px;
      background: rgba(135, 151, 178, 0.14);
    }

    .limit-bar span {
      display: block;
      height: 100%;
      border-radius: inherit;
      background: #d6a000;
    }

    .profile-field input,
    .profile-field select {
      min-height: 2.2rem;
      min-width: 0;
      border: 1px solid rgba(135, 151, 178, 0.22);
      border-radius: 0.78rem;
      padding: 0 0.66rem;
      color: var(--otziv-dark);
      background: var(--otziv-white);
      font: 900 0.74rem/1 var(--otziv-font-family);
    }

    .task-actions {
      display: flex;
      flex-wrap: wrap;
      gap: 0.34rem;
      margin-top: 0.12rem;
    }

    .task-actions button {
      display: inline-flex;
      min-width: 0;
      min-height: 2rem;
      align-items: center;
      justify-content: center;
      gap: 0.22rem;
      border: 1px solid rgba(135, 151, 178, 0.24);
      border-radius: 999px;
      padding: 0 0.55rem;
      color: #315f97;
      background: linear-gradient(145deg, var(--otziv-white) 0%, var(--otziv-muted-surface) 100%);
      font: inherit;
      font-size: 0.62rem;
      font-weight: 900;
      flex: 1 1 5.6rem;
    }

    .task-actions .material-icons-sharp {
      font-size: 0.9rem;
    }

    .task-actions .confirm {
      color: #267d68;
      border-color: rgba(68, 158, 133, 0.34);
    }

    .task-actions .refund {
      color: var(--otziv-danger);
      border-color: rgba(239, 52, 95, 0.28);
    }

    .manager-row {
      grid-template-columns: minmax(0, 1fr);
      align-items: stretch;
      padding: 0.72rem;
    }

    .manager-row span {
      min-width: 0;
    }

    .manager-row select {
      height: 2.35rem;
      border: 1px solid rgba(135, 151, 178, 0.22);
      border-radius: 0.78rem;
      padding: 0 0.72rem;
      background: var(--otziv-white);
    }

    .empty-state {
      display: grid;
      min-height: 12rem;
      place-items: center;
      padding: 1rem;
      color: var(--otziv-info);
      font-size: 0.82rem;
      font-weight: 900;
      text-align: center;
    }

    .empty-state .material-icons-sharp {
      color: var(--otziv-primary);
      font-size: 2rem;
    }

    :host-context(body.otziv-compact-phone) .tbank-page {
      gap: 0.22rem;
      padding: 0.3rem 0.42rem calc(0.16rem + env(safe-area-inset-bottom));
    }

    :host-context(body.otziv-compact-phone) .payment-list {
      grid-auto-columns: min(14.7rem, 80vw);
      gap: 0.34rem;
    }

    :host-context(body.otziv-compact-phone) .payment-card {
      justify-content: flex-start;
      gap: 0.16rem;
      border-radius: 0.82rem;
      padding: 0.38rem;
    }

    :host-context(body.otziv-compact-phone) .payment-card header {
      gap: 0.28rem;
    }

    :host-context(body.otziv-compact-phone) .payment-card h2 {
      font-size: 0.82rem;
      line-height: 0.98;
    }

    :host-context(body.otziv-compact-phone) .payment-card small {
      font-size: 0.5rem;
      line-height: 1.05;
    }

    :host-context(body.otziv-compact-phone) .status-pill {
      min-height: 1.25rem;
      max-width: 5rem;
      padding: 0 0.36rem;
      overflow: hidden;
      font-size: 0.5rem;
      text-overflow: ellipsis;
    }

    :host-context(body.otziv-compact-phone) .amount-row,
    :host-context(body.otziv-compact-phone) .meta-grid,
    :host-context(body.otziv-compact-phone) .payment-context-row {
      gap: 0.22rem;
    }

    :host-context(body.otziv-compact-phone) .amount-row span,
    :host-context(body.otziv-compact-phone) .meta-grid span,
    :host-context(body.otziv-compact-phone) .payment-context-row span {
      min-height: 1.42rem;
      border-radius: 0.58rem;
      padding: 0.16rem 0.34rem;
    }

    :host-context(body.otziv-compact-phone) .payment-context-row span {
      min-height: 2rem;
      padding-block: 0.26rem;
    }

    :host-context(body.otziv-compact-phone) .amount-row strong,
    :host-context(body.otziv-compact-phone) .meta-grid strong,
    :host-context(body.otziv-compact-phone) .payment-context-row strong {
      font-size: 0.63rem;
      line-height: 1;
    }

    :host-context(body.otziv-compact-phone) .amount-row small,
    :host-context(body.otziv-compact-phone) .meta-grid small,
    :host-context(body.otziv-compact-phone) .payment-context-row small {
      font-size: 0.46rem;
      line-height: 1;
    }

    :host-context(body.otziv-compact-phone) .copy-line {
      min-height: 1.36rem;
      gap: 0.2rem;
      padding: 0 0.4rem;
      font-size: 0.55rem;
    }

    :host-context(body.otziv-compact-phone) .copy-line .material-icons-sharp {
      font-size: 0.76rem;
    }

    :host-context(body.otziv-compact-phone) .manual-note {
      gap: 0.08rem;
      border-radius: 0.58rem;
      padding: 0.26rem 0.36rem;
    }

    :host-context(body.otziv-compact-phone) .manual-note strong {
      font-size: 0.58rem;
    }

    :host-context(body.otziv-compact-phone) .manual-note small {
      font-size: 0.5rem;
    }

    :host-context(body.otziv-compact-phone) .payment-card footer {
      gap: 0.18rem;
    }

    :host-context(body.otziv-compact-phone) .payment-card footer button {
      flex-basis: 3.35rem;
      min-height: 1.28rem;
      border-radius: 0.56rem;
      padding: 0 0.28rem;
      font-size: 0.48rem;
    }

    :host-context(body.otziv-compact-phone) .payment-card footer .material-icons-sharp {
      font-size: 0.68rem;
    }

    :host-context(body.otziv-compact-phone) .error-text {
      max-height: 1.6rem;
      border-radius: 0.56rem;
      padding: 0.24rem 0.34rem;
      font-size: 0.5rem;
      line-height: 1.08;
    }

    :host-context(body.otziv-short-phone) .payment-list {
      grid-auto-columns: min(14.25rem, 82vw);
    }

    :host-context(body.otziv-short-phone) .payment-card {
      gap: 0.12rem;
      padding: 0.32rem;
    }

    :host-context(body.otziv-short-phone) .amount-row span,
    :host-context(body.otziv-short-phone) .meta-grid span,
    :host-context(body.otziv-short-phone) .payment-context-row span {
      min-height: 1.3rem;
    }

    :host-context(body.otziv-short-phone) .payment-context-row span {
      min-height: 1.9rem;
    }

    :host-context(body.otziv-dark-theme) .metric-tile,
    :host-context(body.otziv-dark-theme) .search-strip,
    :host-context(body.otziv-dark-theme) .date-strip,
    :host-context(body.otziv-dark-theme) .tbank-bottom-controls,
    :host-context(body.otziv-dark-theme) .launch-card,
    :host-context(body.otziv-dark-theme) .payment-card,
    :host-context(body.otziv-dark-theme) .profile-card,
    :host-context(body.otziv-dark-theme) .bank-card,
    :host-context(body.otziv-dark-theme) .manager-save-card,
    :host-context(body.otziv-dark-theme) .manager-row,
    :host-context(body.otziv-dark-theme) .empty-state {
      background: linear-gradient(145deg, rgba(31, 38, 41, 0.98), rgba(22, 27, 29, 0.96));
      border-color: rgba(151, 169, 183, 0.18);
      box-shadow: none;
    }

    :host-context(body.otziv-dark-theme) .search-strip label,
    :host-context(body.otziv-dark-theme) .date-strip label,
    :host-context(body.otziv-dark-theme) .amount-row span,
    :host-context(body.otziv-dark-theme) .meta-grid span,
    :host-context(body.otziv-dark-theme) .copy-line,
    :host-context(body.otziv-dark-theme) .manual-note,
    :host-context(body.otziv-dark-theme) .manual-limit-box,
    :host-context(body.otziv-dark-theme) .toggle-row,
    :host-context(body.otziv-dark-theme) .profile-field input,
    :host-context(body.otziv-dark-theme) .profile-field select,
    :host-context(body.otziv-dark-theme) .task-actions button,
    :host-context(body.otziv-dark-theme) .payment-card footer button,
    :host-context(body.otziv-dark-theme) .manager-row select {
      background: #151b1d;
      border-color: rgba(151, 169, 183, 0.2);
      color: var(--otziv-dark);
    }

    @media (max-width: 360px) {
      .tbank-page {
        padding-inline: 0.48rem;
      }

      .mode-scroll,
      .status-scroll {
        grid-auto-columns: minmax(6.65rem, 1fr);
      }

      .payment-list {
        grid-auto-columns: var(--otziv-board-card-width, minmax(13.6rem, 72vw));
      }
    }
  `]
})
export class TbankPage implements OnInit {
  readonly statusOptions: StatusOption[] = [
    { key: 'all', label: 'Все', icon: 'apps', tone: 'blue' },
    { key: 'active', label: 'Активные', icon: 'bolt', tone: 'yellow' },
    { key: 'paid', label: 'Оплачены', icon: 'check_circle', tone: 'green' },
    { key: 'refunded', label: 'Возвраты', icon: 'assignment_return', tone: 'teal' },
    { key: 'failed', label: 'Ошибки', icon: 'error', tone: 'red' },
    { key: 'created', label: 'Созданы', icon: 'schedule', tone: 'gray' },
    { key: 'manual', label: 'Ручные', icon: 'phone_iphone', tone: 'yellow' }
  ];

  readonly mode = signal<TbankMode>('payments');
  readonly links = signal<AdminPaymentLinkResponse[]>([]);
  readonly status = signal<TbankPaymentStatus | null>(null);
  readonly profiles = signal<PaymentProfileResponse[]>([]);
  readonly managerProfiles = signal<ManagerPaymentProfileResponse[]>([]);
  readonly manualTasks = signal<ManualPaymentTaskResponse[]>([]);
  readonly profileAssignments = signal<Record<number, number | null>>({});
  readonly profilePolicies = signal<Record<number, ProfilePolicyDraft>>({});
  readonly runtimeSettings = signal<TbankRuntimeSettings | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly mutatingId = signal<number | null>(null);
  readonly mutatingTaskId = signal<number | null>(null);
  readonly savingProfiles = signal(false);
  readonly savingProfilePolicies = signal(false);
  readonly savingManualTask = signal(false);
  readonly savingRuntimeSettings = signal(false);
  readonly savingRoutingSettings = computed(() => this.savingProfiles() || this.savingProfilePolicies());
  readonly copied = signal<string | null>(null);
  readonly search = signal('');
  readonly statusFilter = signal<PaymentStatusFilter>('all');
  readonly dateFrom = signal('');
  readonly dateTo = signal('');
  readonly dateSheetOpen = signal(false);
  readonly draftDateFrom = signal('');
  readonly draftDateTo = signal('');
  readonly pageIndex = signal(0);
  readonly sortDirection = signal<'asc' | 'desc'>('desc');
  readonly adminTaskManagerId = signal<number | null>(null);
  readonly adminTaskPaymentType = signal<ManualPaymentType>('MOBILE_BANK');
  readonly adminTaskPhone = signal('');
  readonly adminTaskRecipient = signal(DEFAULT_MANUAL_RECIPIENT_NAME);
  readonly adminTaskPaymentUrl = signal(DEFAULT_MANUAL_PAYMENT_URL);
  readonly adminTaskPaymentButtonLabel = signal(DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL);
  readonly adminTaskAmountRubles = signal('');
  readonly adminTaskComment = signal('');
  readonly modeItems = computed<MobileStatusItem[]>(() => [
    {
      key: 'payments',
      title: 'платежи',
      value: `${this.filteredLinks().length}/${this.links().length}`,
      icon: 'receipt_long',
      tone: 'blue'
    },
    {
      key: 'launch',
      title: 'запуск',
      value: this.activeRuntimeMode() === 'TEST' ? 'тест' : 'боевой',
      icon: 'rocket_launch',
      tone: this.activeRuntimeMode() === 'TEST' ? 'yellow' : 'green'
    },
    {
      key: 'tasks',
      title: 'задания',
      value: this.activeManualTaskCount(),
      icon: 'playlist_add_check',
      tone: 'yellow'
    },
    {
      key: 'profiles',
      title: 'профили',
      value: this.profiles().length,
      icon: 'storefront',
      tone: 'green'
    },
    {
      key: 'managers',
      title: 'менеджеры',
      value: this.managerProfiles().length,
      icon: 'manage_accounts',
      tone: 'yellow'
    }
  ]);
  readonly paymentStatusItems = computed<MobileStatusItem[]>(() =>
    this.statusOptions.map((option) => ({
      key: option.key,
      title: option.label,
      value: this.statusCount(option.key),
      icon: option.icon,
      tone: option.tone
    }))
  );

  readonly filteredLinks = computed(() => {
    const search = this.search().trim().toLowerCase();
    const from = this.dateFrom();
    const to = this.dateTo();
    const statusFilter = this.statusFilter();
    const direction = this.sortDirection();
    return this.links()
      .filter((link) => this.matchesSearch(link, search))
      .filter((link) => this.matchesStatusFilter(link, statusFilter))
      .filter((link) => this.matchesDateRange(link, from, to))
      .sort((a, b) => {
        const diff = this.timeValue(a.createdAt) - this.timeValue(b.createdAt);
        return direction === 'asc' ? diff : -diff;
      });
  });

  readonly totalPages = computed(() => Math.max(1, Math.ceil(this.filteredLinks().length / PAGE_SIZE)));

  readonly pageLinks = computed(() => {
    const page = Math.min(this.pageIndex(), this.totalPages() - 1);
    const start = page * PAGE_SIZE;
    return this.filteredLinks().slice(start, start + PAGE_SIZE);
  });

  readonly hasFilters = computed(() => Boolean(
    this.search().trim() || this.statusFilter() !== 'all' || this.dateFrom() || this.dateTo()
  ));

  readonly hasDateFilter = computed(() => Boolean(this.dateFrom() || this.dateTo()));

  readonly manualPendingLinks = computed(() => this.links().filter((link) => {
    return this.isManualPayment(link) && (link.status === 'WAITING_MANUAL_PAYMENT' || link.status === 'MANUAL_REPORTED');
  }));

  readonly activeManualTaskCount = computed(() => {
    return this.manualTasks().filter((task) => task.status === 'ACTIVE').length;
  });

  readonly canCreateManualTask = computed(() => {
    const hasTarget = this.adminTaskPaymentType() === 'EXTERNAL_LINK'
      ? Boolean(this.adminTaskPaymentUrl().trim()) && Boolean(this.adminTaskRecipient().trim())
      : Boolean(this.adminTaskPhone().trim()) && Boolean(this.adminTaskRecipient().trim());
    return !this.savingManualTask()
      && this.adminTaskManagerId() != null
      && hasTarget
      && this.adminTaskTargetKopecks() > 0;
  });

  readonly tbankReadyForClientPayments = computed(() => {
    const status = this.status();
    const settings = this.runtimeSettings();
    return Boolean(settings?.tbankEnabled
      && settings.paymentLinksEnabled
      && settings.managerUiEnabled
      && status?.hasCredentials);
  });

  readonly activeRuntimeMode = computed<TbankRuntimeMode>(() => {
    return this.runtimeSettings()?.runtimeMode ?? this.status()?.runtimeMode ?? (this.status()?.testMode ? 'TEST' : 'LIVE');
  });

  readonly paymentPageModeDescription = computed(() => {
    switch (this.runtimeSettings()?.paymentPageMode ?? 'SBP_PRIMARY') {
      case 'BANK_PRIMARY':
        return 'Сначала форма банка, СБП запасным способом.';
      case 'SBP_ONLY':
        return 'Только кнопка СБП, форма банка скрыта.';
      case 'BANK_ONLY':
        return 'Только форма банка: карта, T-Pay и способы банка.';
      default:
        return 'Сначала СБП, форма банка запасным способом.';
    }
  });

  readonly fastBankMethodDescription = computed(() => {
    const settings = this.runtimeSettings();
    if (!settings) {
      return 'Настройки загружаются.';
    }
    const methods = [
      settings.tpayEnabled ? 'T-Pay' : '',
      settings.sberpayEnabled ? 'SberPay' : '',
      settings.mirpayEnabled ? 'Mir Pay' : ''
    ].filter(Boolean);
    return methods.length ? `Показываем: ${methods.join(', ')}.` : 'Быстрые методы выключены, остается карта.';
  });

  readonly launchStateTitle = computed(() => {
    const settings = this.runtimeSettings();
    if (!settings) {
      return 'Настройки загружаются';
    }
    if (settings.runtimeMode === 'TEST') {
      return 'Тестовый контур';
    }
    if (settings.paymentInstructionSource !== 'TBANK_LINK') {
      return 'Боевой терминал, клиенты на Альфа';
    }
    if (!settings.applyConfirmedPayments) {
      return 'T-Bank клиентам, заказы вручную';
    }
    return 'T-Bank полностью включен';
  });

  readonly launchStateDescription = computed(() => {
    const settings = this.runtimeSettings();
    if (!settings) {
      return 'Получаю состояние из backend.';
    }
    if (settings.runtimeMode === 'TEST') {
      return 'Можно проверять ссылки и возвраты. Клиентам остаются старые счета.';
    }
    if (settings.paymentInstructionSource !== 'TBANK_LINK') {
      return 'Терминалы готовы для ручных тестов, но автоответчик отправляет старый текст.';
    }
    if (!settings.applyConfirmedPayments) {
      return 'Клиенты получают ссылки T-Bank, подтвержденные платежи попадают в журнал.';
    }
    return 'Webhook CONFIRMED переводит заказ в оплату и сохраняет e-mail плательщика.';
  });

  readonly dateFilterLabel = computed(() => {
    const from = this.dateFrom();
    const to = this.dateTo();
    if (from && to) {
      return `${this.dateInputLabel(from)} - ${this.dateInputLabel(to)}`;
    }
    if (from) {
      return `с ${this.dateInputLabel(from)}`;
    }
    if (to) {
      return `по ${this.dateInputLabel(to)}`;
    }
    return 'не выбран';
  });

  constructor(
    private readonly api: ApiService,
    private readonly confirm: MobileConfirmService,
    private readonly externalLink: MobileExternalLinkService
  ) {}

  ngOnInit(): void {
    void this.load();
  }

  async refresh(event: RefresherCustomEvent): Promise<void> {
    await this.load();
    event.target.complete();
  }

  async load(): Promise<void> {
    if (this.loading()) {
      return;
    }

    this.loading.set(true);
    this.error.set(null);
    try {
      const [status, links, profiles, manualTasks, runtimeSettings] = await Promise.all([
        firstValueFrom(this.api.getTbankStatus()),
        firstValueFrom(this.api.getAdminTbankPaymentLinks()),
        firstValueFrom(this.api.getAdminTbankPaymentProfiles()),
        firstValueFrom(this.api.getAdminManualPaymentTasks()),
        firstValueFrom(this.api.getAdminTbankRuntimeSettings())
      ]);
      this.status.set(status);
      this.links.set(links ?? []);
      this.manualTasks.set(manualTasks ?? []);
      this.runtimeSettings.set(runtimeSettings);
      this.applyProfilesState(profiles.profiles, profiles.managers);
      this.keepPageInRange();
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Не удалось загрузить T-Bank.'));
    } finally {
      this.loading.set(false);
    }
  }

  setMode(mode: TbankMode): void {
    this.mode.set(mode);
  }

  selectModeItem(mode: string): void {
    this.setMode(mode as TbankMode);
  }

  setSearch(value: string): void {
    this.search.set(value ?? '');
    this.resetPage();
  }

  setStatusFilter(value: PaymentStatusFilter): void {
    this.statusFilter.set(value);
    this.resetPage();
  }

  selectPaymentStatus(value: string): void {
    this.setStatusFilter(value as PaymentStatusFilter);
  }

  setDateFrom(value: string): void {
    this.dateFrom.set(value ?? '');
    this.resetPage();
  }

  setDateTo(value: string): void {
    this.dateTo.set(value ?? '');
    this.resetPage();
  }

  openDateSheet(): void {
    this.draftDateFrom.set(this.dateFrom());
    this.draftDateTo.set(this.dateTo());
    this.dateSheetOpen.set(true);
  }

  closeDateSheet(): void {
    this.dateSheetOpen.set(false);
  }

  setDraftDateFrom(value: string): void {
    this.draftDateFrom.set(value ?? '');
  }

  setDraftDateTo(value: string): void {
    this.draftDateTo.set(value ?? '');
  }

  clearDateSheet(): void {
    this.draftDateFrom.set('');
    this.draftDateTo.set('');
  }

  applyDateSheet(): void {
    this.dateFrom.set(this.draftDateFrom());
    this.dateTo.set(this.draftDateTo());
    this.resetPage();
    this.closeDateSheet();
  }

  resetFilters(): void {
    this.search.set('');
    this.statusFilter.set('all');
    this.dateFrom.set('');
    this.dateTo.set('');
    this.resetPage();
  }

  resetPage(): void {
    this.pageIndex.set(0);
  }

  previousPage(): void {
    this.pageIndex.update((page) => Math.max(0, page - 1));
  }

  nextPage(): void {
    this.pageIndex.update((page) => Math.min(this.totalPages() - 1, page + 1));
  }

  toggleSort(): void {
    this.sortDirection.update((direction) => direction === 'desc' ? 'asc' : 'desc');
    this.resetPage();
  }

  async setRuntimeMode(mode: TbankRuntimeMode): Promise<void> {
    const current = this.runtimeSettings();
    if (!current || this.savingRuntimeSettings() || current.runtimeMode === mode) {
      return;
    }
    if (mode === 'LIVE') {
      const confirmed = await this.confirm.confirm({
        title: 'Боевой контур',
        message: 'Переключить T-Bank на рабочие терминалы? Клиентам ссылки T-Bank не уйдут, пока источник счетов остается «Альфа / текст».',
        confirmText: 'Включить'
      });
      if (!confirmed) {
        return;
      }
    }
    const patch: UpdateTbankRuntimeSettingsRequest = { runtimeMode: mode };
    if (mode === 'TEST') {
      patch.paymentInstructionSource = 'MANAGER_TEXT';
      patch.applyConfirmedPayments = false;
    }
    await this.saveRuntimeSettings(patch);
  }

  async setPaymentInstructionSource(source: PaymentInstructionSource): Promise<void> {
    const current = this.runtimeSettings();
    if (!current || this.savingRuntimeSettings() || current.paymentInstructionSource === source) {
      return;
    }
    if (source === 'TBANK_LINK') {
      if (current.runtimeMode !== 'LIVE') {
        this.error.set('Сначала включите боевой контур.');
        return;
      }
      if (!this.tbankReadyForClientPayments()) {
        this.error.set('T-Bank еще не готов: проверьте API, ссылки, UI менеджера и секреты терминалов.');
        return;
      }
      const confirmed = await this.confirm.confirm({
        title: 'T-Bank клиентам',
        message: 'Отправлять клиентам ссылки T-Bank вместо старого текста/Альфа?',
        confirmText: 'Переключить'
      });
      if (!confirmed) {
        return;
      }
    }
    await this.saveRuntimeSettings({ paymentInstructionSource: source });
  }

  async setApplyConfirmedPayments(enabled: boolean): Promise<void> {
    const current = this.runtimeSettings();
    if (!current || this.savingRuntimeSettings() || current.applyConfirmedPayments === enabled) {
      return;
    }
    if (enabled) {
      if (current.runtimeMode !== 'LIVE') {
        this.error.set('В тестовом режиме нельзя автоматически оплачивать заказы.');
        return;
      }
      const confirmed = await this.confirm.confirm({
        title: 'Автооплата заказов',
        message: 'После webhook CONFIRMED заказ будет переходить в оплату автоматически.',
        confirmText: 'Включить'
      });
      if (!confirmed) {
        return;
      }
    }
    await this.saveRuntimeSettings({ applyConfirmedPayments: enabled });
  }

  async setPaymentPageMode(mode: TbankPaymentPageMode): Promise<void> {
    const current = this.runtimeSettings();
    if (!current || this.savingRuntimeSettings() || current.paymentPageMode === mode) {
      return;
    }
    await this.saveRuntimeSettings({ paymentPageMode: mode });
  }

  async setCoreSwitch(
    field: 'tbankEnabled' | 'paymentLinksEnabled' | 'managerUiEnabled',
    enabled: boolean
  ): Promise<void> {
    const current = this.runtimeSettings();
    if (!current || this.savingRuntimeSettings() || current[field] === enabled) {
      return;
    }
    const patch: UpdateTbankRuntimeSettingsRequest = {};
    patch[field] = enabled;
    await this.saveRuntimeSettings(patch);
  }

  async setFastBankMethodSwitch(
    field: 'tpayEnabled' | 'sberpayEnabled' | 'mirpayEnabled',
    enabled: boolean
  ): Promise<void> {
    const current = this.runtimeSettings();
    if (!current || this.savingRuntimeSettings() || current[field] === enabled) {
      return;
    }
    const patch: UpdateTbankRuntimeSettingsRequest = {};
    patch[field] = enabled;
    await this.saveRuntimeSettings(patch);
  }

  setManagerProfile(managerId: number, value: string | number | null): void {
    const profileId = Number(value);
    this.profileAssignments.update((assignments) => ({
      ...assignments,
      [managerId]: Number.isFinite(profileId) && profileId > 0 ? profileId : null
    }));
  }

  selectedProfileId(manager: ManagerPaymentProfileResponse): number | null {
    return this.profileAssignments()[manager.managerId] ?? manager.paymentProfileId ?? null;
  }

  profilePolicy(profileId: number): ProfilePolicyDraft {
    return this.policyDraft(profileId);
  }

  profilePolicyLabel(profile: PaymentProfileResponse): string {
    const draft = this.policyDraft(profile.id);
    if (draft.paymentPolicy !== 'MANUAL_UNTIL_LIMIT_THEN_TBANK') {
      return 'Только T-Bank';
    }
    return draft.manualPaymentType === 'EXTERNAL_LINK' ? 'Ссылка до лимита' : 'Телефон до лимита';
  }

  profileManualUsagePercent(profile: PaymentProfileResponse): number {
    const limit = this.profileManualLimitKopecks(profile);
    if (!limit) {
      return 0;
    }
    return Math.min(100, Math.round((profile.manualMonthlyUsedKopecks / limit) * 100));
  }

  profileManualLimitKopecks(profile: PaymentProfileResponse): number {
    return this.manualLimitKopecksFromDraft(this.policyDraft(profile.id).manualMonthlyLimitRubles);
  }

  setProfilePolicy(profileId: number, value: PaymentPolicy): void {
    this.updateProfilePolicyDraft(profileId, { paymentPolicy: value });
  }

  setProfileManualPaymentType(profileId: number, value: ManualPaymentType): void {
    const patch: Partial<ProfilePolicyDraft> = { manualPaymentType: value };
    if (value === 'EXTERNAL_LINK' && !this.policyDraft(profileId).manualPaymentUrl.trim()) {
      patch.manualPaymentUrl = DEFAULT_MANUAL_PAYMENT_URL;
    }
    if (!this.policyDraft(profileId).manualRecipientName.trim()) {
      patch.manualRecipientName = DEFAULT_MANUAL_RECIPIENT_NAME;
    }
    this.updateProfilePolicyDraft(profileId, patch);
  }

  setProfileManualPhone(profileId: number, value: string): void {
    this.updateProfilePolicyDraft(profileId, { manualPhone: value ?? '' });
  }

  setProfileManualRecipient(profileId: number, value: string): void {
    this.updateProfilePolicyDraft(profileId, { manualRecipientName: value ?? '' });
  }

  setProfileManualPaymentUrl(profileId: number, value: string): void {
    this.updateProfilePolicyDraft(profileId, { manualPaymentUrl: value ?? '' });
  }

  setProfileManualPaymentButtonLabel(profileId: number, value: string): void {
    this.updateProfilePolicyDraft(profileId, { manualPaymentButtonLabel: value ?? '' });
  }

  setProfileManualLimit(profileId: number, value: string | number | null): void {
    this.updateProfilePolicyDraft(profileId, {
      manualMonthlyLimitRubles: value == null ? '' : String(value)
    });
  }

  setAdminTaskManagerId(value: number | string | null): void {
    const id = value == null || value === '' ? NaN : Number(value);
    this.adminTaskManagerId.set(Number.isFinite(id) && id > 0 ? id : null);
  }

  setAdminTaskPaymentType(value: ManualPaymentType): void {
    this.adminTaskPaymentType.set(value);
    if (value === 'EXTERNAL_LINK' && !this.adminTaskPaymentUrl().trim()) {
      this.adminTaskPaymentUrl.set(DEFAULT_MANUAL_PAYMENT_URL);
    }
    if (!this.adminTaskRecipient().trim()) {
      this.adminTaskRecipient.set(DEFAULT_MANUAL_RECIPIENT_NAME);
    }
  }

  setAdminTaskPhone(value: string | null): void {
    this.adminTaskPhone.set(value ?? '');
  }

  setAdminTaskRecipient(value: string | null): void {
    this.adminTaskRecipient.set(value ?? '');
  }

  setAdminTaskPaymentUrl(value: string | null): void {
    this.adminTaskPaymentUrl.set(value ?? '');
  }

  setAdminTaskPaymentButtonLabel(value: string | null): void {
    this.adminTaskPaymentButtonLabel.set(value ?? '');
  }

  setAdminTaskAmountRubles(value: string | number | null): void {
    this.adminTaskAmountRubles.set(value == null ? '' : String(value));
  }

  setAdminTaskComment(value: string | null): void {
    this.adminTaskComment.set(value ?? '');
  }

  async createManualTask(): Promise<void> {
    if (!this.canCreateManualTask()) {
      return;
    }

    this.savingManualTask.set(true);
    this.error.set(null);
    try {
      const task = await firstValueFrom(this.api.createAdminManualPaymentTask({
        managerId: this.adminTaskManagerId(),
        manualPaymentType: this.adminTaskPaymentType(),
        manualPhone: this.adminTaskPhone().trim(),
        manualRecipientName: this.adminTaskRecipient().trim() || DEFAULT_MANUAL_RECIPIENT_NAME,
        manualPaymentUrl: this.adminTaskPaymentUrl().trim() || DEFAULT_MANUAL_PAYMENT_URL,
        manualPaymentButtonLabel: this.adminTaskPaymentButtonLabel().trim() || DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL,
        targetAmountKopecks: this.adminTaskTargetKopecks(),
        comment: this.adminTaskComment().trim() || null
      }));
      this.manualTasks.update((tasks) => [task, ...tasks]);
      this.adminTaskPhone.set('');
      this.adminTaskRecipient.set(DEFAULT_MANUAL_RECIPIENT_NAME);
      this.adminTaskPaymentType.set('MOBILE_BANK');
      this.adminTaskPaymentUrl.set(DEFAULT_MANUAL_PAYMENT_URL);
      this.adminTaskPaymentButtonLabel.set(DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL);
      this.adminTaskAmountRubles.set('');
      this.adminTaskComment.set('');
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Не удалось создать ручное задание.'));
    } finally {
      this.savingManualTask.set(false);
    }
  }

  async saveProfileAssignments(): Promise<void> {
    if (this.savingProfiles()) {
      return;
    }

    const assignments: ManagerPaymentProfileAssignmentRequest[] = this.managerProfiles().map((manager) => ({
      managerId: manager.managerId,
      paymentProfileId: this.selectedProfileId(manager)
    }));

    this.savingProfiles.set(true);
    this.error.set(null);
    try {
      const state = await firstValueFrom(this.api.updateAdminTbankPaymentProfileAssignments(assignments));
      this.applyProfilesState(state.profiles, state.managers);
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Не удалось сохранить платежные профили.'));
    } finally {
      this.savingProfiles.set(false);
    }
  }

  async saveProfilePolicies(): Promise<void> {
    if (this.savingProfilePolicies()) {
      return;
    }
    this.savingProfilePolicies.set(true);
    this.error.set(null);
    try {
      const state = await firstValueFrom(this.api.updateAdminPaymentProfilePolicies(this.profilePolicyRequest()));
      this.applyProfilesState(state.profiles, state.managers);
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Не удалось сохранить политики оплаты.'));
    } finally {
      this.savingProfilePolicies.set(false);
    }
  }

  async saveRoutingSettings(): Promise<void> {
    if (this.savingRoutingSettings()) {
      return;
    }
    const assignments: ManagerPaymentProfileAssignmentRequest[] = this.managerProfiles().map((manager) => ({
      managerId: manager.managerId,
      paymentProfileId: this.selectedProfileId(manager)
    }));
    this.savingProfilePolicies.set(true);
    this.savingProfiles.set(true);
    this.error.set(null);
    try {
      await firstValueFrom(this.api.updateAdminPaymentProfilePolicies(this.profilePolicyRequest()));
      const state = await firstValueFrom(this.api.updateAdminTbankPaymentProfileAssignments(assignments));
      this.applyProfilesState(state.profiles, state.managers);
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Не удалось сохранить маршрутизацию оплат.'));
    } finally {
      this.savingProfilePolicies.set(false);
      this.savingProfiles.set(false);
    }
  }

  async cancel(link: AdminPaymentLinkResponse): Promise<void> {
    if (!link.refundable || this.mutatingId()) {
      return;
    }

    const confirmed = await this.confirm.confirm({
      title: 'Возврат платежа',
      message: `Вернуть платеж T-Bank ${link.tbankPaymentId || link.id} на сумму ${this.money(link.amount)}?`,
      confirmText: 'Вернуть',
      danger: true
    });
    if (!confirmed) {
      return;
    }

    this.mutatingId.set(link.id);
    this.error.set(null);
    try {
      const updated = await firstValueFrom(this.api.cancelAdminTbankPaymentLink(link.id));
      this.links.update((links) => links.map((item) => item.id === updated.id ? updated : item));
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Не удалось выполнить возврат.'));
    } finally {
      this.mutatingId.set(null);
    }
  }

  async confirmManual(link: AdminPaymentLinkResponse): Promise<void> {
    if (!this.canConfirmManual(link) || this.mutatingId()) {
      return;
    }
    const confirmed = await this.confirm.confirm({
      title: 'Подтвердить перевод',
      message: `Подтвердить ручную оплату по заказу №${link.orderId ?? '-'} на сумму ${this.money(link.amount)}?`,
      confirmText: 'Подтвердить'
    });
    if (!confirmed) {
      return;
    }

    this.mutatingId.set(link.id);
    this.error.set(null);
    try {
      const updated = await firstValueFrom(this.api.confirmAdminManualPaymentLink(link.id));
      this.links.update((links) => links.map((item) => item.id === updated.id ? updated : item));
      await this.loadProfilesOnly();
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Не удалось подтвердить ручную оплату.'));
    } finally {
      this.mutatingId.set(null);
    }
  }

  async markManualReceipt(link: AdminPaymentLinkResponse): Promise<void> {
    if (!this.canMarkManualReceipt(link) || this.mutatingId()) {
      return;
    }

    this.mutatingId.set(link.id);
    this.error.set(null);
    try {
      const updated = await firstValueFrom(this.api.markAdminManualPaymentReceipt(link.id));
      this.links.update((links) => links.map((item) => item.id === updated.id ? updated : item));
      await this.loadProfilesOnly();
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Не удалось отметить чек.'));
    } finally {
      this.mutatingId.set(null);
    }
  }

  async updateManualTaskStatus(task: ManualPaymentTaskResponse, status: ManualPaymentTaskStatus): Promise<void> {
    if (!task?.id || this.mutatingTaskId()) {
      return;
    }

    this.mutatingTaskId.set(task.id);
    this.error.set(null);
    try {
      const updated = await firstValueFrom(this.api.updateAdminManualPaymentTaskStatus(task.id, status));
      this.manualTasks.update((tasks) => tasks.map((item) => item.id === updated.id ? updated : item));
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Не удалось обновить ручное задание.'));
    } finally {
      this.mutatingTaskId.set(null);
    }
  }

  copy(value: string | null | undefined, key: string): void {
    const text = value?.trim();
    if (!text) {
      return;
    }

    void navigator.clipboard.writeText(text).then(() => {
      this.copied.set(key);
      window.setTimeout(() => {
        if (this.copied() === key) {
          this.copied.set(null);
        }
      }, 1400);
    }).catch(() => {
      this.error.set('Не удалось скопировать текст.');
    });
  }

  openUrl(value: string | null | undefined): void {
    void this.externalLink.open(value);
  }

  statusCount(filter: PaymentStatusFilter): number {
    return this.links().filter((link) => this.matchesStatusFilter(link, filter)).length;
  }

  paymentTitle(link: AdminPaymentLinkResponse): string {
    return link.companyTitle || link.filialTitle || `Заказ ${link.orderId ?? '-'}`;
  }

  paymentSubtitle(link: AdminPaymentLinkResponse): string {
    return [link.filialTitle, link.description].filter(Boolean).join(' · ') || 'T-Bank';
  }

  paymentAddress(link: AdminPaymentLinkResponse): string {
    return link.filialTitle?.trim() || '';
  }

  paymentCategory(link: AdminPaymentLinkResponse): string {
    return link.description?.trim() || '';
  }

  paymentMethodLabel(link: AdminPaymentLinkResponse): string {
    if (this.isManualPayment(link)) {
      return this.isExternalManualPayment(link) ? 'Ссылка Альфа' : 'Телефон';
    }
    return link.paymentMethod === 'SBP_QR' ? 'СБП' : 'Форма банка';
  }

  manualSourceLabel(link: AdminPaymentLinkResponse): string {
    if (link.manualSource === 'MANUAL_TASK') {
      return link.manualTaskTitle ? `Задание: ${link.manualTaskTitle}` : 'Ручное задание';
    }
    return this.isManualPayment(link) ? 'Лимит профиля' : '';
  }

  manualTaskTargetLine(task: ManualPaymentTaskResponse): string {
    if (this.isExternalManualTask(task)) {
      return `${task.manualPaymentUrl || DEFAULT_MANUAL_PAYMENT_URL} · ${task.managerTitle || task.username}`;
    }
    return `${task.manualPhone || 'телефон не указан'} · ${task.managerTitle || task.username}`;
  }

  manualTaskTitle(task: ManualPaymentTaskResponse): string {
    return task.manualRecipientName || 'Получатель не указан';
  }

  manualTaskProgressPercent(task: ManualPaymentTaskResponse): number {
    if (!task.targetAmountKopecks) {
      return 0;
    }
    return Math.min(100, Math.round((task.reservedAmountKopecks / task.targetAmountKopecks) * 100));
  }

  manualTaskStatusLabel(status: string): string {
    switch (status) {
      case 'ACTIVE':
        return 'Активно';
      case 'PAUSED':
        return 'Пауза';
      case 'COMPLETED':
        return 'Выполнено';
      case 'CANCELED':
        return 'Отменено';
      default:
        return status || 'Неизвестно';
    }
  }

  receiptStatusLabel(link: AdminPaymentLinkResponse): string {
    if (!this.isManualPayment(link)) {
      return '';
    }
    return link.receiptStatus === 'MARKED' ? 'Чек отмечен' : 'Чек ожидает';
  }

  isManualPayment(link: AdminPaymentLinkResponse): boolean {
    return link.paymentMethod === 'MANUAL_MOBILE_BANK' || link.paymentMethod === 'MANUAL_EXTERNAL_LINK';
  }

  isExternalManualPayment(link: AdminPaymentLinkResponse): boolean {
    return this.isManualPayment(link)
      && (link.manualPaymentType === 'EXTERNAL_LINK' || link.paymentMethod === 'MANUAL_EXTERNAL_LINK');
  }

  isExternalManualTask(task: ManualPaymentTaskResponse): boolean {
    return task.manualPaymentType === 'EXTERNAL_LINK';
  }

  canConfirmManual(link: AdminPaymentLinkResponse): boolean {
    return this.isManualPayment(link)
      && (link.status === 'WAITING_MANUAL_PAYMENT' || link.status === 'MANUAL_REPORTED');
  }

  canMarkManualReceipt(link: AdminPaymentLinkResponse): boolean {
    return this.isManualPayment(link) && link.status === 'CONFIRMED' && link.receiptStatus !== 'MARKED';
  }

  statusLabel(status: string): string {
    const labels: Record<string, string> = {
      CREATED: 'Создана',
      INITIATED: 'Форма',
      AUTHORIZED: 'Авторизован',
      WAITING_MANUAL_PAYMENT: 'Ждет перевод',
      MANUAL_REPORTED: 'Клиент оплатил',
      TEST_CONFIRMED: 'Тест оплачен',
      CONFIRMED: 'Оплачен',
      REJECTED: 'Отклонен',
      CANCELED: 'Отменен',
      REVERSED: 'Отменен',
      PARTIAL_REVERSED: 'Частично отменен',
      REFUNDED: 'Возвращен',
      PARTIAL_REFUNDED: 'Частичный возврат',
      EXPIRED: 'Истек',
      FAILED: 'Ошибка'
    };
    return labels[status] ?? status;
  }

  statusClass(status: string): string {
    if (this.isPaid(status)) {
      return 'paid';
    }
    if (status === 'WAITING_MANUAL_PAYMENT' || status === 'MANUAL_REPORTED') {
      return 'manual';
    }
    if (this.isRefunded(status) || status === 'CANCELED') {
      return 'refunded';
    }
    if (status === 'REJECTED' || status === 'FAILED' || status === 'EXPIRED') {
      return 'failed';
    }
    return 'neutral';
  }

  rowTone(link: AdminPaymentLinkResponse): Tone {
    if (this.isPaid(link.status)) {
      return 'green';
    }
    if (this.isManualPayment(link) && (link.status === 'WAITING_MANUAL_PAYMENT' || link.status === 'MANUAL_REPORTED')) {
      return 'yellow';
    }
    if (this.isRefunded(link.status) || link.status === 'CANCELED') {
      return 'teal';
    }
    if (link.status === 'REJECTED' || link.status === 'FAILED' || link.status === 'EXPIRED') {
      return 'red';
    }
    if (link.status === 'CREATED' || link.status === 'INITIATED') {
      return 'yellow';
    }
    return 'blue';
  }

  money(value: number | null | undefined): string {
    return `${Math.round(Number(value || 0)).toLocaleString('ru-RU')} руб.`;
  }

  formatKopecks(value?: number | null): string {
    return `${new Intl.NumberFormat('ru-RU', {
      minimumFractionDigits: 0,
      maximumFractionDigits: 2
    }).format((value ?? 0) / 100)} руб.`;
  }

  shortDate(value: string | null | undefined): string {
    if (!value) {
      return '-';
    }
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      return value;
    }
    return date.toLocaleString('ru-RU', {
      day: '2-digit',
      month: '2-digit',
      hour: '2-digit',
      minute: '2-digit'
    });
  }

  private applyProfilesState(
    profiles: PaymentProfileResponse[],
    managers: ManagerPaymentProfileResponse[]
  ): void {
    this.profiles.set(profiles ?? []);
    this.managerProfiles.set(managers ?? []);
    this.profileAssignments.set(Object.fromEntries(
      (managers ?? []).map((manager) => [manager.managerId, manager.paymentProfileId ?? null])
    ));
    this.profilePolicies.set(Object.fromEntries(
      (profiles ?? []).map((profile) => [profile.id, this.profileToPolicyDraft(profile)])
    ));
  }

  private matchesSearch(link: AdminPaymentLinkResponse, search: string): boolean {
    if (!search) {
      return true;
    }
    return [
      link.companyTitle,
      link.filialTitle,
      link.description,
      link.orderId,
      link.tbankPaymentId,
      link.tbankOrderId,
      link.paymentProfileName,
      link.tbankTerminalKey,
      link.payerEmail,
      link.manualPhone,
      link.manualRecipientName,
      link.manualPaymentUrl,
      link.manualPaymentButtonLabel,
      link.manualTaskTitle,
      link.manualComment,
      link.lastError,
      this.statusLabel(link.status)
    ].join(' ').toLowerCase().includes(search);
  }

  private matchesStatusFilter(link: AdminPaymentLinkResponse, filter: PaymentStatusFilter): boolean {
    switch (filter) {
      case 'active':
        return link.status === 'CREATED'
          || link.status === 'INITIATED'
          || link.status === 'AUTHORIZED'
          || link.status === 'WAITING_MANUAL_PAYMENT'
          || link.status === 'MANUAL_REPORTED';
      case 'paid':
        return this.isPaid(link.status);
      case 'refunded':
        return this.isRefunded(link.status) || link.status === 'CANCELED';
      case 'failed':
        return link.status === 'REJECTED' || link.status === 'FAILED' || link.status === 'EXPIRED';
      case 'created':
        return link.status === 'CREATED';
      case 'manual':
        return this.isManualPayment(link);
      default:
        return true;
    }
  }

  private async saveRuntimeSettings(request: UpdateTbankRuntimeSettingsRequest): Promise<void> {
    const previous = this.runtimeSettings();
    if (!previous) {
      return;
    }
    const optimistic = { ...previous, ...request };
    optimistic.testMode = optimistic.runtimeMode === 'TEST';
    optimistic.clientTbankEnabled = optimistic.paymentInstructionSource === 'TBANK_LINK';
    this.runtimeSettings.set(optimistic);
    this.savingRuntimeSettings.set(true);
    this.error.set(null);
    try {
      const settings = await firstValueFrom(this.api.updateAdminTbankRuntimeSettings(request));
      this.runtimeSettings.set(settings);
      this.status.update((status) => status ? {
        ...status,
        enabled: settings.tbankEnabled,
        paymentLinksEnabled: settings.paymentLinksEnabled,
        managerUiEnabled: settings.managerUiEnabled,
        applyConfirmedPayments: settings.applyConfirmedPayments,
        runtimeMode: settings.runtimeMode,
        testMode: settings.testMode
      } : status);
    } catch (error) {
      this.runtimeSettings.set(previous);
      this.error.set(this.errorMessage(error, 'Не удалось сохранить настройки T-Bank.'));
    } finally {
      this.savingRuntimeSettings.set(false);
    }
  }

  private async loadProfilesOnly(): Promise<void> {
    try {
      const profiles = await firstValueFrom(this.api.getAdminTbankPaymentProfiles());
      this.applyProfilesState(profiles.profiles, profiles.managers);
    } catch {
      // Основная операция уже выполнена, не перебиваем ее результат ошибкой фонового обновления.
    }
  }

  private profilePolicyRequest(): PaymentProfilePolicyRequest[] {
    return this.profiles().map((profile) => {
      const draft = this.policyDraft(profile.id);
      const manualMonthlyLimitKopecks = this.manualLimitKopecksFromDraft(draft.manualMonthlyLimitRubles);
      return {
        profileId: profile.id,
        paymentPolicy: draft.paymentPolicy,
        manualPaymentType: draft.manualPaymentType,
        manualPhone: draft.manualPhone.trim(),
        manualRecipientName: draft.manualRecipientName.trim() || DEFAULT_MANUAL_RECIPIENT_NAME,
        manualPaymentUrl: draft.manualPaymentUrl.trim() || DEFAULT_MANUAL_PAYMENT_URL,
        manualPaymentButtonLabel: draft.manualPaymentButtonLabel.trim() || DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL,
        manualMonthlySoftLimitKopecks: manualMonthlyLimitKopecks,
        manualMonthlyHardLimitKopecks: manualMonthlyLimitKopecks
      };
    });
  }

  private updateProfilePolicyDraft(profileId: number, patch: Partial<ProfilePolicyDraft>): void {
    this.profilePolicies.update((policies) => ({
      ...policies,
      [profileId]: {
        ...this.policyDraft(profileId),
        ...patch
      }
    }));
  }

  private policyDraft(profileId: number): ProfilePolicyDraft {
    return this.profilePolicies()[profileId] ?? {
      paymentPolicy: 'T_BANK_ONLY',
      manualPaymentType: 'MOBILE_BANK',
      manualPhone: '',
      manualRecipientName: DEFAULT_MANUAL_RECIPIENT_NAME,
      manualPaymentUrl: DEFAULT_MANUAL_PAYMENT_URL,
      manualPaymentButtonLabel: DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL,
      manualMonthlyLimitRubles: String(DEFAULT_MANUAL_MONTHLY_LIMIT_RUBLES)
    };
  }

  private profileToPolicyDraft(profile: PaymentProfileResponse): ProfilePolicyDraft {
    return {
      paymentPolicy: profile.paymentPolicy ?? 'T_BANK_ONLY',
      manualPaymentType: (profile.manualPaymentType as ManualPaymentType | undefined) ?? 'MOBILE_BANK',
      manualPhone: profile.manualPhone ?? '',
      manualRecipientName: profile.manualRecipientName ?? DEFAULT_MANUAL_RECIPIENT_NAME,
      manualPaymentUrl: profile.manualPaymentUrl ?? DEFAULT_MANUAL_PAYMENT_URL,
      manualPaymentButtonLabel: profile.manualPaymentButtonLabel ?? DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL,
      manualMonthlyLimitRubles: String(this.kopecksToRubles(profile.manualMonthlyHardLimitKopecks)
        ?? this.kopecksToRubles(profile.manualMonthlySoftLimitKopecks)
        ?? DEFAULT_MANUAL_MONTHLY_LIMIT_RUBLES)
    };
  }

  private adminTaskTargetKopecks(): number {
    const value = Number(this.adminTaskAmountRubles());
    return Number.isFinite(value) && value > 0 ? Math.round(value * 100) : 0;
  }

  private manualLimitKopecksFromDraft(value: string): number {
    const numeric = Number(value);
    return this.rublesToKopecks(Number.isFinite(numeric) ? numeric : null)
      ?? this.rublesToKopecks(DEFAULT_MANUAL_MONTHLY_LIMIT_RUBLES)
      ?? 0;
  }

  private rublesToKopecks(value: number | null | undefined): number | null {
    return value && value > 0 ? Math.round(value * 100) : null;
  }

  private kopecksToRubles(value?: number | null): number | null {
    return value && value > 0 ? value / 100 : null;
  }

  private matchesDateRange(link: AdminPaymentLinkResponse, from: string, to: string): boolean {
    const createdAt = this.timeValue(link.createdAt);
    if (from) {
      const fromTime = new Date(`${from}T00:00:00`).getTime();
      if (!Number.isNaN(fromTime) && createdAt < fromTime) {
        return false;
      }
    }
    if (to) {
      const toTime = new Date(`${to}T23:59:59.999`).getTime();
      if (!Number.isNaN(toTime) && createdAt > toTime) {
        return false;
      }
    }
    return true;
  }

  private isPaid(status: string): boolean {
    return status === 'CONFIRMED' || status === 'TEST_CONFIRMED' || status === 'AUTHORIZED';
  }

  private isRefunded(status: string): boolean {
    return status === 'REFUNDED'
      || status === 'PARTIAL_REFUNDED'
      || status === 'REVERSED'
      || status === 'PARTIAL_REVERSED';
  }

  private timeValue(value: string | null | undefined): number {
    const date = value ? new Date(value).getTime() : 0;
    return Number.isNaN(date) ? 0 : date;
  }

  private dateInputLabel(value: string): string {
    const [year, month, day] = value.split('-');
    return day && month && year ? `${day}.${month}.${year}` : value;
  }

  private keepPageInRange(): void {
    this.pageIndex.update((page) => Math.min(page, this.totalPages() - 1));
  }

  private errorMessage(error: unknown, fallback: string): string {
    if (typeof error === 'object' && error && 'error' in error) {
      const payload = (error as { error?: { detail?: string; message?: string } | string }).error;
      if (typeof payload === 'string' && payload.trim()) {
        return payload;
      }
      if (typeof payload === 'object' && payload) {
        if (payload.detail) {
          return payload.detail;
        }
        if (payload.message) {
          return payload.message;
        }
      }
    }
    return fallback;
  }
}
