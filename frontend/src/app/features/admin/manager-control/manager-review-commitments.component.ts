import { DatePipe } from '@angular/common';
import { Component, input } from '@angular/core';
import { ManagerReviewCommitment } from '../../../core/manager-control.api';

@Component({
  selector: 'app-manager-review-commitments',
  imports: [DatePipe],
  template: `
    @if (commitments().length) {
      <section aria-label="Обещанные действия по аудиту">
        <h4>Обещанные действия по аудиту</h4>
        <ul>
          @for (promise of commitments(); track promise.id) {
            <li [class.overdue]="promise.status === 'OVERDUE'">
              <strong>{{ promise.action }}</strong>
              <span>Ответственный: {{ promise.owner }}</span>
              <span>Срок проверки: {{ promise.dueAt | date: 'dd.MM.yyyy HH:mm' }}</span>
              <span>Проверка результата: {{ promise.verification }}</span>
              <small>{{ statusLabel(promise.status) }}</small>
            </li>
          }
        </ul>
      </section>
    }
  `,
  styles: `
    :host { display: block; }
    section { margin-top: 12px; padding: 14px; border: 1px solid var(--border, #d6dee8); border-radius: 12px; }
    h4 { margin: 0 0 8px; font-size: 13px; }
    ul { list-style: none; margin: 0; padding: 0; }
    li { display: grid; gap: 4px; padding-left: 10px; border-left: 3px solid #8194ae; font-size: 13px; line-height: 1.45; overflow-wrap: anywhere; }
    li + li { margin-top: 12px; }
    .overdue { border-color: #c74936; }
    small { color: var(--text-muted, #617087); }
    .overdue small { color: #b53a29; }
  `
})
export class ManagerReviewCommitmentsComponent {
  readonly commitments = input<ManagerReviewCommitment[]>([]);

  statusLabel(status: string): string {
    switch (status) {
      case 'PLANNED': return 'Запланировано; результат ещё не подтверждён.';
      case 'OVERDUE': return 'Срок проверки наступил; результат требует подтверждения.';
      case 'NEEDS_CONTEXT': return 'Для проверки результата нужны дополнительные данные.';
      case 'CASE_RESOLVED': return 'Проблема закрыта; выполнение обещанного действия отдельно не подтверждено.';
      case 'REPLANNED': return 'План пересмотрен; прежнее обещание сохранено в истории.';
      default: return 'Состояние выполнения уточняется.';
    }
  }
}
