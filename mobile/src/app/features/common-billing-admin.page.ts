import { Component, OnInit, computed, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { IonContent, IonRefresher, IonRefresherContent, RefresherCustomEvent } from '@ionic/angular/standalone';
import { firstValueFrom } from 'rxjs';
import {
  ApiService,
  CommonBillingAccountRequest,
  CommonBillingAccountResponse,
  CommonBillingCompanyResponse,
  CommonInvoiceDetailsResponse,
  CompanyItem,
  OrderItem
} from '../core/api.service';
import { COMMON_INVOICE_ACTIONS, OrderStatusAction } from './manager/manager-board.helpers';
import { displayPhone, normalizePhoneDigits, phoneHref } from '../shared/phone-format';
import { MobileConfirmService } from '../shared/mobile-confirm.service';
import { MobileHeaderComponent } from '../shared/mobile-header.component';
import { MobileOrderCardComponent } from '../shared/mobile-order-card.component';

type CommonBillingDraft = {
  name: string;
  enabled: boolean;
  autoRepeatOrders: boolean;
  managerId: number | null;
  invoiceCompanyId: number | null;
};

@Component({
  selector: 'app-common-billing-admin-mobile',
  standalone: true,
  imports: [FormsModule, IonContent, IonRefresher, IonRefresherContent, MobileHeaderComponent, MobileOrderCardComponent],
  template: `
    <div class="ion-page">
      <app-mobile-header title="Общие счета" />

      <ion-content fullscreen>
        <ion-refresher slot="fixed" (ionRefresh)="refresh($event)">
          <ion-refresher-content />
        </ion-refresher>

        <main class="common-billing-admin">
          @if (error()) {
            <button class="state-card state-card--error" type="button" (click)="reload()">
              <span class="material-icons-sharp">error</span>
              {{ error() }}
            </button>
          }

          <section class="metrics-strip" aria-label="Сводка общих счетов">
            <article>
              <span>Плательщики</span>
              <strong>{{ accounts().length }}</strong>
            </article>
            <article>
              <span>Включены</span>
              <strong>{{ enabledAccountsCount() }}</strong>
            </article>
            <article>
              <span>Компании</span>
              <strong>{{ linkedCompaniesCount() }}</strong>
            </article>
            <article>
              <span>К оплате</span>
              <strong>{{ kopecks(currentInvoice()?.remainingKopecks) }}</strong>
            </article>
          </section>

          <section class="section-card accounts-panel">
            <header>
              <div>
                <small>Связи</small>
                <h1>Общие счета</h1>
              </div>
              <button type="button" class="icon-action" (click)="startNewAccount()" aria-label="Новый общий счет">
                <span class="material-icons-sharp">add</span>
              </button>
            </header>

            <div class="account-list" aria-label="Список общих счетов">
              @for (account of accounts(); track account.id) {
                <button
                  type="button"
                  class="account-pill"
                  [class.active]="selectedAccountId() === account.id"
                  (click)="selectAccount(account.id)"
                >
                  <span>{{ account.name || 'Общий счет' }}</span>
                  <small>{{ accountCompaniesLabel(account) }} · {{ account.enabled ? 'включен' : 'выключен' }}</small>
                </button>
              } @empty {
                <p class="empty-inline">Связей пока нет. Создайте первый общий счет ниже.</p>
              }
            </div>
          </section>

          <section class="section-card settings-panel">
            <header>
              <div>
                <small>{{ selectedAccount() ? 'Настройки связи' : 'Новая связь' }}</small>
                <h2>{{ selectedAccount()?.name || 'Новый общий счет' }}</h2>
              </div>
              @if (loading()) {
                <span class="loading-chip">загрузка</span>
              }
            </header>

            <label class="form-field">
              <span>Название</span>
              <input
                type="text"
                [ngModel]="draft().name"
                (ngModelChange)="setDraft('name', $event)"
                placeholder="Название общего счета"
              />
            </label>

            <div class="toggle-grid">
              <label>
                <input type="checkbox" [ngModel]="draft().enabled" (ngModelChange)="setDraft('enabled', $event)" />
                <span>Общий счет включен</span>
              </label>
              <label>
                <input type="checkbox" [ngModel]="draft().autoRepeatOrders" (ngModelChange)="setDraft('autoRepeatOrders', $event)" />
                <span>Новые заказы после оплаты</span>
              </label>
            </div>

            <div class="form-grid">
              <label class="form-field">
                <span>ID менеджера</span>
                <input
                  type="number"
                  inputmode="numeric"
                  [ngModel]="draft().managerId"
                  (ngModelChange)="setNumericDraft('managerId', $event)"
                  placeholder="не задан"
                />
              </label>
              <label class="form-field">
                <span>ID компании счета</span>
                <input
                  type="number"
                  inputmode="numeric"
                  [ngModel]="draft().invoiceCompanyId"
                  (ngModelChange)="setNumericDraft('invoiceCompanyId', $event)"
                  placeholder="не задан"
                />
              </label>
            </div>

            <div class="panel-actions">
              <button type="button" (click)="saveAccount()" [disabled]="mutating() || !canSaveAccount()">
                {{ selectedAccount() ? 'Сохранить' : 'Создать' }}
              </button>
              @if (selectedAccount()) {
                <button type="button" (click)="startNewAccount()" [disabled]="mutating()">Новая связь</button>
              }
            </div>
          </section>

          <section class="section-card companies-panel">
            <header>
              <div>
                <small>Компании в связи</small>
                <h2>{{ linkedCompanies().length || draftCompanies().length }} компаний</h2>
              </div>
              <button type="button" class="icon-action" (click)="searchCompanies()" [disabled]="companySearchLoading()" aria-label="Найти компанию">
                <span class="material-icons-sharp">search</span>
              </button>
            </header>

            <label class="form-field search-field">
              <span>Добавить компанию</span>
              <input
                type="search"
                [ngModel]="companyKeyword()"
                (ngModelChange)="onCompanyKeywordChange($event)"
                placeholder="Название, город, телефон"
              />
            </label>

            @if (companyResults().length) {
              <div class="search-results">
                @for (company of companyResults(); track company.id) {
                  <button type="button" (click)="addCompany(company)" [disabled]="mutating() || isCompanyLinked(company.id)">
                    <strong>{{ company.title }}</strong>
                    <small>{{ company.city || companyPhone(company) }}</small>
                  </button>
                }
              </div>
            }

            <div class="linked-list">
              @if (!linkedCompanies().length && !draftCompanies().length) {
                <p class="empty-inline">Компании еще не подключены.</p>
              } @else {
                @for (company of linkedCompanies(); track company.companyId) {
                  <article>
                    <span>{{ company.companyTitle }}</span>
                    <small>{{ company.enabled ? 'участвует' : 'отключена' }}</small>
                    <button type="button" (click)="removeCompany(company)" [disabled]="mutating()">-</button>
                  </article>
                }
                @for (company of draftCompanies(); track company.id) {
                  <article>
                    <span>{{ company.title }}</span>
                    <small>будет добавлена</small>
                    <button type="button" (click)="removeDraftCompany(company.id)" [disabled]="mutating()">-</button>
                  </article>
                }
              }
            </div>
          </section>

          @if (currentInvoice(); as invoice) {
            <section class="section-card invoice-panel">
              <header>
                <div>
                  <small>{{ invoice.status || 'Общий счет' }}</small>
                  <h2>{{ invoice.title || invoice.accountName }}</h2>
                </div>
                <button type="button" class="icon-action" (click)="loadSelectedInvoice()" [disabled]="invoiceLoading()" aria-label="Обновить счет">
                  <span class="material-icons-sharp">refresh</span>
                </button>
              </header>

              <div class="invoice-stats">
                <article><span>Сумма</span><strong>{{ kopecks(invoice.amountKopecks) }}</strong></article>
                <article><span>Оплачено</span><strong>{{ kopecks(invoice.paidKopecks) }}</strong></article>
                <article><span>Остаток</span><strong>{{ kopecks(invoice.remainingKopecks) }}</strong></article>
                <article><span>Готово</span><strong>{{ invoice.readyOrders }}/{{ invoice.totalOrders }}</strong></article>
              </div>

              <div class="panel-actions panel-actions--invoice">
                <button type="button" (click)="openInvoice(invoice.id)">Открыть счет</button>
                <button type="button" (click)="copyPublicUrl()" [disabled]="!invoice.publicUrl">Ссылка</button>
              </div>
            </section>

            <section class="section-card order-cards-panel">
              <header>
                <div>
                  <small>Состав общего счета</small>
                  <h2>{{ orderCards().length }} заказов</h2>
                </div>
              </header>

              <div class="order-card-list">
                @for (order of orderCards(); track order.id) {
                  <app-mobile-order-card
                    [order]="order"
                    [statusActions]="orderActionsFor(order)"
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
                    [phoneCopyKey]="'common-order-phone-' + order.id"
                    [reviewCopyKey]="'common-order-review-' + order.id"
                    [paymentCopyKey]="'common-order-payment-' + order.id"
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
                    (details)="openOrderDetails(order)"
                    (workerClick)="openOrderDetails(order)"
                  />
                } @empty {
                  <p class="empty-inline">В текущем общем счете пока нет заказов.</p>
                }
              </div>
            </section>
          } @else if (!loading()) {
            <p class="state-card">Выберите или создайте связь, чтобы увидеть текущий общий счет.</p>
          }
        </main>
      </ion-content>
    </div>
  `,
  styles: [`
    .common-billing-admin {
      display: grid;
      gap: 0.68rem;
      min-height: 100%;
      padding: 0.66rem var(--otziv-page-padding-x, 0.68rem) calc(var(--otziv-tabbar-height, 3rem) + 1rem);
      background: var(--otziv-page-background);
    }

    .state-card,
    .section-card,
    .metrics-strip article {
      border: 1px solid rgba(103, 116, 131, 0.14);
      border-radius: 0.9rem;
      background: rgba(255, 255, 255, 0.95);
      box-shadow: 0 0.5rem 1.15rem rgba(132, 139, 200, 0.12);
    }

    .state-card {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 0.4rem;
      min-height: 3rem;
      margin: 0;
      padding: 0.7rem;
      color: var(--otziv-info);
      font: 900 0.78rem/1.2 var(--otziv-card-title-font);
    }

    .state-card--error {
      color: var(--otziv-danger);
    }

    .metrics-strip {
      display: grid;
      grid-template-columns: repeat(4, minmax(7.4rem, 1fr));
      gap: 0.5rem;
      overflow-x: auto;
      padding-bottom: 0.08rem;
      scrollbar-width: none;
    }

    .metrics-strip::-webkit-scrollbar,
    .account-list::-webkit-scrollbar,
    .order-card-list::-webkit-scrollbar {
      display: none;
    }

    .metrics-strip article {
      display: grid;
      gap: 0.2rem;
      min-height: 3.9rem;
      padding: 0.58rem;
      background: linear-gradient(145deg, var(--otziv-white) 0%, var(--otziv-tone-work-surface) 100%);
    }

    .metrics-strip span,
    .section-card small,
    .form-field span,
    .invoice-stats span {
      color: var(--otziv-info);
      font: 900 0.62rem/1.15 var(--otziv-card-title-font);
    }

    .metrics-strip strong,
    .section-card h1,
    .section-card h2,
    .invoice-stats strong {
      color: var(--otziv-dark);
      font-family: var(--otziv-card-title-font);
      font-weight: 1000;
      line-height: 1.05;
    }

    .metrics-strip strong {
      font-size: 0.96rem;
    }

    .section-card {
      display: grid;
      gap: 0.62rem;
      padding: 0.72rem;
    }

    .section-card header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.65rem;
    }

    .section-card h1,
    .section-card h2 {
      margin: 0.12rem 0 0;
      font-size: 1.02rem;
    }

    .icon-action {
      display: grid;
      min-width: 2.35rem;
      min-height: 2.35rem;
      place-items: center;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 0.8rem;
      color: var(--otziv-primary);
      background: var(--otziv-light);
    }

    .account-list {
      display: flex;
      gap: 0.48rem;
      overflow-x: auto;
      padding-bottom: 0.08rem;
      scroll-snap-type: x proximity;
    }

    .account-pill {
      display: grid;
      flex: 0 0 min(16.8rem, 78vw);
      gap: 0.24rem;
      min-height: 3.15rem;
      border: 1px solid rgba(103, 116, 131, 0.18);
      border-radius: 0.78rem;
      padding: 0.55rem 0.68rem;
      color: var(--otziv-dark);
      background: var(--otziv-white);
      text-align: left;
      scroll-snap-align: start;
      font: 900 0.72rem/1.14 var(--otziv-card-title-font);
    }

    .account-pill.active {
      border-color: rgba(112, 154, 211, 0.58);
      background: var(--otziv-light);
    }

    .form-field {
      display: grid;
      gap: 0.28rem;
    }

    .form-field input {
      width: 100%;
      min-height: 2.4rem;
      border: 1px solid rgba(103, 116, 131, 0.16);
      border-radius: 0.78rem;
      padding: 0 0.74rem;
      color: var(--otziv-dark);
      background: var(--otziv-white);
      font: 900 0.76rem/1 var(--otziv-card-title-font);
      outline: none;
    }

    .toggle-grid,
    .form-grid,
    .invoice-stats {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.5rem;
    }

    .toggle-grid label {
      display: flex;
      align-items: center;
      gap: 0.45rem;
      min-height: 3.05rem;
      border: 1px solid rgba(103, 116, 131, 0.14);
      border-radius: 0.78rem;
      padding: 0.55rem;
      color: var(--otziv-dark);
      background: var(--otziv-field-background);
      font: 900 0.68rem/1.12 var(--otziv-card-title-font);
    }

    .panel-actions {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.48rem;
    }

    .panel-actions button,
    .search-results button,
    .linked-list article button {
      min-height: 2.18rem;
      border: 1px solid rgba(103, 116, 131, 0.18);
      border-radius: 999px;
      color: var(--otziv-dark);
      background: var(--otziv-white);
      font: 900 0.7rem/1 var(--otziv-card-title-font);
    }

    .panel-actions button:first-child {
      color: var(--otziv-primary);
      background: var(--otziv-light);
    }

    button:disabled {
      opacity: 0.5;
    }

    .search-results,
    .linked-list {
      display: grid;
      gap: 0.42rem;
    }

    .search-results button,
    .linked-list article {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      align-items: center;
      gap: 0.34rem 0.6rem;
      border: 1px solid rgba(103, 116, 131, 0.14);
      border-radius: 0.78rem;
      padding: 0.55rem;
      background: var(--otziv-white);
      text-align: left;
    }

    .search-results strong,
    .linked-list span {
      color: var(--otziv-dark);
      font: 1000 0.78rem/1.12 var(--otziv-card-title-font);
    }

    .search-results small,
    .linked-list small {
      grid-column: 1;
    }

    .linked-list article button {
      grid-row: 1 / span 2;
      grid-column: 2;
      min-width: 2.1rem;
      min-height: 2.1rem;
      padding: 0;
      color: var(--otziv-primary);
      background: var(--otziv-light);
    }

    .empty-inline {
      margin: 0;
      color: var(--otziv-info);
      font: 900 0.7rem/1.24 var(--otziv-card-title-font);
    }

    .loading-chip {
      border-radius: 999px;
      padding: 0.28rem 0.55rem;
      color: var(--otziv-primary);
      background: var(--otziv-light);
      font: 900 0.62rem/1 var(--otziv-card-title-font);
    }

    .invoice-stats article {
      display: grid;
      gap: 0.22rem;
      min-height: 3.8rem;
      border: 1px solid rgba(103, 116, 131, 0.12);
      border-radius: 0.76rem;
      padding: 0.58rem;
      background: var(--otziv-field-background);
    }

    .panel-actions--invoice {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }

    .order-card-list {
      display: flex;
      gap: 0.58rem;
      min-height: 34rem;
      overflow-x: auto;
      overscroll-behavior-x: contain;
      scroll-snap-type: x mandatory;
      -webkit-overflow-scrolling: touch;
    }

    .order-card-list app-mobile-order-card {
      flex: 0 0 var(--otziv-board-card-width, min(18.25rem, 84vw));
    }
  `]
})
export class CommonBillingAdminPage implements OnInit {
  readonly accounts = signal<CommonBillingAccountResponse[]>([]);
  readonly selectedAccountId = signal<number | null>(null);
  readonly details = signal<CommonInvoiceDetailsResponse | null>(null);
  readonly loading = signal(false);
  readonly invoiceLoading = signal(false);
  readonly error = signal<string | null>(null);
  readonly mutating = signal<string | null>(null);
  readonly copiedKey = signal<string | null>(null);
  readonly companyKeyword = signal('');
  readonly companyResults = signal<CompanyItem[]>([]);
  readonly companySearchLoading = signal(false);
  readonly draftCompanies = signal<CompanyItem[]>([]);
  readonly draft = signal<CommonBillingDraft>({
    name: 'Новый общий счет',
    enabled: true,
    autoRepeatOrders: true,
    managerId: null,
    invoiceCompanyId: null
  });

  readonly selectedAccount = computed(() =>
    this.accounts().find((account) => account.id === this.selectedAccountId()) ?? null
  );
  readonly linkedCompanies = computed(() => this.selectedAccount()?.companies ?? []);
  readonly currentInvoice = computed(() => this.details()?.summary ?? this.selectedAccount()?.currentInvoice ?? null);
  readonly orderCards = computed(() => this.details()?.orderCards ?? []);
  readonly enabledAccountsCount = computed(() => this.accounts().filter((account) => account.enabled).length);
  readonly linkedCompaniesCount = computed(() =>
    this.accounts().reduce((sum, account) => sum + (account.companies?.length ?? 0), 0)
  );

  private companySearchTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(
    private readonly api: ApiService,
    private readonly router: Router,
    private readonly confirm: MobileConfirmService
  ) {}

  ngOnInit(): void {
    void this.load();
  }

  async refresh(event: RefresherCustomEvent): Promise<void> {
    await this.load();
    event.target.complete();
  }

  reload(): void {
    void this.load();
  }

  async load(): Promise<void> {
    this.loading.set(true);
    try {
      const accounts = await firstValueFrom(this.api.getCommonBillingAccounts());
      this.accounts.set(accounts);
      const selectedId = this.selectedAccountId();
      const selected = accounts.find((account) => account.id === selectedId) ?? accounts[0] ?? null;
      this.selectedAccountId.set(selected?.id ?? null);
      this.applyDraftFromAccount(selected);
      await this.loadSelectedInvoice();
      this.error.set(null);
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Не удалось загрузить общие счета.'));
    } finally {
      this.loading.set(false);
    }
  }

  async selectAccount(accountId: number): Promise<void> {
    if (this.selectedAccountId() === accountId) {
      return;
    }
    this.selectedAccountId.set(accountId);
    this.draftCompanies.set([]);
    this.applyDraftFromAccount(this.selectedAccount());
    await this.loadSelectedInvoice();
  }

  startNewAccount(): void {
    this.selectedAccountId.set(null);
    this.details.set(null);
    this.companyResults.set([]);
    this.draftCompanies.set([]);
    this.draft.set({
      name: 'Новый общий счет',
      enabled: true,
      autoRepeatOrders: true,
      managerId: null,
      invoiceCompanyId: null
    });
  }

  setDraft<K extends keyof CommonBillingDraft>(field: K, value: CommonBillingDraft[K]): void {
    this.draft.update((draft) => ({ ...draft, [field]: value }));
  }

  setNumericDraft(field: 'managerId' | 'invoiceCompanyId', value: string | number | null): void {
    const normalized = Number(value);
    this.setDraft(field, Number.isFinite(normalized) && normalized > 0 ? normalized : null);
  }

  canSaveAccount(): boolean {
    return this.draft().name.trim().length > 0 && !this.loading();
  }

  async saveAccount(): Promise<void> {
    if (!this.canSaveAccount() || this.mutating()) {
      return;
    }

    const selected = this.selectedAccount();
    const request = this.accountRequest();
    this.mutating.set('account');
    try {
      const account = selected
        ? await firstValueFrom(this.api.updateCommonBillingAccount(selected.id, request))
        : await firstValueFrom(this.api.createCommonBillingAccount(request));
      this.upsertAccount(account);
      this.selectedAccountId.set(account.id);
      this.draftCompanies.set([]);
      this.applyDraftFromAccount(account);
      await this.loadSelectedInvoice();
      this.error.set(null);
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Не удалось сохранить общий счет.'));
    } finally {
      this.mutating.set(null);
    }
  }

  onCompanyKeywordChange(value: string): void {
    this.companyKeyword.set(value);
    if (this.companySearchTimer) {
      clearTimeout(this.companySearchTimer);
    }
    this.companySearchTimer = setTimeout(() => void this.searchCompanies(), 320);
  }

  async searchCompanies(): Promise<void> {
    const keyword = this.companyKeyword().trim();
    if (keyword.length < 2) {
      this.companyResults.set([]);
      return;
    }

    this.companySearchLoading.set(true);
    try {
      const board = await firstValueFrom(this.api.getManagerBoard({
        section: 'companies',
        status: 'Все',
        keyword,
        pageNumber: 0,
        pageSize: 8,
        sortDirection: 'desc'
      }));
      this.companyResults.set(board.companies?.content ?? []);
      this.error.set(null);
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Не удалось найти компании.'));
    } finally {
      this.companySearchLoading.set(false);
    }
  }

  async addCompany(company: CompanyItem): Promise<void> {
    if (this.isCompanyLinked(company.id) || this.mutating()) {
      return;
    }

    const account = this.selectedAccount();
    if (!account) {
      this.draftCompanies.update((companies) => [company, ...companies.filter((item) => item.id !== company.id)]);
      return;
    }

    this.mutating.set(`add-company-${company.id}`);
    try {
      const updated = await firstValueFrom(this.api.addCommonBillingCompany(account.id, company.id));
      this.upsertAccount(updated);
      this.applyDraftFromAccount(updated);
      await this.loadSelectedInvoice();
      this.error.set(null);
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Не удалось добавить компанию в связь.'));
    } finally {
      this.mutating.set(null);
    }
  }

  removeDraftCompany(companyId: number): void {
    this.draftCompanies.update((companies) => companies.filter((company) => company.id !== companyId));
  }

  async removeCompany(company: CommonBillingCompanyResponse): Promise<void> {
    const account = this.selectedAccount();
    if (!account || this.mutating()) {
      return;
    }

    const detachCurrent = await this.confirm.confirm({
      message: `Отключить ${company.companyTitle} от связи и убрать ее заказы из текущего счета?`,
      danger: true
    });
    const confirmed = detachCurrent || await this.confirm.confirm({
      message: `Отключить ${company.companyTitle} только для будущих общих счетов?`,
      danger: true
    });
    if (!confirmed) {
      return;
    }

    this.mutating.set(`remove-company-${company.companyId}`);
    try {
      const updated = await firstValueFrom(this.api.removeCommonBillingCompany(account.id, company.companyId, detachCurrent));
      this.upsertAccount(updated);
      this.applyDraftFromAccount(updated);
      await this.loadSelectedInvoice();
      this.error.set(null);
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Не удалось отключить компанию от связи.'));
    } finally {
      this.mutating.set(null);
    }
  }

  async loadSelectedInvoice(): Promise<void> {
    const invoiceId = this.selectedAccount()?.currentInvoice?.id ?? null;
    if (!invoiceId) {
      this.details.set(null);
      return;
    }

    this.invoiceLoading.set(true);
    try {
      const details = await firstValueFrom(this.api.getCommonInvoice(invoiceId));
      this.details.set(details);
      this.error.set(null);
    } catch (error) {
      this.details.set(null);
      this.error.set(this.errorMessage(error, 'Не удалось загрузить текущий общий счет.'));
    } finally {
      this.invoiceLoading.set(false);
    }
  }

  openInvoice(invoiceId: number): void {
    void this.router.navigate(['/tabs/common-billing', invoiceId]);
  }

  openOrderDetails(order: OrderItem): void {
    const companyId = order.companyId;
    if (!companyId || !order.id) {
      return;
    }
    void this.router.navigate(['/tabs/orders', companyId, order.id]);
  }

  async copyPublicUrl(): Promise<void> {
    const invoice = this.currentInvoice();
    if (!invoice?.publicUrl) {
      return;
    }
    await this.copyText(invoice.publicUrl, `common-invoice-${invoice.id}`);
  }

  async copyOrderPhone(order: OrderItem): Promise<void> {
    await this.copyText(normalizePhoneDigits(order.companyTelephone), `common-order-phone-${order.id}`);
  }

  async copyOrderText(order: OrderItem, kind: 'review' | 'payment'): Promise<void> {
    if (kind === 'review') {
      await this.copyText(this.orderReviewUrl(order), `common-order-review-${order.id}`);
      return;
    }
    await this.copyText(this.currentInvoice()?.publicUrl ?? '', `common-order-payment-${order.id}`);
  }

  isCompanyLinked(companyId: number): boolean {
    return this.linkedCompanies().some((company) => company.companyId === companyId)
      || this.draftCompanies().some((company) => company.id === companyId);
  }

  accountCompaniesLabel(account: CommonBillingAccountResponse): string {
    const count = account.companies?.length ?? 0;
    return `${count} ${this.plural(count, 'компания', 'компании', 'компаний')}`;
  }

  companyPhone(company: CompanyItem): string {
    return displayPhone(company.telephone, '-');
  }

  kopecks(value?: number | null): string {
    const rubles = Math.round((value ?? 0) / 100);
    return `${new Intl.NumberFormat('ru-RU').format(rubles)} ₽`;
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

  orderActionsFor(order: OrderItem): readonly OrderStatusAction[] {
    if (order.commonInvoice) {
      return COMMON_INVOICE_ACTIONS;
    }
    return [];
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

  private accountRequest(): CommonBillingAccountRequest {
    const draft = this.draft();
    const selected = this.selectedAccount();
    const draftCompanyIds = this.draftCompanies().map((company) => company.id);
    const selectedCompanyIds = selected?.companies?.map((company) => company.companyId) ?? [];
    return {
      name: draft.name.trim(),
      enabled: draft.enabled,
      autoRepeatOrders: draft.autoRepeatOrders,
      managerId: draft.managerId,
      invoiceCompanyId: draft.invoiceCompanyId,
      companyIds: [...new Set([...selectedCompanyIds, ...draftCompanyIds])]
    };
  }

  private applyDraftFromAccount(account: CommonBillingAccountResponse | null): void {
    this.draft.set({
      name: account?.name || 'Новый общий счет',
      enabled: account?.enabled ?? true,
      autoRepeatOrders: account?.autoRepeatOrders ?? true,
      managerId: account?.managerId ?? null,
      invoiceCompanyId: account?.invoiceCompanyId ?? null
    });
  }

  private upsertAccount(account: CommonBillingAccountResponse): void {
    this.accounts.update((accounts) => [
      account,
      ...accounts.filter((item) => item.id !== account.id)
    ]);
  }

  private async copyText(value: string, key: string): Promise<void> {
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
      this.error.set('Не удалось скопировать текст.');
    }
  }

  private plural(value: number, one: string, few: string, many: string): string {
    const mod10 = value % 10;
    const mod100 = value % 100;
    if (mod10 === 1 && mod100 !== 11) {
      return one;
    }
    if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
      return few;
    }
    return many;
  }

  private errorMessage(error: unknown, fallback: string): string {
    if (error instanceof HttpErrorResponse) {
      return error.error?.message || error.message || fallback;
    }
    return fallback;
  }
}
