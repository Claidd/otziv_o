import { TestBed } from '@angular/core/testing';
import { CompanyNoteTriggerComponent } from './company-note-trigger.component';

describe('CompanyNoteTriggerComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CompanyNoteTriggerComponent]
    }).compileComponents();
  });

  it('toggles the note popover from the warning icon', () => {
    const fixture = TestBed.createComponent(CompanyNoteTriggerComponent);
    fixture.componentRef.setInput('note', 'Company note');
    fixture.detectChanges();

    const trigger = fixture.nativeElement.querySelector('.t') as HTMLElement;

    trigger.click();
    fixture.detectChanges();

    expect(fixture.componentInstance.open()).toBe(true);
    expect(trigger.classList.contains('e')).toBe(true);
    expect(trigger.getAttribute('aria-expanded')).toBe('true');

    trigger.click();
    fixture.detectChanges();

    expect(fixture.componentInstance.open()).toBe(false);
    expect(trigger.classList.contains('e')).toBe(false);
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
  });
});
