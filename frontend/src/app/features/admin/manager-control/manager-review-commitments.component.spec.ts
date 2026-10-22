import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { ManagerReviewCommitmentsComponent } from './manager-review-commitments.component';

describe('ManagerReviewCommitmentsComponent', () => {
  beforeEach(() => TestBed.configureTestingModule({ imports: [ManagerReviewCommitmentsComponent] }));

  function render(status: string) {
    const fixture = TestBed.createComponent(ManagerReviewCommitmentsComponent);
    fixture.componentRef.setInput('commitments', [{
      id: 1, action: 'Проверить доставку', owner: 'Менеджер', dueAt: '2026-10-22T15:00:00',
      verification: 'Найти отметку доставки', status
    }]);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('shows promised action, owner, deadline and confirmation for an overdue plan', () => {
    const element = render('OVERDUE');
    expect(element.textContent).toContain('Проверить доставку');
    expect(element.textContent).toContain('Ответственный: Менеджер');
    expect(element.textContent).toContain('22.10.2026 15:00');
    expect(element.textContent).toContain('Найти отметку доставки');
    expect(element.textContent).toContain('результат требует подтверждения');
    expect(element.querySelector('li.overdue')).not.toBeNull();
  });

  it('does not present a closed case as a completed promise', () => {
    expect(render('CASE_RESOLVED').textContent)
      .toContain('выполнение обещанного действия отдельно не подтверждено');
  });

  it('does not label an unknown future status as completed', () => {
    expect(render('FUTURE_STATUS').textContent).toContain('Состояние выполнения уточняется');
  });
});
