import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';

@Component({
  selector: 'app-pay-result',
  imports: [AdminLayoutComponent, RouterLink],
  templateUrl: './pay-result.component.html',
  styleUrl: './pay-result.component.scss'
})
export class PayResultComponent {
  // Both bank return URLs are public and carry no verified payment identity.
  // Only the original payment page can show the invoice's current state.
  readonly title = 'Возврат из банка';
  readonly serviceName = 'Репутационное сопровождение компании в сети Интернет';
  readonly statusLabel = 'Статус платежа не проверен';
  readonly heroTitle = 'Откройте исходную ссылку на счёт';
  readonly heroText = 'По исходной ссылке на оплату отображается актуальный статус счёта.';
  readonly nextStepTitle = 'Проверьте статус перед повторной оплатой';
  readonly lead = 'Подтверждение банка может поступить с задержкой. Если статус не обновился, свяжитесь с менеджером.';
}
