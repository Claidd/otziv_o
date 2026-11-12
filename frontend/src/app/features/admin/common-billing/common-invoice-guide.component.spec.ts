import { TestBed } from '@angular/core/testing';
import { ORDER_PAYMENT_GUIDE } from '../../manager/order-payment-guide';
import { COMMON_INVOICE_GUIDE, CommonInvoiceGuideComponent, GuideSectionContent } from './common-invoice-guide.component';
import { GUIDE_SHOTS, GUIDE_SHOTS_VERSION, GuideShot } from './guide-shots';

describe('Common invoice guide', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [CommonInvoiceGuideComponent] });
  });

  afterEach(() => {
    document.body.classList.remove('otziv-dark-theme');
  });

  function render(section = COMMON_INVOICE_GUIDE[0].id, sections: readonly GuideSectionContent[] = COMMON_INVOICE_GUIDE) {
    const fixture = TestBed.createComponent(CommonInvoiceGuideComponent);
    fixture.componentInstance.section = section;
    fixture.componentInstance.sections = sections;
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

  describe('step-by-step walkthroughs', () => {
    const manuals: readonly (readonly [string, readonly GuideSectionContent[]])[] = [
      ['common invoice', COMMON_INVOICE_GUIDE],
      ['order payment', ORDER_PAYMENT_GUIDE]
    ];
    const figures = (sections: readonly GuideSectionContent[]) =>
      sections.flatMap(section => (section.walkthroughs ?? []).flatMap(walk => walk.figures));

    it('shows the main tasks of both manuals as steps over screenshots', () => {
      const tasks = (sections: readonly GuideSectionContent[]) =>
        sections.flatMap(section => (section.walkthroughs ?? []).map(walk => `${section.id}: ${walk.title}`));
      expect(tasks(COMMON_INVOICE_GUIDE)).toEqual([
        'overview: Что где на панели счёта',
        'payment: Как отметить оплату переводом',
        'payment: Счёт — ссылка банка, а клиент перевёл на карту',
        'route: Как выдать номер карты',
        'route: Как вернуть клиенту ссылку банка',
        'send: Как отправить счёт клиенту',
        'term: Как дать клиенту отсрочку',
        'paper: Как выставить счёт и акт и следить за оплатой',
        'orders: Как вернуть исключённый заказ в счёт',
        'danger: Где эти кнопки',
        'settings: Как добавить или убрать компанию связи',
        'settings: Как включить общий счёт компании, у которой заказу уже выставлен свой счёт'
      ]);
      expect(tasks(ORDER_PAYMENT_GUIDE)).toEqual([
        'overview: Что где на карточке заказа',
        'invoice: Как отправить счёт самому',
        'paid: Как отметить оплату переводом',
        'paid: Счёт — ссылка банка, а клиент перевёл на карту',
        'route: Как открыть блок и что в нём',
        'route: Как выдать номер карты',
        'route: Как вернуть клиенту ссылку банка',
        'paper: Как выставить счёт и акт',
        'unpaid: Что можно сделать с неоплаченным заказом'
      ]);
    });

    it('gives every figure a described picture and at least one step', () => {
      for (const [name, sections] of manuals) {
        for (const figure of figures(sections)) {
          const size = GUIDE_SHOTS[figure.shot];
          expect(size, `${name}: ${figure.shot}`).toBeDefined();
          expect(size[0]).toBeGreaterThan(200);
          expect(size[1]).toBeGreaterThan(100);
          expect(figure.alt.trim().length, `${name}: ${figure.shot}`).toBeGreaterThan(20);
          expect(figure.steps.length, `${name}: ${figure.shot}`).toBeGreaterThan(0);
          expect(figure.steps.every(step => step.trim().length > 15)).toBe(true);
        }
      }
    });

    it('uses every published picture exactly once', () => {
      const used = manuals.flatMap(([, sections]) => figures(sections).map(figure => figure.shot)).sort();
      expect(used).toEqual((Object.keys(GUIDE_SHOTS) as GuideShot[]).sort());
    });

    it('keeps common invoice pictures out of the order manual and the other way round', () => {
      expect(figures(COMMON_INVOICE_GUIDE).every(figure => figure.shot.startsWith('ci-'))).toBe(true);
      expect(figures(ORDER_PAYMENT_GUIDE).every(figure => figure.shot.startsWith('op-'))).toBe(true);
    });

    it('numbers the steps through all pictures of a walkthrough, as the pictures are numbered', () => {
      const fixture = render('route');
      const walk = fixture.nativeElement.querySelector('#common-invoice-guide-route .guide-walk') as HTMLElement;
      expect(walk.querySelector('h4')?.textContent).toContain('Как выдать номер карты');
      const numbers = [...walk.querySelectorAll('.guide-step-number')].map(node => node.textContent?.trim());
      expect(numbers).toEqual(['1', '2', '3', '4', '5', '6', '7', '8', '9']);
      const perFigure = [...walk.querySelectorAll('.guide-figure')].map(node => node.querySelectorAll('.guide-steps li').length);
      expect(perFigure).toEqual([2, 4, 3]);
      // A screen reader hears the number that a sighted user sees in the circle.
      expect(walk.querySelector('.guide-steps li')?.textContent).toContain('Шаг 1.');
      expect(walk.querySelector('.guide-result')?.textContent).toContain('Клиент получил новый счёт');
    });

    it('reserves the place of a picture and loads it only when it is scrolled to', () => {
      const element: HTMLElement = render('payment').nativeElement;
      const image = element.querySelector('#common-invoice-guide-payment .guide-shot img') as HTMLImageElement;
      const [width, height] = GUIDE_SHOTS['ci-pay-button'];
      expect(image.getAttribute('src')).toBe(`/assets/guide/light/ci-pay-button.webp?v=${GUIDE_SHOTS_VERSION}`);
      expect(image.getAttribute('width')).toBe(String(width));
      expect(image.getAttribute('height')).toBe(String(height));
      expect(image.getAttribute('loading')).toBe('lazy');
      expect(image.alt).toContain('Оплачен');
    });

    it('puts a narrow picture beside its steps and a wide one above them', () => {
      const element: HTMLElement = render('payment').nativeElement;
      const [narrow, wide] = [...element.querySelectorAll('#common-invoice-guide-payment .guide-figure')];
      expect(narrow.classList.contains('guide-figure--wide')).toBe(false);
      expect(wide.classList.contains('guide-figure--wide')).toBe(true);
    });

    it('shows the pictures of the dark theme when the site is dark', () => {
      document.body.classList.add('otziv-dark-theme');
      const element: HTMLElement = render('payment').nativeElement;
      const image = element.querySelector('#common-invoice-guide-payment .guide-shot img') as HTMLImageElement;
      expect(image.getAttribute('src')).toContain('/assets/guide/dark/ci-pay-button.webp');
    });

    it('opens a picture at full size and closes it by Escape before the manual itself', () => {
      const fixture = render('payment');
      let closed = 0;
      fixture.componentInstance.closed.subscribe(() => closed++);
      const element: HTMLElement = fixture.nativeElement;
      expect(element.querySelector('.guide-zoom')).toBeNull();

      (element.querySelector('#common-invoice-guide-payment .guide-shot') as HTMLButtonElement).click();
      fixture.detectChanges();
      const zoom = element.querySelector('.guide-zoom') as HTMLElement;
      expect(zoom.querySelector('img')?.getAttribute('src')).toContain('ci-pay-button.webp');

      element.querySelector('.guide')?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
      fixture.detectChanges();
      expect(element.querySelector('.guide-zoom')).toBeNull();
      expect(closed).toBe(0);

      (element.querySelector('#common-invoice-guide-payment .guide-shot') as HTMLButtonElement).click();
      fixture.detectChanges();
      (element.querySelector('.guide-zoom') as HTMLElement).click();
      fixture.detectChanges();
      expect(element.querySelector('.guide-zoom')).toBeNull();
      expect(closed).toBe(0);

      element.querySelector('.guide')?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
      expect(closed).toBe(1);
    });

    it('renders the order manual with its own pictures', () => {
      const element: HTMLElement = render('overview', ORDER_PAYMENT_GUIDE).nativeElement;
      const sources = [...element.querySelectorAll('.guide-shot img')].map(image => image.getAttribute('src') ?? '');
      expect(sources.length).toBe(13);
      expect(sources.every(source => source.startsWith('/assets/guide/light/op-'))).toBe(true);
      expect(element.querySelector('#common-invoice-guide-route .guide-walk-intro')?.textContent).toContain('Сменить способ оплаты');
    });

    it('covers the whole payment path of an ordinary order, as the common invoice manual does for its panel', () => {
      expect(ORDER_PAYMENT_GUIDE.map(section => section.id))
        .toEqual(['overview', 'invoice', 'paid', 'route', 'remind', 'paper', 'unpaid', 'rules', 'situations']);
      // «Способ оплаты» opens the manual at its own section.
      const element: HTMLElement = render('route', ORDER_PAYMENT_GUIDE).nativeElement;
      expect(element.querySelector('.guide-toc button.active')?.textContent).toContain('Смена получателя и способа оплаты');
      for (const label of ['«оплатили»', '«Оплата конкретному специалисту»', '«напомнить»', '«не опл.»',
        'Кнопка «счет» в ряду ссылок', 'Действие «счет»', 'Выдать ссылку банка']) {
        expect(ORDER_PAYMENT_GUIDE.some(section => section.items.some(item => item.term === label)), label).toBe(true);
      }
    });

    it('says what the manual buttons of an order do not do', () => {
      const item = (id: string, term: string) =>
        ORDER_PAYMENT_GUIDE.find(section => section.id === id)?.items.find(row => row.term === term)?.text ?? '';
      // A manual reminder and the «счет» action only change the status: nothing is sent to the client.
      expect(item('remind', '«напомнить»')).toContain('Сообщение клиенту при этом не уходит');
      expect(item('invoice', 'Действие «счет»')).toContain('Только меняет статус');
      expect(item('invoice', 'Кнопка «счет» в ряду ссылок')).toContain('Клиенту ничего не отправляет');
      expect(item('remind', '«не опл.»')).toContain('только владелец и администратор');
    });

    it('tells that a message still being sent is a wait and not an error', () => {
      const route = COMMON_INVOICE_GUIDE.find(section => section.id === 'route');
      const wait = route?.items.find(item => item.term === 'Сообщение клиенту ещё отправляется');
      expect(wait?.text).toContain('Это не ошибка');
      expect(route?.items.some(item => item.term === 'Исход отправки уточняется')).toBe(true);
      const steps = (route?.walkthroughs ?? []).flatMap(walk => walk.figures.flatMap(figure => figure.steps));
      expect(steps.some(step => step.includes('ещё отправляется'))).toBe(true);
    });

    it('no longer advises to press the button again to get another recipient', () => {
      const text = JSON.stringify(COMMON_INVOICE_GUIDE);
      expect(text).not.toContain('нажмите ещё раз — выберется другой человек');
      expect(text).toContain('В окне оставьте «Подобрать автоматически» или выберите сотрудника сами');
    });
  });
});
