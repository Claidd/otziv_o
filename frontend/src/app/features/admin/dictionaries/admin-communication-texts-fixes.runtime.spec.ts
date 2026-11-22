import { Component, signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  type TestRequest,
} from '@angular/common/http/testing';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { ReactiveFormsModule } from '@angular/forms';
import { AdminCommunicationTextsApi } from '../../../core/admin-communication-texts.api';
import type {
  AdminManagerText,
  AdminPromoText,
  PromoButtonSlot,
  PromoTextAssignment,
  PromoTextManagementResponse,
} from '../../../core/admin-dictionaries.api';
import { AdminCommunicationTextsFacade } from './admin-communication-texts.facade';

const texts: AdminPromoText[] = [
  { id: 1, position: 1, text: 'Отлично) Вот наше предложение' },
  { id: 5, position: 5, text: 'Тексты на проверку по ссылке' },
  { id: 7, position: 7, text: 'Иначе передадим дело юристу' },
  {
    id: 25,
    position: 13,
    text: 'Здравствуйте, напоминаем об оплате выполненных заказов, пришлите чек',
  },
  { id: 26, position: 14, text: 'Запасной текст' },
];
const promoText = (id: number): AdminPromoText => texts.find((text) => text.id === id)!;

const slot = (
  section: string,
  sectionTitle: string,
  buttonKey: string,
  buttonLabel: string,
  position: number,
  defaultPromoTextId: number | null,
): PromoButtonSlot => ({
  section,
  sectionTitle,
  buttonKey,
  buttonLabel,
  outputPosition: position,
  defaultPromoPosition: position,
  defaultPromoTextId,
});
const leadsOffer = slot('leads', 'Лиды', 'offer', 'предложение', 1, 1);
const companyOffer = slot('manager_companies', 'Менеджер: компании', 'offer', 'предложение', 1, 1);
const threat = slot('manager_orders', 'Менеджер: заказы', 'threat', 'угроза', 7, 7);
const reviewFirst = slot(
  'manager_orders',
  'Менеджер: заказы',
  'review_first',
  'текст первого заказа',
  5,
  5,
);

const assignment = (
  managerId: number,
  managerTitle: string,
  button: PromoButtonSlot,
  promoTextId: number,
): PromoTextAssignment => ({
  id: managerId * 100 + promoTextId,
  managerId,
  managerTitle,
  section: button.section,
  sectionTitle: button.sectionTitle,
  buttonKey: button.buttonKey,
  buttonLabel: button.buttonLabel,
  outputPosition: button.outputPosition,
  promoTextId,
  promoTextLabel: 'Текст #13',
});

/** Иванов has a personal text on «угроза»; Петров has none. */
const management = (
  assignments: PromoTextAssignment[] = [assignment(1, 'Иванов', threat, 25)],
): PromoTextManagementResponse => ({
  texts: structuredClone(texts),
  managers: [
    { id: 1, title: 'Иванов' },
    { id: 2, title: 'Петров' },
  ],
  assignments,
  buttons: [leadsOffer, companyOffer, threat, reviewFirst],
});

const manager = (id: number, title: string): AdminManagerText => ({
  managerId: id,
  managerTitle: title,
  payText: `Оплата ${title}`,
  beginText: 'Начало',
  offerText: 'Предложение',
  reminderText: 'Напоминание',
  startText: 'Старт',
});
const blankManager: AdminManagerText = {
  managerId: 9,
  managerTitle: 'Сидоров',
  payText: '',
  beginText: '',
  offerText: ' ',
  reminderText: '',
  startText: '',
};

function setup(tab: 'promo' | 'managerTexts' = 'promo') {
  TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
  const http = TestBed.inject(HttpTestingController);
  const toast = { success: vi.fn(), error: vi.fn() };
  const page = { active: true, search: signal(''), selectedId: signal<number | null>(null) };
  const facade = new AdminCommunicationTextsFacade({
    api: TestBed.inject(AdminCommunicationTextsApi),
    toast,
    isActive: () => page.active,
    tab: () => tab,
    selectedId: page.selectedId,
    search: () => page.search(),
  });
  return { http, toast, page, facade };
}

function flushManagement(http: HttpTestingController, body = management()) {
  const read = http.expectOne(
    (request) => request.method === 'GET' && request.url.endsWith('/promo-texts/management'),
  );
  // The whole list is read every time: the search narrows only the table on the page.
  expect(read.request.params.has('keyword')).toBe(false);
  read.flush(body);
}

