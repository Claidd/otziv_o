import { DatePipe } from '@angular/common';
import { Component, input } from '@angular/core';
import { ManagerReportReviewAnswerAttempt } from '../../../core/manager-control.api';

@Component({
  selector: 'app-manager-review-answer-attempts',
  imports: [DatePipe],
  template: `
    @if (attempts().length) {
      <section aria-label="Разбор ответов менеджера">
        <h4>Ответы и результаты проверки</h4>
        @for (attempt of attempts(); track $index) {
          <details class="answer-attempt">
            <summary>
              Попытка {{ attempt.attemptNumber }} · {{ attempt.questionIndex < 0 ? 'План действий' : 'Вопрос ' + (attempt.questionIndex + 1) }}
              · {{ decisionLabel(attempt.decision) }}
            </summary>
            @if (attempt.recordedAt) { <small>Ответ записан {{ attempt.recordedAt | date: 'dd.MM.yyyy HH:mm:ss' }}</small> }
            <p><b>Вопрос:</b> {{ attempt.question }}</p>
            <b>Ответ менеджера</b>
            <blockquote>{{ attempt.answer }}</blockquote>
            @if (attempt.reason) { <p><b>Результат проверки:</b> {{ attempt.reason }}</p> }
            @if (!attempt.structuredAssessment) {
              <p class="note">Историческая проверка: подробный разбор по критериям для этой попытки не сохранялся.</p>
            }
            @if (attempt.criteria.length) {
              <h5>Соответствие вопросу</h5>
              @for (criterion of attempt.criteria; track $index) {
                <details class="assessment-part">
                  <summary>{{ criterion.criterion || 'Критерий ' + (criterion.criterionIndex + 1) }} · {{ statusLabel(criterion.status) }}</summary>
                  @if (criterion.answerQuote) { <blockquote>{{ criterion.answerQuote }}</blockquote> }
                  @if (criterion.reason) { <p>{{ criterion.reason }}</p> }
                  @if (criterion.evidenceRefs.length) { <p class="references">Факты: {{ criterion.evidenceRefs.join(', ') }}</p> }
                  @if (criterion.answerAttempt >= 0) { <small>Цитата из ответа №{{ criterion.answerAttempt + 1 }} в контексте проверки этого вопроса.</small> }
                </details>
              }
            }
            @if (attempt.claims.length) {
              <h5>Проверка утверждений</h5>
              @for (claim of attempt.claims; track $index) {
                <details class="assessment-part">
                  <summary>{{ kindLabel(claim.kind) }} · {{ statusLabel(claim.status) }}</summary>
                  @if (claim.answerQuote) { <blockquote>{{ claim.answerQuote }}</blockquote> }
                  @if (claim.reason) { <p>{{ claim.reason }}</p> }
                  @if (claim.evidenceRefs.length) { <p class="references">Факты: {{ claim.evidenceRefs.join(', ') }}</p> }
                  @if (claim.answerAttempt >= 0) { <small>Цитата из ответа №{{ claim.answerAttempt + 1 }} в контексте проверки этого вопроса.</small> }
                </details>
              }
            }
          </details>
        }
      </section>
    }
  `,
  styles: [`
    :host { display: block; overflow-wrap: anywhere; }
    h4 { margin: 16px 0 8px; font-size: 13px; }
    h5 { margin: 12px 0 4px; font-size: 12px; }
    details { padding: 8px; border: 1px solid var(--border, #d6dee8); border-radius: 8px; margin: 6px 0; }
    summary { cursor: pointer; font-weight: 600; }
    p { margin: 8px 0; white-space: pre-wrap; }
    blockquote { margin: 8px 0; padding: 8px; border-left: 3px solid #94a3b8; white-space: pre-wrap; }
    small, .note, .references { color: var(--text-muted, #617087); font-size: 11px; }
  `]
})
export class ManagerReviewAnswerAttemptsComponent {
  readonly attempts = input<ManagerReportReviewAnswerAttempt[]>([]);

  decisionLabel(value: string): string {
    return ({ ACCEPTED: 'Принят', NEEDS_CLARIFICATION: 'Нужно уточнение', NEEDS_CONTEXT: 'Нужны данные или сверка владельцем',
      UNAVAILABLE: 'Ожидает проверки', LEGACY_ACCEPTED: 'Принят прежней проверкой', LEGACY_REJECTED: 'Прежняя проверка запросила уточнение'
    } as Record<string, string>)[value] ?? value;
  }

  statusLabel(value: string): string {
    return ({ SATISFIED: 'Соответствует', MISSING: 'Не раскрыто', CONTRADICTED: 'Противоречит данным', UNVERIFIABLE: 'Не удалось подтвердить',
      SUPPORTED: 'Подтверждено', PLAN: 'Обещание, выполнение не подтверждено'
    } as Record<string, string>)[value] ?? value;
  }

  kindLabel(value: string): string {
    return ({ FACT: 'Факт', COMPLETED_ACTION: 'Заявление о выполненном действии', PROMISE: 'Обещание', HYPOTHESIS: 'Предположение', RETRACTION: 'Отзыв прежнего утверждения' } as Record<string, string>)[value] ?? value;
  }
}
