import { TestBed } from '@angular/core/testing';
import { COMMON_INVOICE_GUIDE, CommonInvoiceGuideComponent } from './common-invoice-guide.component';

describe('Common invoice guide', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [CommonInvoiceGuideComponent] });
  });

  function render(section = COMMON_INVOICE_GUIDE[0].id) {
    const fixture = TestBed.createComponent(CommonInvoiceGuideComponent);
    fixture.componentInstance.section = section;
    fixture.detectChanges();
    return fixture;
  }

  it('lists every section with its explanations', () => {
    const element: HTMLElement = render().nativeElement;
    const text = element.textContent ?? '';
    for (const section of COMMON_INVOICE_GUIDE) {
      expect(element.querySelector(`#common-invoice-guide-${section.id}`)).not.toBeNull();
      expect(text).toContain(section.title);
    }
    expect(element.querySelectorAll('.guide-toc button').length).toBe(COMMON_INVOICE_GUIDE.length);
  });

  it('highlights the section opened from a panel group', () => {
    const element: HTMLElement = render('route').nativeElement;
    expect(element.querySelector('#common-invoice-guide-route')?.classList).toContain('active');
    expect(element.querySelector('.guide-toc button.active')?.textContent).toContain('Смена получателя и способа оплаты');
  });

  it('closes by the button, the backdrop and Escape', () => {
    const fixture = render();
    let closed = 0;
    fixture.componentInstance.closed.subscribe(() => closed++);
    const element: HTMLElement = fixture.nativeElement;
    (element.querySelector('.guide-close') as HTMLButtonElement).click();
    (element.querySelector('.guide-backdrop') as HTMLElement).click();
    element.querySelector('.guide')?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    (element.querySelector('.guide') as HTMLElement).click();
    expect(closed).toBe(3);
  });

  it('names the side panel buttons by their labels', () => {
    for (const label of ['Выдать номер карты', 'Выдать ссылку банка', 'Выдать новую ссылку банка',
      'Деньги получил другой человек', 'Оплачен', 'Не оплачен', 'Выставить счёт и акт',
      'Скопировать ссылку на оплату', 'Закрыть проверку', 'Повторить обработку']) {
      expect(COMMON_INVOICE_GUIDE.some(section => section.items.some(item => item.term.includes(label)))).toBe(true);
    }
  });
});