function loadPromo(
  http: HttpTestingController,
  facade: AdminCommunicationTextsFacade,
  body = management(),
) {
  facade.load();
  flushManagement(http, body);
}

function loadManagers(http: HttpTestingController, facade: AdminCommunicationTextsFacade) {
  facade.load();
  http
    .expectOne((request) => request.method === 'GET' && request.url.endsWith('/manager-texts'))
    .flush([manager(1, 'Иванов'), manager(2, 'Петров')]);
}

/** Flushes a refusal of the server with its Russian message, as the backend sends it. */
function refuse(request: TestRequest, message: string) {
  request.flush({ message }, { status: 404, statusText: 'Not Found' });
}

afterEach(() => {
  try {
    TestBed.inject(HttpTestingController).verify();
  } finally {
    TestBed.resetTestingModule();
  }
});

describe('promo search', () => {
  it('narrows only the table by the applied search; button choices and default names keep every text', () => {
    const { http, page, facade } = setup();
    page.search.set('  УГРОЗА ');
    loadPromo(http, facade);
    expect(facade.promoTexts().map((text) => text.id)).toEqual([7]);
    expect(facade.allPromoTexts().map((text) => text.id)).toEqual([1, 5, 7, 25, 26]);
    expect(facade.promoDefaultTextLabel(leadsOffer)).toBe('предложение');
    expect(facade.promoAssignedTextLabel(threat)).toBe('Текст #13');

    // A search typed but not run does not narrow the table after a save.
    page.search.set('напоминаем');
    facade.selectPromoText(promoText(7));
    facade.savePromoText();
    http
      .expectOne((request) => request.method === 'PUT' && request.url.endsWith('/promo-texts/7'))
      .flush(promoText(7));
    flushManagement(http);
    expect(facade.promoTexts().map((text) => text.id)).toEqual([7]);
  });

  it('finds a text by its number, position, name or words, ignoring case', () => {
    const { http, page, facade } = setup();
    const found = (query: string) => {
      page.search.set(query);
      loadPromo(http, facade);
      return facade.promoTexts().map((text) => text.id);
    };
    expect(found('26')).toEqual([26]);
    expect(found('13')).toEqual([25]);
    expect(found('ЮРИСТУ')).toEqual([7]);
    expect(found('ссылка на')).toEqual([5]);
    expect(found('')).toEqual([1, 5, 7, 25, 26]);
  });
});

describe('promo button assignments', () => {
  it('shows a choice while it is saved and the saved one again after a failure, naming the manager and button', () => {
    const { http, toast, facade } = setup();
    loadPromo(http, facade);
    expect(facade.selectedPromoManagerId()).toBe(1);
    facade.savePromoAssignment(threat, '1');
    expect(facade.promoAssignmentValue(threat)).toBe(1);
    expect(facade.promoAssignedTextLabel(threat)).toBe('Текст #13');
    refuse(
      http.expectOne((request) => request.method === 'PUT'),
      'Промо-текст не найден',
    );
    expect(facade.promoAssignmentValue(threat)).toBe(25);
    expect(facade.saving()).toBe(false);
    expect(toast.error).toHaveBeenCalledWith(
      'Назначение не сохранено',
      'Иванов, Менеджер: заказы · угроза. Промо-текст не найден',
    );
    expect(facade.error()).toBe(
      'Назначение не сохранено: Иванов, Менеджер: заказы · угроза. Промо-текст не найден',
    );
  });

  it('applies and announces an answer that arrives after another manager was opened', () => {
    const { http, toast, facade } = setup();
    loadPromo(http, facade);
    facade.savePromoAssignment(leadsOffer, '25');
    const write = http.expectOne((request) => request.method === 'PUT');
    expect(write.request.body).toEqual({
      managerId: 1,
      section: 'leads',
      buttonKey: 'offer',
      promoTextId: 25,
    });
    facade.selectPromoManager(2);
    expect(facade.promoAssignmentValue(leadsOffer)).toBe('');
    write.flush(assignment(1, 'Иванов', leadsOffer, 25));
    expect(toast.success).toHaveBeenCalledWith(
      'Назначение сохранено',
      'Иванов, Лиды · предложение',
    );
    facade.selectPromoManager(1);
    expect(facade.promoAssignmentValue(leadsOffer)).toBe(25);
    expect(facade.promoAssignedTextLabel(leadsOffer)).toBe('Текст #13');
  });

  it('reports a failed reset after another manager was opened and keeps the saved choice', () => {
    const { http, toast, facade } = setup();
    loadPromo(http, facade);
    facade.savePromoAssignment(threat, '');
    const write = http.expectOne(
      (request) =>
        request.method === 'DELETE' &&
        request.url.endsWith('/promo-text-assignments/1/manager_orders/threat'),
    );
    facade.selectPromoManager(2);
    refuse(write, 'Менеджер не найден');
    expect(toast.error).toHaveBeenCalledWith(
      'Назначение не сохранено',
      'Иванов, Менеджер: заказы · угроза. Менеджер не найден',
    );
    expect(facade.saving()).toBe(false);
    facade.selectPromoManager(1);
    expect(facade.promoAssignmentValue(threat)).toBe(25);
  });

  it('drops a reset choice at once and reads the list again for the same manager', () => {
    const { http, toast, facade } = setup();
    loadPromo(http, facade);
    facade.savePromoAssignment(threat, '');
    http.expectOne((request) => request.method === 'DELETE').flush(null);
    expect(facade.promoAssignmentValue(threat)).toBe('');
    expect(facade.promoAssignedTextLabel(threat)).toBe('по умолчанию: угроза');
    expect(toast.success).toHaveBeenCalledWith(
      'Назначение сохранено',
      'Иванов, Менеджер: заказы · угроза',
    );
    flushManagement(http, management([]));
  });
});

