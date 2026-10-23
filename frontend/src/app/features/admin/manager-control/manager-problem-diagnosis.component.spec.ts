import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { ManagerProblemDiagnosticView } from '../../../core/manager-control.api';
import { ManagerProblemDiagnosisComponent } from './manager-problem-diagnosis.component';

function fixtureView(overrides: Partial<ManagerProblemDiagnosticView> = {}): ManagerProblemDiagnosticView {
  return {
    caseId: 12, version: 3, episode: 1, status: 'READY', observedAt: '2026-10-22T10:00:00', rolloutMode: 'PILOT',
    facts: [{ reference: 'invoice:297:status', subject: 'invoice', field: 'status', value: 'NEEDS_ATTENTION' }],
    diagnosis: {
      summary: 'Публикация требует проверки',
      confirmedFacts: [{ text: 'Сервис вернул конфликт публикации', evidenceRefs: ['invoice:297:status'] }],
      hypotheses: [{ text: 'Возможен повторный текст', evidenceRefs: [] }],
      missingEvidence: ['Нужна проверка опубликованного отзыва'],
      actions: [{ code: 'CHECK_SOURCE', text: 'Откройте отзыв и сравните текст', owner: 'MANAGER', verification: 'Сравните текст', evidenceRefs: [] }],
      verification: ['Статус публикации подтверждён']
    },
    ...overrides
  };
}

