import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { ManagerReportReviewAnswerAttempt } from '../../../core/manager-control.api';
import { ManagerReviewAnswerAttemptsComponent } from './manager-review-answer-attempts.component';

describe('ManagerReviewAnswerAttemptsComponent', () => {
  beforeEach(() => TestBed.configureTestingModule({ imports: [ManagerReviewAnswerAttemptsComponent] }));

  function render(overrides: Partial<ManagerReportReviewAnswerAttempt> = {}) {
    const fixture = TestBed.createComponent(ManagerReviewAnswerAttemptsComponent);
    fixture.componentRef.setInput('attempts', [{
      attemptNumber: 3, questionIndex: 1, question: 'Что произошло?', answer: 'Счёт собирается. Проверю к 16:00.',
      recordedAt: '2026-10-22T12:30:00', decision: 'NEEDS_CLARIFICATION', reason: 'Не указан способ проверки', structuredAssessment: true,
      criteria: [{ criterionIndex: 0, criterion: 'Текущий статус', status: 'SATISFIED', answerAttempt: 1,
        answerQuote: 'Счёт собирается', evidenceRefs: ['E1'], reason: 'Статус совпадает' }],
      claims: [{ kind: 'PROMISE', status: 'PLAN', answerAttempt: 1, answerQuote: 'Проверю к 16:00', evidenceRefs: [], reason: 'Будущее действие' }],
      ...overrides
    }]);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('keeps attempts and criteria collapsed and explains promises separately from completion', () => {
    const element = render();
    expect(element.querySelector<HTMLDetailsElement>('.answer-attempt')?.open).toBe(false);
    expect(element.querySelector<HTMLDetailsElement>('.assessment-part')?.open).toBe(false);
    expect(element.textContent).toContain('Попытка 3 · Вопрос 2');
    expect(element.textContent).toContain('Текущий статус · Соответствует');
    expect(element.textContent).toContain('Обещание, выполнение не подтверждено');
    expect(element.textContent).toContain('Счёт собирается. Проверю к 16:00.');
    expect(element.textContent).toContain('Факты: E1');
  });

  it('does not invent detailed analysis for historical answers', () => {
    const element = render({ structuredAssessment: false, decision: 'LEGACY_ACCEPTED', criteria: [], claims: [], recordedAt: null });
    expect(element.textContent).toContain('Принят прежней проверкой');
    expect(element.textContent).toContain('подробный разбор по критериям для этой попытки не сохранялся');
    expect(element.querySelector('.assessment-part')).toBeNull();
  });

  it('renders manager and model text as inert text', () => {
    const element = render({ answer: '<script>alert(1)</script>', reason: '<a href="https://example.com">link</a>' });
    expect(element.querySelector('script, a')).toBeNull();
    expect(element.textContent).toContain('<script>alert(1)</script>');
  });
});