describe('save results after the editor moved on', () => {
  it('updates the saved manager row and announces it after another manager was opened', () => {
    const { http, toast, facade } = setup('managerTexts');
    loadManagers(http, facade);
    facade.selectManagerText(facade.managerTexts()[0]);
    facade.managerTextForm.controls.payText.setValue('Новый текст оплаты');
    facade.saveManagerText();
    const write = http.expectOne(
      (request) => request.method === 'PUT' && request.url.endsWith('/manager-texts/1'),
    );
    facade.selectManagerText(facade.managerTexts()[1]);
    write.flush({ ...manager(1, 'Иванов'), payText: 'Новый текст оплаты' });
    expect(facade.managerTexts()[0].payText).toBe('Новый текст оплаты');
    expect(facade.managerTextForm.controls.payText.value).toBe('Оплата Петров');
    expect(toast.success).toHaveBeenCalledWith('Тексты менеджера сохранены', 'Иванов');
    facade.selectManagerText(facade.managerTexts()[0]);
    expect(facade.managerTextForm.controls.payText.value).toBe('Новый текст оплаты');
  });

  it('names the manager when a save fails after another manager was opened', () => {
    const { http, toast, facade } = setup('managerTexts');
    loadManagers(http, facade);
    facade.selectManagerText(facade.managerTexts()[0]);
    facade.saveManagerText();
    const write = http.expectOne((request) => request.method === 'PUT');
    facade.selectManagerText(facade.managerTexts()[1]);
    refuse(write, 'Менеджер не найден');
    expect(toast.error).toHaveBeenCalledWith(
      'Тексты менеджера не сохранены',
      'Иванов. Менеджер не найден',
    );
    expect(facade.error()).toBe('Тексты менеджера не сохранены: Иванов. Менеджер не найден');
    expect(facade.saving()).toBe(false);
  });

  it('keeps a row saved while the window was hidden and reports a failure there', () => {
    const { http, toast, page, facade } = setup('managerTexts');
    loadManagers(http, facade);
    facade.selectManagerText(facade.managerTexts()[0]);
    facade.managerTextForm.controls.payText.setValue('Сохранён, пока окно скрыто');
    facade.saveManagerText();
    const saved = http.expectOne((request) => request.method === 'PUT');
    page.active = false;
    facade.deactivate();
    saved.flush({ ...manager(1, 'Иванов'), payText: 'Сохранён, пока окно скрыто' });
    expect(facade.managerTexts()[0].payText).toBe('Сохранён, пока окно скрыто');
    expect(facade.saving()).toBe(false);

    page.active = true;
    facade.saveManagerText();
    const failed = http.expectOne((request) => request.method === 'PUT');
    page.active = false;
    facade.deactivate();
    refuse(failed, 'Менеджер не найден');
    expect(toast.error).toHaveBeenCalledWith(
      'Тексты менеджера не сохранены',
      'Иванов. Менеджер не найден',
    );
    expect(facade.error()).toBe('Тексты менеджера не сохранены: Иванов. Менеджер не найден');
  });

  it('names a promo text whose save failed after another text was opened, and keeps a text saved while hidden', () => {
    const { http, toast, page, facade } = setup();
    loadPromo(http, facade);
    facade.selectPromoText(promoText(7));
    facade.promoTextForm.controls.text.setValue('Новая угроза');
    facade.savePromoText();
    const failed = http.expectOne(
      (request) => request.method === 'PUT' && request.url.endsWith('/promo-texts/7'),
    );
    facade.selectPromoText(promoText(1));
    refuse(failed, 'Промо-текст не найден');
    expect(toast.error).toHaveBeenCalledWith(
      'Промо-текст не сохранён',
      '«угроза», ID 7. Промо-текст не найден',
    );
    expect(facade.error()).toBe('Промо-текст не сохранён: «угроза», ID 7. Промо-текст не найден');
    expect(facade.saving()).toBe(false);

    facade.selectPromoText(promoText(7));
    facade.promoTextForm.controls.text.setValue('Новая угроза');
    facade.savePromoText();
    const saved = http.expectOne((request) => request.method === 'PUT');
    page.active = false;
    facade.deactivate();
    saved.flush({ id: 7, position: 7, text: 'Новая угроза' });
    expect(facade.allPromoTexts().find((text) => text.id === 7)?.text).toBe('Новая угроза');
    expect(facade.saving()).toBe(false);
  });
});

