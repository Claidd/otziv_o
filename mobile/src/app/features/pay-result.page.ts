import { Component, computed, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { IonContent } from '@ionic/angular/standalone';

type PaymentResult = 'success' | 'fail';

@Component({
  selector: 'app-pay-result-page',
  imports: [IonContent, RouterLink],
  template: `
    <div class="ion-page">
      <ion-content fullscreen>
        <main class="result-page">
          <a routerLink="/" class="brand">Компания <strong>О!</strong></a>
          <section class="result-card" [class.success]="isSuccess()">
            <span class="material-icons-sharp">{{ icon() }}</span>
            <p>{{ statusLabel() }}</p>
            <h1>{{ heroTitle() }}</h1>
            <strong>{{ heroText() }}</strong>
            <small>{{ lead() }}</small>
            <a class="primary" [routerLink]="actionLink()">{{ actionLabel() }}</a>
          </section>
        </main>
      </ion-content>
    </div>
  `,
  styles: [`
    ion-content{--background:#f6f8fc}.result-page{display:grid;gap:.9rem;max-width:34rem;margin:0 auto;padding:calc(1.2rem + env(safe-area-inset-top)) .9rem calc(1.2rem + env(safe-area-inset-bottom));font-family:var(--otziv-font-family)}.brand{color:var(--otziv-dark);font:900 1.15rem/1 var(--otziv-card-title-font);text-decoration:none}.brand strong{color:var(--otziv-danger)}
    .result-card{display:grid;gap:.55rem;place-items:center;min-height:24rem;border:1px solid rgba(239,68,68,.2);border-radius:1.15rem;padding:1.2rem;text-align:center;color:var(--otziv-danger);background:linear-gradient(155deg,var(--otziv-white),var(--otziv-tone-walk-surface));box-shadow:0 1rem 2rem rgba(132,139,200,.13)}.result-card.success{border-color:rgba(74,198,177,.28);color:#16735f}.result-card>.material-icons-sharp{font-size:4rem}.result-card p{margin:0;font-size:.72rem;font-weight:1000;text-transform:uppercase}.result-card h1{margin:0;color:var(--otziv-dark);font-size:2rem;line-height:1}.result-card strong{color:var(--otziv-dark);line-height:1.4}.result-card small{color:var(--otziv-info);font-weight:800;line-height:1.45}.primary{display:inline-flex;align-items:center;justify-content:center;min-height:2.55rem;border-radius:.82rem;padding:0 1rem;color:#fff;background:var(--otziv-primary);font-weight:1000;text-decoration:none}
  `]
})
export class PayResultPage {
  private readonly route = inject(ActivatedRoute);

  readonly result = (this.route.snapshot.data['result'] as PaymentResult | undefined) ?? 'fail';
  readonly isSuccess = computed(() => this.result === 'success');
  readonly icon = computed(() => this.isSuccess() ? 'task_alt' : 'error');
  readonly statusLabel = computed(() => this.isSuccess() ? 'Оплачено' : 'Не оплачено');
  readonly heroTitle = computed(() => this.isSuccess() ? 'Платеж успешно принят' : 'Платеж не подтвердился');
  readonly heroText = computed(() => this.isSuccess()
    ? 'Спасибо. Деньги поступили в банк, а электронный чек будет отправлен на указанный e-mail.'
    : 'Банк не подтвердил списание. Деньги не были приняты этой операцией.'
  );
  readonly actionLabel = computed(() => this.isSuccess() ? 'На главную' : 'Вернуться к оплате');
  readonly actionLink = computed(() => this.isSuccess() ? '/' : '/pay');
  readonly lead = computed(() => this.isSuccess()
    ? 'Статус заказа обновится автоматически после банковского уведомления.'
    : 'Можно открыть форму оплаты заново или связаться с менеджером.'
  );
}
