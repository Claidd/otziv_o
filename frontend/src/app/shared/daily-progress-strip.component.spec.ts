import { TestBed } from '@angular/core/testing';
import { DailyWorkProgress } from '../core/daily-progress';
import { DailyProgressStripComponent } from './daily-progress-strip.component';

describe('DailyProgressStripComponent pending work', () => {
  it('explains that 100 percent does not close tasks waiting for an account', () => {
    const fixture = TestBed.createComponent(DailyProgressStripComponent);
    fixture.componentInstance.progress = {
      visible: true, roleType: 'WORKER', completed: 10, total: 10, active: 0,
      percent: 100, checked: true, externalBlockedCount: 1, lateExcludedCount: 2,
    } as DailyWorkProgress;
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.textContent).toContain('100%');
    const notice = element.querySelector('.daily-progress-exceptions')?.textContent;
    expect(notice).toContain('Ждут аккаунта: 1');
    expect(notice).toContain('Поздние задачи: 2');
    expect(notice).toContain('не означает закрытие этих карточек');
  });

  it('keeps ordinary completed progress free of exception notices', () => {
    const fixture = TestBed.createComponent(DailyProgressStripComponent);
    fixture.componentInstance.progress = {
      visible: true, roleType: 'WORKER', completed: 10, total: 10, active: 0,
      percent: 100, checked: true,
    } as DailyWorkProgress;
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.daily-progress-exceptions')).toBeNull();
  });
});