describe('forms after a save', () => {
  it('marks the manager form clean when nothing was typed during the save, not otherwise', () => {
    const { http, facade } = setup('managerTexts');
    loadManagers(http, facade);
    facade.selectManagerText(facade.managerTexts()[0]);
    facade.managerTextForm.markAsDirty();
    facade.saveManagerText();
    http.expectOne((request) => request.method === 'PUT').flush(manager(1, 'Иванов'));
    expect(facade.managerTextForm.dirty).toBe(false);

    facade.managerTextForm.markAsDirty();
    facade.saveManagerText();
    const write = http.expectOne((request) => request.method === 'PUT');
    facade.managerTextForm.controls.offerText.setValue('Набрано во время сохранения');
    write.flush(manager(1, 'Иванов'));
    expect(facade.managerTextForm.dirty).toBe(true);
    expect(facade.managerTextForm.controls.offerText.value).toBe('Набрано во время сохранения');
  });

  it('marks a created promo text clean, opens it and shows it in the list at once', () => {
    const { http, page, facade } = setup();
    loadPromo(http, facade);
    facade.clearSelection();
    facade.promoTextForm.controls.text.setValue('Новый текст');
    facade.promoTextForm.markAsDirty();
    facade.savePromoText();
    http
      .expectOne((request) => request.method === 'POST')
      .flush({ id: 30, position: 15, text: 'Новый текст' });
    expect(page.selectedId()).toBe(30);
    expect(facade.promoTextForm.dirty).toBe(false);
    expect(facade.promoTexts().map((text) => text.id)).toContain(30);
    flushManagement(http);
  });
});

describe('promo and pay text checks before sending', () => {
  it('treats spaces and line breaks alone as an empty promo text and does not send it', () => {
    const { http, facade } = setup();
    loadPromo(http, facade);
    facade.clearSelection();
    facade.promoTextForm.controls.text.setValue('  \n\t ');
    facade.savePromoText();
    http.expectNone((request) => request.method !== 'GET');
    expect(facade.promoTextForm.invalid).toBe(true);
    expect(facade.promoTextForm.touched).toBe(true);
  });

  it('refuses a promo text longer than its column, counting a line break as seven letters', () => {
    const { http, toast, facade } = setup();
    loadPromo(http, facade);
    facade.clearSelection();
    const lines = Array.from({ length: 101 }, () => 'а'.repeat(20)).join('\n');
    expect(lines.length).toBeLessThan(2555);
    facade.promoTextForm.controls.text.setValue(lines);
    facade.savePromoText();
    http.expectNone((request) => request.method === 'POST');
    expect(toast.error).toHaveBeenCalledWith(
      'Промо-текст слишком длинный',
      'Можно до 2555 знаков, каждый перенос строки считается за 7. Сейчас 2720.',
    );
    expect(facade.saving()).toBe(false);

    facade.promoTextForm.controls.text.setValue(`${'а'.repeat(2548)}\r\nб`);
    facade.savePromoText();
    http.expectNone((request) => request.method === 'POST');

    facade.promoTextForm.controls.text.setValue(`${'а'.repeat(2547)}\r\nб`);
    facade.savePromoText();
    http
      .expectOne((request) => request.method === 'POST')
      .flush({ id: 30, position: 15, text: 'новый' });
    flushManagement(http);
  });

  it('refuses a pay text longer than 1000 letters and counts emoji as the database does', () => {
    const { http, toast, facade } = setup('managerTexts');
    loadManagers(http, facade);
    facade.selectManagerText(facade.managerTexts()[0]);
    facade.managerTextForm.controls.payText.setValue('я'.repeat(1001));
    facade.saveManagerText();
    http.expectNone((request) => request.method === 'PUT');
    expect(toast.error).toHaveBeenCalledWith(
      'Текст оплаты слишком длинный',
      'Можно до 1000 знаков. Сейчас 1001.',
    );

    facade.managerTextForm.controls.payText.setValue('😀'.repeat(1000));
    facade.saveManagerText();
    http
      .expectOne((request) => request.method === 'PUT')
      .flush({ ...manager(1, 'Иванов'), payText: '😀'.repeat(1000) });
  });
});

