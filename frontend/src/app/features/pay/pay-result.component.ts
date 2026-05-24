import { Component, computed, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';

type PaymentResult = 'success' | 'fail';

@Component({
  selector: 'app-pay-result',
  imports: [AdminLayoutComponent, RouterLink],
  templateUrl: './pay-result.component.html',
  styleUrl: './pay-result.component.scss'
})
export class PayResultComponent {
  private readonly route = inject(ActivatedRoute);
  readonly result = (this.route.snapshot.data['result'] as PaymentResult | undefined) ?? 'fail';
  readonly serviceName = 'Репутационное сопровождение компании в сети Интернет';

  readonly isSuccess = computed(() => this.result === 'success');
  readonly title = computed(() => this.isSuccess() ? 'Оплата прошла успешно' : 'Оплата не завершена');
  readonly icon = computed(() => this.isSuccess() ? 'task_alt' : 'error');
  readonly statusLabel = computed(() => this.isSuccess() ? 'Оплачено' : 'Не оплачено');
  readonly heroTitle = computed(() => this.isSuccess() ? 'Платеж успешно принят' : 'Платеж не подтвердился');
  readonly heroText = computed(() => this.isSuccess()
    ? 'Спасибо. Деньги поступили в банк, а электронный чек будет отправлен на указанный e-mail.'
    : 'Банк не подтвердил списание. Деньги не были приняты этой операцией.'
  );
  readonly nextStepTitle = computed(() => this.isSuccess() ? 'Заказ принят в работу' : 'Можно повторить оплату');
  readonly actionLabel = computed(() => this.isSuccess() ? 'На главную' : 'Вернуться к оплате');
  readonly actionLink = computed(() => this.isSuccess() ? '/' : '/pay');
  readonly lead = computed(() => this.isSuccess()
    ? 'Статус заказа обновится автоматически после банковского уведомления. Если чек не придет, напишите нам.'
    : 'Банк не подтвердил оплату. Можно открыть форму оплаты заново или связаться с менеджером.'
  );
}
