import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ModalController } from '@ionic/angular';
import { MobilePaymentRecipientPickerComponent } from './mobile-payment-recipient-picker.component';
import type {
  MobilePaymentRecipientChoice,
  MobilePaymentRecipientIssueOutcome,
  MobilePaymentRecipientLoad,
  MobilePaymentRecipientOption
} from './payment-recipient-picker';

const settle = async () => { for (let i = 0; i < 10; i++) await Promise.resolve(); };

describe('«Выдать номер карты» window of the app', () => {
  const person = (overrides: Partial<MobilePaymentRecipientOption>): MobilePaymentRecipientOption => ({
    profileId: 1, name: 'Лидия З.', role: 'SPECIALIST', orderSpecialist: false, manager: false, teamMember: true,
    workedWithClient: false, availableKopecks: 300_000, overrunKopecks: 0, current: false, blockReason: null, ...overrides
  });
  const people = [
    person({ profileId: 1, name: 'Лидия З.', orderSpecialist: true, current: true }),
    person({ profileId: 2, name: 'Вика Ц.', role: 'MANAGER', manager: true, teamMember: false }),
    person({ profileId: 3, name: 'Анна П.', workedWithClient: true, availableKopecks: 40_000, overrunKopecks: 60_000 }),
    person({ profileId: 4, name: 'Олег К.', blockReason: 'допуск к приёму оплат выключен' })
  ];

  async function open(setup: {
    loadRecipients?: () => Promise<MobilePaymentRecipientLoad>;
    issue?: (choice: MobilePaymentRecipientChoice) => Promise<MobilePaymentRecipientIssueOutcome>;
    amountKopecks?: number | null;
  } = {}) {
    const dismiss = vi.fn().mockResolvedValue(true);
    const everyone = async (): Promise<MobilePaymentRecipientLoad> => ({ recipients: people });
    const issued = async (_choice: MobilePaymentRecipientChoice): Promise<MobilePaymentRecipientIssueOutcome> => ({ status: 'issued' });
    const loadRecipients = vi.fn(setup.loadRecipients ?? everyone);
    const issue = vi.fn(setup.issue ?? issued);
    TestBed.configureTestingModule({
      imports: [MobilePaymentRecipientPickerComponent],
      providers: [{ provide: ModalController, useValue: { dismiss } }]
    });
    const fixture = TestBed.createComponent(MobilePaymentRecipientPickerComponent);
    fixture.componentRef.setInput('heading', 'Общий счёт №318');
    fixture.componentRef.setInput('currentRecipient', 'Лидия З. (специалист заказа)');
    fixture.componentRef.setInput('amountKopecks', setup.amountKopecks === undefined ? 90_000 : setup.amountKopecks);
    fixture.componentRef.setInput('loadRecipients', loadRecipients);
    fixture.componentRef.setInput('issue', issue);
    await render(fixture);
    return { fixture, component: fixture.componentInstance, element: fixture.nativeElement as HTMLElement, dismiss, loadRecipients, issue };
  }

  async function render(fixture: ComponentFixture<MobilePaymentRecipientPickerComponent>): Promise<void> {
    fixture.detectChanges(); await settle(); fixture.detectChanges();
  }

  const rows = (element: HTMLElement) => Array.from(element.querySelectorAll('.pick-list--people .pick-option'))
    .map(row => row.textContent?.replace(/\s+/g, ' ').trim() ?? '');

  it('starts with the automatic choice and names every person with the reserve', async () => {
    const { fixture, component, element, issue, dismiss } = await open();
    expect(component.selected()).toBeNull();
    expect(element.querySelector('ion-title')?.textContent).toContain('Выдать номер карты');
    expect(element.querySelector('.pick-head')?.textContent).toContain('Сейчас в счёте: Лидия З. (специалист заказа)');
    expect(element.querySelector('.pick-amount')?.textContent).toContain('900,00');
    expect(element.querySelector('.pick-option.selected')?.textContent).toContain('Подобрать автоматически');
    const list = rows(element);
    expect(list[0]).toContain('специалист заказа'); expect(list[0]).toContain('сейчас в счёте');
    expect(list[1]).toContain('менеджер'); expect(list[1]).toContain('хватает');
    expect(list[2]).toContain('работал с этим клиентом'); expect(list[2]).toContain('сверх резерва');
    expect(list[3]).toContain('допуск к приёму оплат выключен'); expect(list[3]).toContain('недоступен');
    expect(Array.from(element.querySelectorAll('.pick-list--people .pick-tag'), tag => tag.className))
      .toEqual(['pick-tag pick-tag--current', 'pick-tag pick-tag--success', 'pick-tag pick-tag--warning', 'pick-tag pick-tag--muted']);
    expect(element.querySelector('input[type="search"]')).toBeNull();
    expect(element.querySelector('.pick-consequence')?.textContent).toContain('клиент ещё не оплатил по прежним реквизитам');

    await component.submit(); await render(fixture);
    expect(issue).toHaveBeenCalledExactlyOnceWith({ profileId: null });
    expect(dismiss).toHaveBeenCalledExactlyOnceWith({ status: 'issued' }, 'issued');
  });

  it('lets a manager name a person above the reserve and says what that means before the button', async () => {
    const { fixture, component, element, issue } = await open();
    const inputs = Array.from(element.querySelectorAll<HTMLInputElement>('.pick-list--people input'));
    inputs[2].dispatchEvent(new Event('change')); await render(fixture);
    expect(component.selected()).toBe(3); expect(component.chosenOverrun()).toBe(60_000);
    const consequence = element.querySelector('ion-footer .pick-consequence');
    expect(consequence?.classList.contains('warning')).toBe(true); expect(consequence?.getAttribute('role')).toBe('status');
    expect(consequence?.textContent).toContain('сверх резерва на 600,00');
    expect(consequence?.textContent).toContain('не будет сама назначать');
    expect(consequence?.textContent).toContain('клиент ещё не оплатил');

    await component.submit();
    expect(issue).toHaveBeenCalledExactlyOnceWith({ profileId: 3 });
  });

  it('never submits the current recipient or a person who cannot receive invoices', async () => {
    const { component, element, issue, dismiss } = await open();
    const inputs = Array.from(element.querySelectorAll<HTMLInputElement>('.pick-list--people input'));
    expect(inputs.map(input => input.disabled)).toEqual([true, false, false, true]);
    component.select(1); await component.submit();
    component.select(4); await component.submit();
    component.select(99); await component.submit();
    expect(issue).not.toHaveBeenCalled(); expect(dismiss).not.toHaveBeenCalled();
  });

  it('keeps the window open with the refusal of the server so another person can be chosen at once', async () => {
    const answers: MobilePaymentRecipientIssueOutcome[] = [
      { status: 'refused', message: 'Сейчас ни специалист, ни менеджер не могут принять сумму общего счёта' },
      { status: 'issued' }
    ];
    const { fixture, component, element, issue, dismiss } = await open({ issue: async () => answers.shift()! });
    await component.submit(); await render(fixture);
    expect(dismiss).not.toHaveBeenCalled(); expect(component.saving()).toBe(false);
    const refusal = element.querySelector('ion-footer .pick-error');
    expect(refusal?.textContent).toContain('не могут принять'); expect(refusal?.getAttribute('role')).toBe('alert');

    component.select(2); await component.submit(); await render(fixture);
    expect(issue).toHaveBeenLastCalledWith({ profileId: 2 });
    expect(element.querySelector('ion-footer .pick-error')).toBeNull();
    expect(dismiss).toHaveBeenCalledExactlyOnceWith({ status: 'issued' }, 'issued');
  });

  it('offers the card of the owner first, with what is left of the month, and sends it as its own choice', async () => {
    const { fixture, component, element, issue } = await open({
      loadRecipients: async () => ({ recipients: people, ownerCard: { name: 'Мия Р.', remainingKopecks: 4_500_000, current: false, blockReason: null } })
    });
    fixture.componentRef.setInput('autoHintWithOwnerCard', 'после сотрудников — карта владельца'); await render(fixture);
    const list = rows(element);
    expect(list).toHaveLength(5);
    expect(list[0]).toContain('Владелец · Мия Р.'); expect(list[0]).toContain('осталось в этом месяце 45 000,00'); expect(list[0]).toContain('лимит вмещает');
    expect(element.querySelector('.pick-option small')?.textContent).toContain('после сотрудников — карта владельца');

    element.querySelector<HTMLInputElement>('.pick-option--owner input')!.dispatchEvent(new Event('change')); await render(fixture);
    expect(component.selected()).toBe(-1); expect(component.chosenOverrun()).toBe(0);
    const consequence = element.querySelector('ion-footer .pick-consequence')?.textContent ?? '';
    expect(consequence).toContain('на карту владельца (Мия Р.)'); expect(consequence).toContain('месячный лимит карты');
    expect(consequence).toContain('подтверждает владелец'); expect(consequence).toContain('клиент ещё не оплатил');

    await component.submit();
    expect(issue).toHaveBeenCalledExactlyOnceWith({ profileId: -1 });
  });

  it('shows why the card of the owner cannot be issued, never submits it then, and has no owner line where the server sends none', async () => {
    const blocked = await open({
      loadRecipients: async () => ({ recipients: [], ownerCard: { name: 'Мия Р.', remainingKopecks: 5_000, current: false, blockReason: 'лимит карты на этот месяц не вмещает счёт' } })
    });
    const row = blocked.element.querySelector('.pick-option--owner');
    expect(row?.textContent?.replace(/\s+/g, ' ')).toContain('лимит карты на этот месяц не вмещает счёт (осталось в этом месяце 50,00');
    expect(row?.textContent).toContain('недоступен'); expect(row?.querySelector('input')?.disabled).toBe(true);
    // The automatic hint stays the ordinary one: the card cannot take this invoice.
    blocked.fixture.componentRef.setInput('autoHintWithOwnerCard', 'после сотрудников — карта владельца'); await render(blocked.fixture);
    expect(blocked.element.querySelector('.pick-option small')?.textContent).not.toContain('карта владельца');
    blocked.component.select(-1); await blocked.component.submit();
    expect(blocked.issue).not.toHaveBeenCalled();

    TestBed.resetTestingModule();
    const bankOnly = await open({ loadRecipients: async () => ({ recipients: [] }) });
    expect(bankOnly.element.querySelector('.pick-option--owner')).toBeNull();
    expect(bankOnly.element.querySelector('.pick-state')?.textContent).toContain('Сотрудников с платёжным профилем');
    bankOnly.component.select(-1); await bankOnly.component.submit();
    expect(bankOnly.issue).not.toHaveBeenCalled();
  });

  it('closes with the answer of the bank queue and silently when the page was left', async () => {
    const accepted: MobilePaymentRecipientIssueOutcome = { status: 'accepted', message: 'Запрос принят. Ожидаем ответ банка.' };
    const first = await open({ issue: async () => accepted });
    await first.component.submit();
    expect(first.dismiss).toHaveBeenCalledExactlyOnceWith(accepted, 'issued');
    TestBed.resetTestingModule();

    const second = await open({ issue: async () => ({ status: 'left' }) });
    await second.component.submit();
    expect(second.dismiss).toHaveBeenCalledExactlyOnceWith({ status: 'left' }, 'cancel');
  });

  it('cannot be closed or pressed twice while the card number is being issued', async () => {
    let answer!: (outcome: MobilePaymentRecipientIssueOutcome) => void;
    const { fixture, component, element, issue, dismiss } = await open({ issue: () => new Promise(resolve => answer = resolve) });
    const first = component.submit(); const second = component.submit(); await render(fixture);
    expect(issue).toHaveBeenCalledTimes(1); expect(component.saving()).toBe(true);
    expect(Array.from(element.querySelectorAll<HTMLFieldSetElement>('fieldset')).every(fieldset => fieldset.disabled)).toBe(true);
    component.close(); component.select(2);
    expect(dismiss).not.toHaveBeenCalled(); expect(component.selected()).toBeNull();
    answer({ status: 'issued' }); await Promise.all([first, second]);
    expect(dismiss).toHaveBeenCalledExactlyOnceWith({ status: 'issued' }, 'issued');
    component.close(); expect(dismiss).toHaveBeenCalledTimes(1);
  });

  it('shows why the people were not loaded, retries and still allows the automatic choice', async () => {
    let fail = true;
    const { fixture, component, element, loadRecipients, issue } = await open({
      amountKopecks: null,
      loadRecipients: async () => {
        if (fail) throw new Error('Не удалось загрузить сотрудников. Код 503.');
        return { recipients: people, amountKopecks: 150_000 };
      }
    });
    expect(element.querySelector('.pick-people .pick-error')?.textContent).toContain('Код 503');
    expect(element.querySelector('.pick-amount')).toBeNull();
    expect(rows(element)).toEqual([]);

    fail = false; await component.load(); await render(fixture);
    expect(loadRecipients).toHaveBeenCalledTimes(2);
    expect(element.querySelector('.pick-people .pick-error')).toBeNull();
    expect(element.querySelector('.pick-amount')?.textContent).toContain('500,00');
    expect(rows(element)).toHaveLength(4);
    await component.submit();
    expect(issue).toHaveBeenCalledExactlyOnceWith({ profileId: null });
  });

  it('offers a search by name once the list is long and keeps the choice while it is filtered', async () => {
    const many = Array.from({ length: 8 }, (_, index) => person({ profileId: index + 10, name: `Сотрудник ${index + 1}` }));
    const { fixture, component, element } = await open({ loadRecipients: async () => ({ recipients: [...many, person({ profileId: 3, name: 'Анна П.' })] }) });
    expect(element.querySelector('input[type="search"]')?.getAttribute('aria-label')).toBe('Поиск сотрудника по имени');
    component.select(3); component.query.set('  сотрудник 8 '); await render(fixture);
    expect(rows(element)).toHaveLength(1); expect(rows(element)[0]).toContain('Сотрудник 8');
    expect(component.chosenOption()?.name).toBe('Анна П.');
    component.query.set('никто'); await render(fixture);
    expect(element.querySelector('.pick-people')?.textContent).toContain('Никого не найдено. Проверьте имя.');
  });

  it('ignores an answer that arrives after the window is gone', async () => {
    let answer!: (outcome: MobilePaymentRecipientIssueOutcome) => void;
    const { fixture, component, dismiss } = await open({ issue: () => new Promise(resolve => answer = resolve) });
    const pending = component.submit(); await settle();
    fixture.destroy(); answer({ status: 'issued' }); await pending;
    expect(dismiss).not.toHaveBeenCalled();
  });
});
