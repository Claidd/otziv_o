import { TestBed } from '@angular/core/testing';
import { ContractorPaymentMetricHelpComponent } from './contractor-payment-metric-help.component';

describe('ContractorPaymentMetricHelpComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ContractorPaymentMetricHelpComponent]
    }).compileComponents();
  });

  it('shows a clear metric label and toggles its explanation accessibly', () => {
    const fixture = TestBed.createComponent(ContractorPaymentMetricHelpComponent);
    fixture.componentRef.setInput('label', 'Клиент сообщил об оплате');
    fixture.componentRef.setInput(
      'description',
      'Это ещё не подтверждение поступления денег — требуется сверка.'
    );
    fixture.componentRef.setInput('descriptionId', 'metric-help-client-reported');
    fixture.detectChanges();

    const trigger = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    const description = fixture.nativeElement.querySelector('p') as HTMLParagraphElement;

    expect(trigger.textContent).toContain('Клиент сообщил об оплате');
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    expect(trigger.getAttribute('aria-controls')).toBe('metric-help-client-reported');
    expect(description.id).toBe('metric-help-client-reported');
    expect(description.hidden).toBe(true);

    trigger.click();
    fixture.detectChanges();

    expect(fixture.componentInstance.expanded()).toBe(true);
    expect(trigger.getAttribute('aria-expanded')).toBe('true');
    expect(description.hidden).toBe(false);
    expect(description.textContent).toContain('не подтверждение поступления');

    trigger.click();
    fixture.detectChanges();

    expect(fixture.componentInstance.expanded()).toBe(false);
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    expect(description.hidden).toBe(true);
  });
});
