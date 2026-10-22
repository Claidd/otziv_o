import { DatePipe } from '@angular/common';
import { Component, computed, input } from '@angular/core';
import { ManagerProblemDiagnosticView } from '../../../core/manager-control.api';

type EvidenceFact = ManagerProblemDiagnosticView['facts'][number];

const SUBJECT_LABELS: Record<string, string> = {
  invoice: 'Счёт', invoice_order: 'Позиция счёта', card: 'Замечание',
  automation: 'Автоматическое сообщение', automation_attempt: 'Попытка отправки',
  client_chat: 'Переписка с клиентом', client_chat_message: 'Сообщение',
  recorded_chat_message: 'Записанное сообщение', coverage: 'Полнота данных', history_coverage: 'История попыток'
};
const FIELD_LABELS: Record<string, string> = {
  status: 'статус', item_status: 'статус замечания', state_status: 'состояние автоматизации',
  attempt_status: 'результат попытки', delivery_status: 'статус доставки',
  orderCount: 'позиций в счёте', readyCount: 'готовых позиций', orderIds: 'номера позиций',
  invoice_id: 'номер счёта', order_id: 'номер заказа', state_id: 'номер автоматизации', attempt_id: 'номер попытки', id: 'номер записи',
  amount_kopecks: 'сумма, коп.', paid_kopecks: 'оплачено, коп.', ready: 'позиция готова', paid: 'позиция оплачена',
  active_membership: 'входит в счёт', publication_blocker_since: 'публикация заблокирована с', paid_at: 'время оплаты',
  last_error: 'последняя ошибка', last_error_code: 'код последней ошибки', last_error_message: 'последняя ошибка',
  error_code: 'код ошибки', error_message: 'описание ошибки', payment_success_notification_error: 'ошибка уведомления об оплате',
  sent_at: 'время отправки', next_reminder_at: 'следующее напоминание', consecutive_failures: 'ошибок подряд',
  next_attempt_at: 'следующая попытка', last_attempt_at: 'последняя попытка', last_success_at: 'последняя успешная попытка',
  attempted_at: 'время попытки', delivery_recovery_checked_at: 'доставка проверена', scenario: 'сценарий',
  title: 'название', entity_type: 'тип источника', entity_id: 'номер источника', reason: 'причина замечания', comment: 'комментарий',
  follow_up_at: 'следующая проверка', last_manual_touch_at: 'последнее действие менеджера', resolved_at: 'время закрытия замечания',
  availability: 'доступность источника', live_context: 'доступный контекст', automation_states_truncated: 'границы проверки автоматизации',
  attempt_history_truncated: 'границы истории', last_client_message_id: 'номер последнего сообщения клиента',
  last_client_message_at: 'последнее сообщение клиента', first_opened_at: 'начало ожидания ответа', closed_at: 'время закрытия',
  resolution_type: 'способ закрытия', resolution_message_id: 'номер ответа', resolution_reason_code: 'причина закрытия',
  resolved_by_user_id: 'номер ответившего пользователя', manual_override: 'ручное решение', reply_quality: 'оценка ответа',
  audit_required: 'требуется проверка', platform: 'канал', direction: 'направление', sender_role: 'отправитель',
  message_at: 'время сообщения', routing_ambiguous: 'получатель не определён однозначно',
  recorded_client_text: 'цитата клиента (содержание не подтверждено)', recorded_reply_text: 'цитата ответа (содержание не подтверждено)',
  recorded_text: 'цитата (содержание не подтверждено)'
};
const INVOICE_STATUS_LABELS: Record<string, string> = {
  COLLECTING: 'Собирается', READY: 'Готов к выставлению', INVOICED: 'Выставлен', REMINDER: 'Напоминание',
  PARTIALLY_PAID: 'Частично оплачен', NEEDS_ATTENTION: 'Требует внимания', PAID: 'Оплачен',
  UNPAID: 'Не оплачен', BAN: 'Бан', DISABLED: 'Отключён', ARCHIVED: 'В архиве'
};

