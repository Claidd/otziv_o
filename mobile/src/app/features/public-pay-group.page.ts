import { Component, HostListener, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { IonContent } from '@ionic/angular/standalone';
import { ApiService, PublicCommonInvoice } from '../core/api.service';

@Component({
  selector: 'app-public-pay-group-page',
  imports: [FormsModule, IonContent, RouterLink],
  template: `
    <div class="ion-page">
      <ion-content fullscreen>
        <main class="pay-page">
          <header class="pay-hero">
            <a routerLink="/" class="brand">Компания <strong>О!</strong></a>
            <p>{{ statusLabel() }}</p>
            <h1>{{ title() }}</h1>
          </header>

          @if (loading()) {
            <section class="state-card"><span class="material-icons-sharp">hourglass_top</span><strong>Загружаем счет</strong></section>
          }
          @if (error()) {
            <button class="state-card error" type="button" (click)="loadInvoice()"><span class="material-icons-sharp">error</span><strong>{{ error() }}</strong></button>
          }
          @if (message()) {
            <section class="state-card ok"><span class="material-icons-sharp">info</span><strong>{{ message() }}</strong></section>
          }

          @if (invoice(); as invoice) {
            <section class="summary-card">
              <div><small>Счет</small><strong>{{ invoice.title || 'Общий счет' }}</strong></div>
              <div><small>Аккаунт</small><strong>{{ invoice.accountName || '-' }}</strong></div>
              <div><small>Осталось</small><b>{{ formatRubles(invoice.remaining) }} ₽</b></div>
              <div><small>Готово</small><strong>{{ readyOrders() }} из {{ invoice.orders.length }}</strong></div>
            </section>

            <section class="order-list">
              @for (order of invoice.orders; track order.orderId) {
                <article [class.paid]="order.paid">
                  <div>
                    <strong>#{{ order.orderId }} {{ order.companyTitle }}</strong>
                    <small>{{ order.filialTitle || order.orderStatus }}</small>
                  </div>
                  <b>{{ formatRubles(order.amount) }} ₽</b>
                </article>
              }
            </section>

            @if (invoice.payable) {
              <form class="pay-form" (ngSubmit)="submitPayment()">
                <label>
                  <span>E-mail для чека</span>
                  <input name="email" type="email" autocomplete="email" [ngModel]="email()" (ngModelChange)="email.set($event)">
                </label>
                <label class="check-row"><input type="checkbox" name="offer" [ngModel]="offerConsent()" (ngModelChange)="offerConsent.set($event)"><span>Согласен с <a routerLink="/offer">офертой</a>.</span></label>
                <label class="check-row"><input type="checkbox" name="privacy" [ngModel]="privacyConsent()" (ngModelChange)="privacyConsent.set($event)"><span>Согласен с <a routerLink="/privacy">политикой персональных данных</a>.</span></label>
                <label class="check-row"><input type="checkbox" name="receipt" [ngModel]="receiptConsent()" (ngModelChange)="receiptConsent.set($event)"><span>Согласен получить <a routerLink="/receipt-consent">электронный чек</a>.</span></label>
                <button class="primary" type="submit" [disabled]="!canSubmit()">
                  <span class="material-icons-sharp">{{ submitting() ? 'hourglass_top' : 'payments' }}</span>
                  {{ submitting() ? 'Открываем банк...' : 'Оплатить счет' }}
                </button>
              </form>
            } @else {
              <section class="state-card"><span class="material-icons-sharp">lock</span><strong>Этот счет недоступен для оплаты.</strong></section>
            }
          }
        </main>
      </ion-content>
    </div>
  `,
  styles: [`
    ion-content{--background:#f6f8fc}.pay-page{display:grid;gap:.75rem;max-width:42rem;margin:0 auto;padding:calc(1rem + env(safe-area-inset-top)) .85rem calc(1.2rem + env(safe-area-inset-bottom));font-family:var(--otziv-font-family)}
    .pay-hero,.summary-card,.pay-form,.state-card,.order-list article{border:1px solid rgba(103,116,131,.16);border-radius:1rem;background:linear-gradient(155deg,var(--otziv-white),var(--otziv-tone-walk-surface));box-shadow:0 .9rem 1.8rem rgba(132,139,200,.12)}
    .pay-hero{display:grid;gap:.45rem;padding:1rem}.brand{color:var(--otziv-dark);font:900 1.1rem/1 var(--otziv-card-title-font);text-decoration:none}.brand strong{color:var(--otziv-danger)}.pay-hero p{margin:0;color:var(--otziv-info);font-size:.7rem;font-weight:1000;text-transform:uppercase}.pay-hero h1{margin:0;color:var(--otziv-dark);font-size:1.75rem}
    .summary-card{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.55rem;padding:.8rem}.summary-card div{display:grid;gap:.12rem}.summary-card small,.pay-form label>span{color:var(--otziv-info);font-size:.66rem;font-weight:1000;text-transform:uppercase}.summary-card strong,.summary-card b{overflow:hidden;color:var(--otziv-dark);font-size:.9rem;text-overflow:ellipsis}.summary-card b{color:#16735f;font-size:1.2rem}
    .order-list{display:grid;gap:.5rem}.order-list article{display:grid;grid-template-columns:minmax(0,1fr)auto;gap:.55rem;align-items:center;padding:.75rem}.order-list article.paid{opacity:.68}.order-list strong,.order-list small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.order-list small{color:var(--otziv-info);font-weight:800}.order-list b{color:#16735f}
    .pay-form{display:grid;gap:.62rem;padding:.85rem}.pay-form label{display:grid;gap:.3rem}.pay-form input{min-height:2.5rem;border:1px solid rgba(103,116,131,.18);border-radius:.75rem;padding:0 .75rem;color:var(--otziv-dark);background:var(--otziv-white);font:900 .9rem/1 var(--otziv-font-family)}.check-row{grid-template-columns:auto minmax(0,1fr);align-items:center}.check-row input{min-height:1rem}.check-row span{color:var(--otziv-dark);font-size:.75rem;text-transform:none}.check-row a{color:var(--otziv-primary)}
    button{display:inline-flex;align-items:center;justify-content:center;gap:.35rem;min-height:2.55rem;border:1px solid rgba(108,155,207,.25);border-radius:.82rem;color:var(--otziv-primary);background:var(--otziv-white);font:1000 .82rem/1 var(--otziv-font-family)}button.primary{color:#fff;background:var(--otziv-primary)}button:disabled{opacity:.55}.state-card{display:grid;place-items:center;gap:.35rem;min-height:5.5rem;padding:1rem;text-align:center}.state-card.error{color:var(--otziv-danger)}.state-card.ok{color:#16735f}
  `]
})
export class PublicPayGroupPage {
  private readonly route = inject(ActivatedRoute);

  readonly token = signal(this.route.snapshot.paramMap.get('token') ?? '');
  readonly invoice = signal<PublicCommonInvoice | null>(null);
  readonly loading = signal(true);
  readonly refreshing = signal(false);
  readonly submitting = signal(false);
  readonly error = signal('');
  readonly message = signal('');
  readonly email = signal('');
  readonly offerConsent = signal(false);
  readonly privacyConsent = signal(false);
  readonly receiptConsent = signal(false);
  private lastReturnRefreshAt = 0;

  readonly title = computed(() => this.invoice()?.title || 'Общий счет');
  readonly statusLabel = computed(() => this.statusText(this.invoice()?.status));
  readonly readyOrders = computed(() => this.invoice()?.orders.filter((order) => order.ready).length ?? 0);
  readonly canSubmit = computed(() => Boolean(this.invoice()?.payable && this.email().includes('@') && this.offerConsent() && this.privacyConsent() && this.receiptConsent() && !this.submitting()));

  constructor(private readonly api: ApiService) {
    this.loadInvoice();
  }

  @HostListener('window:pageshow') onPageShow(): void { this.refreshAfterReturn(); }
  @HostListener('window:focus') onWindowFocus(): void { this.refreshAfterReturn(); }
  @HostListener('document:visibilitychange') onVisibilityChange(): void {
    if (document.visibilityState === 'visible') {
      this.refreshAfterReturn();
    }
  }

  submitPayment(): void {
    if (!this.canSubmit()) {
      this.message.set('Укажите e-mail и подтвердите согласия.');
      return;
    }
    this.submitting.set(true);
    this.error.set('');
    this.message.set('');
    this.api.initPublicCommonInvoicePayment(this.token(), this.email().trim(), this.offerConsent(), this.privacyConsent(), this.receiptConsent()).subscribe({
      next: (response) => {
        if (response.paymentUrl) {
          window.location.href = response.paymentUrl;
          return;
        }
        this.message.set('Банк не вернул ссылку на оплату.');
        this.submitting.set(false);
      },
      error: (error) => {
        this.error.set(this.errorMessage(error, 'Не удалось перейти к оплате.'));
        this.submitting.set(false);
      }
    });
  }

  formatRubles(amount?: number | null): string {
    return new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 2 }).format(amount ?? 0);
  }

  loadInvoice(): void {
    if (!this.token()) {
      this.loading.set(false);
      this.error.set('Общая платежная ссылка не найдена.');
      return;
    }
    this.loading.set(true);
    this.api.getPublicCommonInvoice(this.token()).subscribe({
      next: (invoice) => {
        this.invoice.set(invoice);
        this.loading.set(false);
      },
      error: (error) => {
        this.error.set(this.errorMessage(error, 'Не удалось открыть общий счет.'));
        this.loading.set(false);
      }
    });
  }

  private refreshAfterReturn(): void {
    const current = this.invoice();
    const now = Date.now();
    if (now - this.lastReturnRefreshAt < 1200 || this.loading() || this.refreshing() || !this.token() || !current || ['PAID', 'UNPAID', 'DISABLED'].includes(current.status)) {
      return;
    }
    this.lastReturnRefreshAt = now;
    this.refreshing.set(true);
    this.api.getPublicCommonInvoice(this.token()).subscribe({
      next: (invoice) => {
        this.invoice.set(invoice);
        this.refreshing.set(false);
      },
      error: () => this.refreshing.set(false)
    });
  }

  private statusText(status?: string): string {
    return {
      COLLECTING: 'Собирается',
      READY: 'Готов к счету',
      INVOICED: 'Выставлен',
      REMINDER: 'Напоминание',
      PARTIALLY_PAID: 'Частично оплачен',
      NEEDS_ATTENTION: 'Требует проверки',
      PAID: 'Оплачен',
      UNPAID: 'Не оплачен',
      BAN: 'Бан',
      DISABLED: 'Отключен'
    }[status ?? ''] ?? 'Проверяется';
  }

  private errorMessage(error: unknown, fallback: string): string {
    if (typeof error === 'object' && error && 'error' in error) {
      const body = (error as { error?: { message?: string; detail?: string; error?: string } | string }).error;
      return typeof body === 'string' ? body : body?.message || body?.detail || body?.error || fallback;
    }
    return fallback;
  }
}