describe('texts that say where a text is used', () => {
  it('names default buttons, specialists and personal choices of a promo text', () => {
    const { http, facade } = setup();
    loadPromo(
      http,
      facade,
      management([assignment(1, 'Иванов', leadsOffer, 1), assignment(2, 'Петров', threat, 25)]),
    );
    expect(facade.promoUsageSummary(promoText(1))).toBe(
      'по умолчанию — Лиды · предложение, Менеджер: компании · предложение; ' +
        'лично — Иванов (Лиды · предложение)',
    );
    expect(facade.promoUsageSummary(promoText(5))).toBe(
      'по умолчанию — Менеджер: заказы · текст первого заказа; специалисты — отзывы на проверку',
    );
    expect(facade.promoUsageSummary(promoText(25))).toBe(
      'лично — Петров (Менеджер: заказы · угроза)',
    );
    expect(facade.promoUsageSummary(promoText(26))).toBe('не назначен');

    facade.selectPromoText(promoText(7));
    expect(facade.selectedPromoTextUsage()).toBe(
      'Где стоит: по умолчанию — Менеджер: заказы · угроза. Правка изменит его везде.',
    );
    expect(facade.selectedPromoTextTitle()).toBe('Промо-текст «угроза», ID 7');
    facade.selectPromoText(promoText(26));
    expect(facade.selectedPromoTextUsage()).toBe('Текст пока не стоит ни на одной кнопке.');
    facade.clearSelection();
    expect(facade.selectedPromoTextUsage()).toBeNull();
    expect(facade.selectedPromoTextTitle()).toBe('Новый промо-текст');
  });

  it('labels the choices with the start of the text and a missing default text plainly', () => {
    const { http, facade } = setup();
    loadPromo(http, facade);
    expect(facade.promoTextOptionLabel(promoText(25))).toBe(
      'Текст #13 — Здравствуйте, напоминаем об оплате…',
    );
    expect(facade.promoTextOptionLabel(promoText(7))).toBe('угроза — Иначе передадим дело юристу');
    // An emoji on the edge of the preview stays whole instead of turning into «�».
    const emoji = { ...promoText(7), text: 'Ж'.repeat(39) + '😀' + 'хвост' };
    expect(facade.promoTextOptionLabel(emoji)).toBe('угроза — ' + 'Ж'.repeat(39) + '😀…');
    expect(facade.promoDefaultTextLabel(slot('leads', 'Лиды', 'answers', 'ответы', 12, null))).toBe(
      'текста на позиции 12 нет',
    );
  });

  it('counts only the pay text as used in the manager list', () => {
    const { facade } = setup('managerTexts');
    expect(facade.managerTextSummary(manager(1, 'Иванов'))).toBe('оплата · 4 поля не используются');
    expect(facade.managerTextSummary({ ...blankManager, payText: 'Оплата' })).toBe('оплата');
    expect(facade.managerTextSummary({ ...blankManager, startText: 'Старт' })).toBe(
      'оплата не заполнена · 1 поле не используется',
    );
    expect(facade.managerTextSummary(blankManager)).toBe('тексты не заполнены');
    expect(facade.managerTextPreview({ ...manager(1, 'Иванов'), payText: '' })).toBe('');
    expect(facade.managerTextPreview(manager(1, 'Иванов'))).toBe('Оплата Иванов');
  });
});