@Component({
  selector: 'app-manager-problem-diagnosis',
  imports: [DatePipe],
  templateUrl: './manager-problem-diagnosis.component.html',
  styleUrl: './manager-problem-diagnosis.component.scss'
})
export class ManagerProblemDiagnosisComponent {
  readonly view = input.required<ManagerProblemDiagnosticView>();
  private readonly factsByReference = computed(() => new Map(this.view().facts.map(fact => [fact.reference, fact])));
  readonly analysis = computed(() => {
    const view = this.view();
    return ['READY', 'NEEDS_CONTEXT'].includes(view.status)
      && !['OFF', 'SHADOW'].includes(view.rolloutMode ?? '') ? view.diagnosis : null;
  });
  readonly statusText = computed(() => {
    const view = this.view();
    if (view.rolloutMode === 'SHADOW' || view.rolloutMode === 'OFF') {
      return 'Для разбора доступны подтверждённые данные ниже.';
    }
    switch (view.status) {
      case 'QUEUED':
      case 'RUNNING': return 'Собранные данные анализируются. Результат появится после обновления карточки.';
      case 'RETRY': return 'Анализ временно недоступен. Система повторит проверку.';
      case 'UNAVAILABLE': return 'Анализ недоступен. Проверьте исходные данные или передайте вопрос ответственному.';
      case 'NEEDS_CONTEXT': return 'Для подтверждения причины нужны дополнительные данные.';
      case 'READY': return 'Разбор подготовлен по состоянию на указанное время.';
      default: return 'Состояние разбора уточняется. Используйте подтверждённые данные ниже.';
    }
  });

  confirmedText(finding: { text: string; evidenceRefs: string[] }): string {
    const facts = finding.evidenceRefs.map(reference => this.factsByReference().get(reference));
    // Preserve a safe text fallback if the response references evidence this client does not have.
    return facts.length && facts.every((fact): fact is EvidenceFact => !!fact)
      ? facts.map(fact => this.factText(fact)).join('; ')
      : finding.text;
  }

  evidenceText(reference: string): string {
    const fact = this.factsByReference().get(reference);
    return fact ? this.factText(fact) : 'Исходный факт недоступен в этом срезе';
  }

  private factText(fact: EvidenceFact): string {
    const identifier = fact.reference.split(':')[1];
    const subject = SUBJECT_LABELS[fact.subject];
    const source = subject ? `${subject}${/^\d+$/.test(identifier ?? '') ? ` №${identifier}` : ''}` : fact.subject;
    let value = fact.value;
    if (fact.subject === 'invoice' && fact.field === 'status') value = INVOICE_STATUS_LABELS[value] ?? value;
    if (['ready', 'paid', 'active_membership', 'manual_override', 'audit_required', 'routing_ambiguous'].includes(fact.field)) {
      value = ({ true: 'да', false: 'нет', '1': 'да', '0': 'нет' } as Record<string, string>)[value.toLowerCase()] ?? value;
    }
    if (fact.field === 'state_status') value = ({ ACTIVE: 'Активно', PAUSED: 'Приостановлено', DONE: 'Завершено', DISABLED: 'Отключено' } as Record<string, string>)[value] ?? value;
    if (fact.field === 'attempt_status') value = ({ SENT: 'Отправлено', FAILED: 'Ошибка', SKIPPED: 'Пропущено' } as Record<string, string>)[value] ?? value;
    return `${source}: ${FIELD_LABELS[fact.field] ?? fact.field} — ${value}`;
  }

  ownerLabel(owner: string): string {
    const labels: Record<string, string> = {
      MANAGER: 'менеджер', OWNER: 'руководитель', DEVELOPER: 'разработчик',
      SUPPORT: 'техническая поддержка', SYSTEM: 'система', CLIENT: 'клиент',
      WORKER: 'специалист', PROVIDER: 'внешний сервис'
    };
    return labels[owner.toUpperCase()] ?? owner;
  }
}