describe('ManagerProblemDiagnosisComponent', () => {
  beforeEach(() => TestBed.configureTestingModule({ imports: [ManagerProblemDiagnosisComponent] }));

  function render(view: ManagerProblemDiagnosticView, showTechnical = false) {
    const fixture = TestBed.createComponent(ManagerProblemDiagnosisComponent);
    fixture.componentRef.setInput('view', view);
    fixture.componentRef.setInput('showTechnical', showTechnical);
    fixture.detectChanges();
    return fixture;
  }

  function expandTechnical(fixture: ReturnType<typeof render>) {
    const details: HTMLDetailsElement = fixture.nativeElement.querySelector('.technical-details');
    details.open = true;
    details.dispatchEvent(new Event('toggle'));
    fixture.detectChanges();
  }

  it('gives the manager three practical blocks without hundreds of raw facts or hypotheses', () => {
    const view = fixtureView({ facts: Array.from({ length: 249 }, (_, index) => ({
      reference: `card:8769:raw_field_${index}`, subject: 'card', field: 'follow_up_at', value: '2026-10-23'
    })) });
    const element: HTMLElement = render(view).nativeElement;
    const text = element.textContent!;
    expect([...element.querySelectorAll('h4')].map(h => h.textContent)).toEqual([
      'Что случилось', 'Что сделать', 'Когда можно закрыть'
    ]);
    expect(text).toContain('Публикация требует проверки');
    expect(text).toContain('Откройте отзыв и сравните текст');
    expect(text).toContain('Ответственный: менеджер');
    expect(text).toContain('Статус публикации подтверждён');
    expect(text).not.toContain('card:8769');
    expect(text).not.toContain('follow_up_at');
    expect(text).not.toContain('Возможен повторный текст');
    expect(element.querySelector('dl, .technical-details, .evidence-ref')).toBeNull();
  });

  it('keeps administrator evidence out of the DOM until explicitly expanded', () => {
    const fixture = render(fixtureView(), true);
    expect(fixture.nativeElement.textContent).toContain('Технические данные для администратора');
    expect(fixture.nativeElement.textContent).not.toContain('invoice:297:status');
    expect(fixture.nativeElement.querySelector('dl')).toBeNull();
    expandTechnical(fixture);
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('invoice:297:status');
    expect(text).toContain('Возможные причины — требуют проверки');
    expect(text).toContain('Каких данных не хватает');
    expect(text).toContain('Проверка результата: Сравните текст');
  });

  it.each(['QUEUED', 'RUNNING', 'RETRY', 'UNAVAILABLE', 'UNRECOGNIZED'])(
    'does not show a stale model diagnosis during %s', (status) => {
      const text = render(fixtureView({ status, model: 'deepseek-flash' })).nativeElement.textContent;
      expect(text).not.toContain('Откройте отзыв');
      expect(text).not.toContain('NEEDS_ATTENTION');
    }
  );

  it.each(['QUEUED', 'RUNNING', 'RETRY', 'UNAVAILABLE', 'NEEDS_CONTEXT'])(
    'shows useful server advice during %s with an honest provider status', (status) => {
      const element: HTMLElement = render(fixtureView({ status, model: 'server-rules' })).nativeElement;
      expect(element.textContent).toContain('Предварительный разбор по данным системы');
      expect(element.textContent).toContain('Откройте отзыв и сравните текст');
      expect(element.querySelector('.diagnosis-note')?.textContent).toBeTruthy();
      if (status === 'UNAVAILABLE') expect(element.querySelector('.diagnosis-note')?.textContent).toBe('Расширенный анализ недоступен.');
    }
  );

  it.each(['SHADOW', 'OFF'])('does not expose advice in %s mode even for a server fallback', (rolloutMode) => {
    const text = render(fixtureView({ rolloutMode, model: 'server-rules' })).nativeElement.textContent;
    expect(text).not.toContain('Откройте отзыв');
    expect(text).not.toContain('NEEDS_ATTENTION');
    expect(text).toContain('Разбор пока не включён');
  });

  it('keeps extra business steps accessible without expanding all of them initially', () => {
    const view = fixtureView();
    view.diagnosis!.actions = Array.from({ length: 4 }, (_, i) => ({ ...view.diagnosis!.actions[0], text: `Действие ${i + 1}` }));
    view.diagnosis!.verification = ['Условие 1', 'Условие 2', 'Условие 3'];
    const element: HTMLElement = render(view).nativeElement;
    const business = element.querySelector('.business-diagnosis')!;
    expect(business.querySelectorAll(':scope > ol > li')).toHaveLength(3);
    expect(business.querySelectorAll(':scope > ul > li')).toHaveLength(2);
    const extra = [...business.querySelectorAll<HTMLDetailsElement>('details')];
    expect(extra.every(details => !details.open)).toBe(true);
    expect(extra[0].textContent).toContain('Действие 4');
    expect(extra[1].textContent).toContain('Условие 3');
  });

  it('renders source and model text without executing markup or adding links', () => {
    const view = fixtureView();
    view.diagnosis!.summary = '<a href="https://external.example">click</a>';
    view.facts[0].value = '<img src="bad" onerror="alert(1)">';
    const fixture = render(view, true);
    expandTechnical(fixture);
    const element: HTMLElement = fixture.nativeElement;
    expect(element.querySelector('a, img, script')).toBeNull();
    expect(element.textContent).toContain('<img src="bad"');
  });

  it('explains invoice facts in Russian inside administrator details', () => {
    const view = fixtureView();
    view.facts[0] = { reference: 'invoice:297:status', subject: 'invoice', field: 'status', value: 'COLLECTING' };
    view.diagnosis!.confirmedFacts[0].text = 'invoice.status = COLLECTING';
    const fixture = render(view, true);
    expandTechnical(fixture);
    expect(fixture.nativeElement.querySelector('.confirmed-fact')?.textContent).toBe('Счёт №297: статус — Собирается');
    expect(fixture.nativeElement.querySelector('.business-diagnosis')?.textContent).not.toContain('invoice:');
  });

  it('preserves unknown evidence as escaped text for the administrator without inferring its meaning', () => {
    const view = fixtureView();
    view.facts[0] = { reference: 'future:297:state', subject: 'future', field: 'state', value: '<img src="bad">' };
    view.diagnosis!.confirmedFacts[0].evidenceRefs = ['future:297:state'];
    const fixture = render(view, true);
    expandTechnical(fixture);
    expect(fixture.nativeElement.querySelector('.confirmed-fact')?.textContent).toBe('future: state — <img src="bad">');
    expect(fixture.nativeElement.querySelector('img')).toBeNull();
  });
});
