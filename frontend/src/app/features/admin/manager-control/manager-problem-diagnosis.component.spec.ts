import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { ManagerProblemDiagnosticView } from '../../../core/manager-control.api';
import { ManagerProblemDiagnosisComponent } from './manager-problem-diagnosis.component';

function fixtureView(overrides: Partial<ManagerProblemDiagnosticView> = {}): ManagerProblemDiagnosticView {
  return {
    caseId: 12, version: 3, episode: 1, status: 'READY', observedAt: '2026-10-22T10:00:00', rolloutMode: 'PILOT',
    facts: [{ reference: 'invoice:297:status', subject: 'Счёт №297', field: 'status', value: 'NEEDS_ATTENTION' }],
    diagnosis: {
      summary: 'Публикация требует проверки',
      confirmedFacts: [{ text: 'Сервис вернул конфликт публикации', evidenceRefs: ['invoice:297:status'] }],
      hypotheses: [{ text: 'Возможен повторный текст', evidenceRefs: [] }],
      missingEvidence: ['Нужна проверка опубликованного отзыва'],
      actions: [{ code: 'CHECK_SOURCE', text: 'Откройте отзыв', owner: 'MANAGER', verification: 'Сравните текст', evidenceRefs: [] }],
      verification: ['Статус публикации подтверждён']
    },
    ...overrides
  };
}

describe('ManagerProblemDiagnosisComponent', () => {
  beforeEach(() => TestBed.configureTestingModule({ imports: [ManagerProblemDiagnosisComponent] }));

  function render(view: ManagerProblemDiagnosticView) {
    const fixture = TestBed.createComponent(ManagerProblemDiagnosisComponent);
    fixture.componentRef.setInput('view', view);
    fixture.detectChanges();
    return fixture;
  }

  it('separates confirmed facts, hypotheses, missing context and evidence in the card', () => {
    const text = render(fixtureView()).nativeElement.textContent;
    expect(text).toContain('Что подтверждено');
    expect(text).toContain('Возможные причины — требуют проверки');
    expect(text).toContain('Каких данных не хватает');
    expect(text).toContain('invoice:297:status');
    expect(text).toContain('Ответственный: менеджер');
    expect(text).toContain('Проверка результата: Сравните текст');
  });

  it.each(['QUEUED', 'RUNNING', 'RETRY', 'UNAVAILABLE', 'UNRECOGNIZED'])(
    'does not show a stale diagnosis during %s', (status) => {
      const text = render(fixtureView({ status })).nativeElement.textContent;
      expect(text).not.toContain('Откройте отзыв');
      expect(text).toContain('NEEDS_ATTENTION');
    }
  );

  it.each(['SHADOW', 'OFF'])('does not expose advice in %s mode', (rolloutMode) => {
    const text = render(fixtureView({ rolloutMode })).nativeElement.textContent;
    expect(text).not.toContain('Откройте отзыв');
    expect(text).toContain('NEEDS_ATTENTION');
  });

  it('renders source and model text without executing markup or adding links', () => {
    const view = fixtureView();
    view.diagnosis!.summary = '<a href="https://external.example">click</a>';
    view.facts[0].value = '<img src="bad" onerror="alert(1)">';
    const element: HTMLElement = render(view).nativeElement;
    expect(element.querySelector('a, img, script')).toBeNull();
    expect(element.textContent).toContain('<img src="bad"');
  });

  it('explains invoice facts in Russian and keeps technical references inside expandable evidence', () => {
    const view = fixtureView();
    view.facts[0] = { reference: 'invoice:297:status', subject: 'invoice', field: 'status', value: 'COLLECTING' };
    view.diagnosis!.confirmedFacts[0].text = 'invoice.status = COLLECTING';
    const element: HTMLElement = render(view).nativeElement;
    expect(element.querySelector('.confirmed-fact')?.textContent).toBe('Счёт №297: статус — Собирается');
    const evidence = element.querySelector<HTMLDetailsElement>('details.finding-evidence')!;
    expect(evidence.open).toBe(false);
    expect(evidence.querySelector('summary')?.textContent).toBe('Доказательства');
    expect(evidence.textContent).toContain('invoice:297:status');
    expect(element.querySelector('.confirmed-fact')?.textContent).not.toContain('invoice:');
  });

  it('preserves unknown evidence as escaped text without inferring its meaning', () => {
    const view = fixtureView();
    view.facts[0] = { reference: 'future:297:state', subject: 'future', field: 'state', value: '<img src="bad">' };
    view.diagnosis!.confirmedFacts[0].evidenceRefs = ['future:297:state'];
    const element: HTMLElement = render(view).nativeElement;
    expect(element.querySelector('.confirmed-fact')?.textContent).toBe('future: state — <img src="bad">');
    expect(element.querySelector('img')).toBeNull();
  });
});