/**
 * The lists of the «Кнопки менеджера» block with the markup asked for the shared template: each option says whether
 * it is chosen ([selected]) instead of a [value] on the select, which Angular sets before the options exist.
 */
@Component({
  selector: 'app-promo-assignment-host',
  imports: [ReactiveFormsModule],
  template: `
    <select
      id="manager"
      (change)="facade.selectPromoManager($any($event.target).value)"
      [disabled]="facade.saving()"
    >
      @for (manager of facade.promoManagers(); track manager.id) {
        <option [value]="manager.id" [selected]="manager.id === facade.selectedPromoManagerId()">
          {{ manager.title }}
        </option>
      }
    </select>
    @for (button of facade.promoButtons(); track button.section + ':' + button.buttonKey) {
      <select
        [attr.data-button]="button.section + ':' + button.buttonKey"
        (change)="facade.savePromoAssignment(button, $any($event.target).value)"
        [disabled]="facade.saving() || facade.selectedPromoManagerId() === null"
        [attr.aria-label]="button.sectionTitle + ' · ' + button.buttonLabel"
      >
        <option value="" [selected]="facade.promoAssignmentValue(button) === ''">
          По умолчанию: {{ facade.promoDefaultTextLabel(button) }}
        </option>
        @for (promoText of facade.allPromoTexts(); track promoText.id) {
          <option
            [value]="promoText.id"
            [selected]="facade.promoAssignmentValue(button) === promoText.id"
          >
            {{ facade.promoTextOptionLabel(promoText) }}
          </option>
        }
      </select>
    }
  `,
})
class PromoAssignmentHost {
  facade!: AdminCommunicationTextsFacade;
}

describe('button lists with the requested markup', () => {
  function render(facade: AdminCommunicationTextsFacade): ComponentFixture<PromoAssignmentHost> {
    const fixture = TestBed.createComponent(PromoAssignmentHost);
    fixture.componentInstance.facade = facade;
    fixture.detectChanges();
    return fixture;
  }
  const list = (fixture: ComponentFixture<PromoAssignmentHost>, key: string) =>
    (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>(
      `[data-button="${key}"]`,
    )!;
  const managerList = (fixture: ComponentFixture<PromoAssignmentHost>) =>
    (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('#manager')!;
  function choose(select: HTMLSelectElement, value: string) {
    select.value = value;
    select.dispatchEvent(new Event('change'));
  }

  it('shows the saved choice when the lists appear, returns to it after a failure and allows «По умолчанию»', () => {
    const { http, facade } = setup();
    loadPromo(http, facade);
    const fixture = render(facade);
    const threatList = list(fixture, 'manager_orders:threat');
    expect(managerList(fixture).value).toBe('1');
    expect(threatList.value).toBe('25');
    expect(list(fixture, 'leads:offer').value).toBe('');
    expect(threatList.getAttribute('aria-label')).toBe('Менеджер: заказы · угроза');

    choose(threatList, '1');
    fixture.detectChanges();
    expect(threatList.disabled).toBe(true);
    expect(managerList(fixture).disabled).toBe(true);
    refuse(
      http.expectOne((request) => request.method === 'PUT'),
      'Промо-текст не найден',
    );
    fixture.detectChanges();
    expect(threatList.value).toBe('25');
    expect(threatList.disabled).toBe(false);

    choose(threatList, '');
    http
      .expectOne(
        (request) =>
          request.method === 'DELETE' &&
          request.url.endsWith('/promo-text-assignments/1/manager_orders/threat'),
      )
      .flush(null);
    fixture.detectChanges();
    expect(threatList.value).toBe('');
    flushManagement(http, management([]));
    fixture.detectChanges();
    expect(threatList.value).toBe('');
  });

  it('keeps the chosen manager in the list when the promo view is created again', () => {
    const { http, facade } = setup();
    loadPromo(http, facade);
    const first = render(facade);
    choose(managerList(first), '2');
    first.detectChanges();
    expect(list(first, 'manager_orders:threat').value).toBe('');
    first.destroy();

    // Another tab and back: the block is created again and the list is read again.
    facade.deactivate();
    loadPromo(http, facade);
    const second = render(facade);
    expect(managerList(second).value).toBe('2');
    expect(facade.selectedPromoManagerTitle()).toBe('Петров');
    expect(list(second, 'manager_orders:threat').value).toBe('');
    choose(managerList(second), '1');
    second.detectChanges();
    expect(list(second, 'manager_orders:threat').value).toBe('25');
  });
});
