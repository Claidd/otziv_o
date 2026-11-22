import { Subject } from 'rxjs';
import type { MockInstance } from 'vitest';
import type { AdminClientMessageSettingsApi } from '../../../core/admin-client-message-settings.api';
import type {
  AdminClientMessageSettings,
  ClientMessageSettingsRequest,
} from '../../../core/admin-dictionaries.api';
import {
  AUTO_IGNORE_PHRASE_MAX_LENGTH,
  AdminClientMessageSettingsFacade,
  phraseCountLabel,
} from './admin-client-message-settings.facade';
import { messageSettingsWriteFixture } from './message-settings-request.fixture';

const saved = (patch: Partial<AdminClientMessageSettings> = {}): AdminClientMessageSettings =>
  ({ ...messageSettingsWriteFixture, ...patch }) as AdminClientMessageSettings;

function answer(reply: Subject<AdminClientMessageSettings>, value: AdminClientMessageSettings): void {
  reply.next(value);
  reply.complete();
}

function setup() {
  const reads: Subject<AdminClientMessageSettings>[] = [];
  const writes: { body: ClientMessageSettingsRequest; reply: Subject<AdminClientMessageSettings> }[] = [];
  const api = {
    getClientMessageSettings: vi.fn(() => {
      const reply = new Subject<AdminClientMessageSettings>();
      reads.push(reply);
      return reply.asObservable();
    }),
    updateClientMessageSettings: vi.fn((body: ClientMessageSettingsRequest) => {
      const reply = new Subject<AdminClientMessageSettings>();
      writes.push({ body, reply });
      return reply.asObservable();
    }),
  };
  const toast = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
  const facade = new AdminClientMessageSettingsFacade({
    api: api as unknown as AdminClientMessageSettingsApi,
    toast,
    isActive: () => true,
    onSettingsChanged: vi.fn(),
  });
  return { facade, form: facade.autoresponderForm, toast, reads, writes };
}

/** The tab opens as setTab does it: reset, then read. */
function openTab() {
  const page = setup();
  page.facade.resetAutoresponderForm();
  page.facade.load();
  return page;
}

function lastError(toast: { error: { mock: { calls: unknown[][] } } }): [string, string] {
  return toast.error.mock.calls.at(-1) as [string, string];
}

describe('autoresponder and «Фразы без ответа» settings fixes', () => {
  let confirm: MockInstance<(message?: string) => boolean>;
  beforeEach(() => {
    confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
  });
  afterEach(() => {
    vi.restoreAllMocks();
    document.body.innerHTML = '';
  });

  it('keeps the form locked and saving refused until the first read answers', () => {
    const { facade, form, reads, writes } = openTab();
    expect(form.disabled).toBe(true);
    facade.saveAutoresponderSettings();
    expect(writes).toHaveLength(0);
    expect(facade.error()).toBe('Настройки ещё не загрузились. Дождитесь загрузки и сохраните снова.');

    reads[0].error({ status: 0 });
    facade.saveAutoresponderSettings();
    expect(writes).toHaveLength(0);
    expect(form.disabled).toBe(true);

    facade.load();
    answer(reads[1], saved());
    expect(form.enabled).toBe(true);
    facade.saveAutoresponderSettings();
    expect(writes).toHaveLength(1);
    expect(writes[0].body).toEqual(messageSettingsWriteFixture);
    expect(confirm).not.toHaveBeenCalled();
  });

  it('a field changed before the first answer keeps its value, every other field takes the server value', () => {
    const { facade, form, reads, writes } = openTab();
    form.controls.dailyLimit.setValue(321);
    answer(
      reads[0],
      saved({ clientTextReminderIntervalDays: 5, reviewReminderText: 'Серверный текст', unansweredAutoIgnorePhrases: 'ок,взаимно' }),
    );
    expect(form.controls.dailyLimit.value).toBe(321);
    expect(form.controls.clientTextReminderIntervalDays.value).toBe(5);
    expect(form.controls.reviewReminderText.value).toBe('Серверный текст');

    facade.saveAutoresponderSettings();
    expect(writes[0].body).toEqual({
      ...messageSettingsWriteFixture,
      dailyLimit: 321,
      clientTextReminderIntervalDays: 5,
      reviewReminderText: 'Серверный текст',
      unansweredAutoIgnorePhrases: 'ок,взаимно',
    });
  });

  it('a later read refreshes untouched fields and keeps the edited ones', () => {
    const { facade, form, reads } = openTab();
    answer(reads[0], saved());
    form.controls.paymentReminderText.setValue('Мой текст');
    form.controls.paymentReminderText.markAsDirty();
    facade.load();
    answer(reads[1], saved({ dailyLimit: 99, paymentReminderText: 'Серверный текст' }));
    expect(form.controls.paymentReminderText.value).toBe('Мой текст');
    expect(form.controls.dailyLimit.value).toBe(99);
    expect(form.dirty).toBe(true);
    expect(facade.clientMessageSettings()?.paymentReminderText).toBe('Серверный текст');

    form.controls.paymentReminderText.setValue('Серверный текст');
    facade.load();
    answer(reads[2], saved({ dailyLimit: 77, paymentReminderText: 'Серверный текст' }));
    expect(form.controls.dailyLimit.value).toBe(77);
    expect(form.pristine).toBe(true);
  });

  it('the monitor switch saved on its own tab is not taken for an unsaved edit', () => {
    const { facade, form, reads } = openTab();
    answer(reads[0], saved({ monitorEnabled: false }));
    facade.patchClientMessageMonitorEnabled(true);
    facade.load();
    answer(reads[1], saved({ monitorEnabled: false }));
    expect(form.controls.monitorEnabled.value).toBe(false);
  });

  it('sends the version the form was loaded with and, on a stale form, reads the fresh values without claiming a partial save', () => {
    const { facade, form, reads, writes, toast } = openTab();
    answer(reads[0], saved({ liveEnabled: true, dailyLimit: 100, settingsVersion: 'v1' } as Partial<AdminClientMessageSettings>));
    form.controls.dailyLimit.setValue(150);
    form.controls.dailyLimit.markAsDirty();
    facade.saveAutoresponderSettings();
    expect(writes).toHaveLength(1);
    expect(writes[0].body.expectedSettingsVersion).toBe('v1');

    // Another administrator stopped sending meanwhile: the server refuses the stale form with 409.
    writes[0].reply.error({ status: 409, error: { message: 'Настройки автоответчика изменились после открытия страницы.' } });
    expect(lastError(toast)[1]).not.toContain('Часть изменений могла сохраниться');
    expect(reads).toHaveLength(2);
    answer(reads[1], saved({ liveEnabled: false, dailyLimit: 100, settingsVersion: 'v2' } as Partial<AdminClientMessageSettings>));
    // The typed limit stays, the untouched switch now shows the stop, and the next save carries the new version.
    expect(form.controls.dailyLimit.value).toBe(150);
    expect(form.controls.liveEnabled.value).toBe(false);
    facade.saveAutoresponderSettings();
    expect(writes[1].body.expectedSettingsVersion).toBe('v2');
    expect(writes[1].body.liveEnabled).toBe(false);
  });

  it('asks before turning sending back on and sends nothing on «Отмена»', () => {
    const { facade, form, reads, writes } = openTab();
    answer(reads[0], saved({ workerEnabled: true, liveEnabled: false }));
    confirm.mockReturnValue(false);
    form.controls.liveEnabled.setValue(true);
    facade.saveAutoresponderSettings();
    expect(String(confirm.mock.calls[0][0]).startsWith('Включить отправку клиентам?')).toBe(true);
    expect(writes).toHaveLength(0);
    confirm.mockReturnValue(true);
    facade.saveAutoresponderSettings();
    expect(writes).toHaveLength(1);
    expect(writes[0].body.liveEnabled).toBe(true);
  });

  it('asks before turning the queue or sending off, only on a change from the saved «on»', () => {
    const { facade, form, reads, writes } = openTab();
    answer(reads[0], saved({ workerEnabled: true, liveEnabled: true }));
    confirm.mockReturnValue(false);
    form.controls.liveEnabled.setValue(false);
    facade.saveAutoresponderSettings();
    expect(writes).toHaveLength(0);
    const sendingText = String(confirm.mock.calls[0][0]);
    expect(sendingText.startsWith('Выключить отправку клиентам?')).toBe(true);
    expect(sendingText).toContain('общие счета (и ручные отправки тоже)');
    expect(sendingText).toContain('Новые маршруты оплаты на специалистов выдаваться не будут');

    form.controls.workerEnabled.setValue(false);
    facade.saveAutoresponderSettings();
    expect(String(confirm.mock.calls[1][0]).startsWith('Выключить фоновую очередь и отправку клиентам?')).toBe(true);
    expect(writes).toHaveLength(0);

    confirm.mockReturnValue(true);
    facade.saveAutoresponderSettings();
    expect(writes).toHaveLength(1);
    expect(writes[0].body.workerEnabled).toBe(false);
    expect(writes[0].body.liveEnabled).toBe(false);
    answer(writes[0].reply, saved({ workerEnabled: false, liveEnabled: false }));

    facade.saveAutoresponderSettings();
    expect(confirm).toHaveBeenCalledTimes(3);
    expect(writes).toHaveLength(2);
  });

  it('names only the queue when only the background queue is turned off', () => {
    const { facade, form, reads, writes } = openTab();
    answer(reads[0], saved({ workerEnabled: true, liveEnabled: true }));
    confirm.mockReturnValue(false);
    form.controls.workerEnabled.setValue(false);
    facade.saveAutoresponderSettings();
    const question = String(confirm.mock.calls[0][0]);
    expect(question.startsWith('Выключить фоновую очередь?')).toBe(true);
    expect(question).toContain('Остановятся напоминания');
    expect(question).not.toContain('Клиентам перестанут уходить');
    expect(writes).toHaveLength(0);
  });

  it('refuses a phrase longer than the server allows, on adding and on editing', () => {
    const { facade, reads, toast } = openTab();
    answer(reads[0], saved({ unansweredAutoIgnorePhrases: 'ок,да' }));
    const tooLong = 'х'.repeat(AUTO_IGNORE_PHRASE_MAX_LENGTH + 1);
    facade.updateAutoIgnorePhraseDraft(tooLong);
    facade.addAutoIgnorePhrase();
    expect(facade.autoIgnorePhrases()).toEqual(['ок', 'да']);
    expect(facade.autoIgnorePhraseDraft()).toBe(tooLong);
    expect(toast.info).toHaveBeenCalledWith('Фраза слишком длинная', expect.stringContaining('80 символов'));

    facade.updateAutoIgnorePhraseDraft('х'.repeat(AUTO_IGNORE_PHRASE_MAX_LENGTH));
    facade.addAutoIgnorePhrase();
    expect(facade.autoIgnorePhrases()).toHaveLength(3);

    facade.startEditAutoIgnorePhrase(0, 'ок');
    facade.updateEditingAutoIgnorePhraseValue(tooLong);
    facade.saveAutoIgnorePhraseEdit(0);
    expect(facade.autoIgnorePhrases()[0]).toBe('ок');
    expect(facade.editingAutoIgnorePhraseIndex()).toBe(0);
  });

  it('refuses to save a stored phrase that is too long and names it', () => {
    const { facade, reads, writes, toast } = openTab();
    answer(reads[0], saved({ unansweredAutoIgnorePhrases: `ок,${'длинная фраза '.repeat(7)}` }));
    facade.savePhraseDictionary();
    expect(writes).toHaveLength(0);
    const [title, message] = lastError(toast);
    expect(title).toBe('Фразы не сохранены');
    expect(message).toContain('«Фразы без ответа»: фраза «длинная фраза');
    expect(message).toContain('длиннее 80 символов.');
  });

  it('treats text of spaces as empty and names every wrong field with its limit', () => {
    const { facade, form, reads, writes, toast } = openTab();
    answer(reads[0], saved());
    form.controls.reviewReminderText.setValue('   ');
    form.controls.unansweredFastClickWarningCount.setValue(2);
    form.controls.dailyLimit.setValue(null as unknown as number);
    facade.saveAutoresponderSettings();
    expect(writes).toHaveLength(0);
    const [title, message] = lastError(toast);
    expect(title).toBe('Автоответчик не сохранен');
    expect(message).toContain('«Первый порог: закрытий»: не меньше 3.');
    expect(message).toContain('«Дневной лимит сообщений»: укажите число.');
    expect(message).toContain('«Проверка отзывов»: заполните поле.');
    expect(form.controls.reviewReminderText.touched).toBe(true);
  });

  it('lists wrong fields in page order, opens the folded block of the first one and puts the cursor there', () => {
    document.body.innerHTML = `
      <label><span>Проверка отзывов (на странице)</span><textarea formcontrolname="reviewReminderText"></textarea></label>
      <details class="scenario-panel"><summary>Расписание</summary>
        <label><span>Дневной лимит сообщений</span><input type="number" formcontrolname="dailyLimit"></label>
      </details>`;
    const { facade, form, reads, toast } = openTab();
    answer(reads[0], saved());
    form.controls.dailyLimit.setValue(6000);
    form.controls.reviewReminderText.setValue('');
    facade.saveAutoresponderSettings();
    const message = lastError(toast)[1];
    expect(message.indexOf('Проверка отзывов (на странице)')).toBeLessThan(message.indexOf('Дневной лимит сообщений'));
    expect(message).toContain('не больше 5000');
    expect(document.activeElement?.getAttribute('formcontrolname')).toBe('reviewReminderText');

    form.controls.reviewReminderText.setValue('Текст');
    facade.saveAutoresponderSettings();
    expect(document.querySelector('details')?.open).toBe(true);
    expect(document.activeElement?.getAttribute('formcontrolname')).toBe('dailyLimit');
    const lines = lastError(toast)[1];
    expect(lines).toBe('«Дневной лимит сообщений»: не больше 5000.');
  });

  it('does not save while a typed phrase is not added or a phrase edit is open', () => {
    const { facade, reads, writes, toast } = openTab();
    answer(reads[0], saved({ unansweredAutoIgnorePhrases: 'ок,да' }));
    facade.updateAutoIgnorePhraseDraft('  принято ');
    facade.saveAutoresponderSettings();
    expect(writes).toHaveLength(0);
    expect(toast.error).toHaveBeenCalledWith('Фраза не добавлена', expect.stringContaining('«принято»'));

    facade.updateAutoIgnorePhraseDraft('');
    facade.startEditAutoIgnorePhrase(1, 'да');
    facade.saveAutoresponderSettings();
    expect(writes).toHaveLength(0);
    expect(lastError(toast)[0]).toBe('Правка фразы не применена');

    facade.cancelAutoIgnorePhraseEdit();
    facade.saveAutoresponderSettings();
    expect(writes).toHaveLength(1);
  });

  it('after a refused save reads the settings again, keeps typed values and warns that part may be saved', () => {
    const { facade, form, reads, writes, toast } = openTab();
    answer(reads[0], saved());
    form.controls.dailyLimit.setValue(500);
    form.controls.dailyLimit.markAsDirty();
    facade.saveAutoresponderSettings();
    writes[0].reply.error({ status: 400, error: { message: 'Укажите текст оплаты' } });
    expect(facade.saving()).toBe(false);
    expect(facade.error()).toBe('Укажите текст оплаты. Часть изменений могла сохраниться: сверьте значения.');
    expect(lastError(toast)).toEqual([
      'Автоответчик не сохранен',
      'Укажите текст оплаты. Часть изменений могла сохраниться: сверьте значения.',
    ]);
    expect(reads).toHaveLength(2);

    answer(reads[1], saved({ dailyLimit: 500, workerEnabled: false }));
    expect(facade.clientMessageSettings()?.workerEnabled).toBe(false);
    expect(form.controls.workerEnabled.value).toBe(false);
    expect(form.controls.dailyLimit.value).toBe(500);
    expect(form.dirty).toBe(true);
    expect(facade.error()).toContain('Часть изменений могла сохраниться');

    facade.saveAutoresponderSettings();
    writes[1].reply.error({ status: 500 });
    reads[2].error({ status: 0 });
    expect(facade.error()).toContain('Часть изменений могла сохраниться');
    expect(toast.error).toHaveBeenCalledTimes(2);
  });

  it('an open phrase edit follows its phrase when a phrase above it is removed', () => {
    const { facade, reads } = openTab();
    answer(reads[0], saved({ unansweredAutoIgnorePhrases: 'ок,да,нет' }));
    facade.startEditAutoIgnorePhrase(2, 'нет');
    facade.updateEditingAutoIgnorePhraseValue('нет уж');
    facade.removeAutoIgnorePhrase(0);
    expect(facade.editingAutoIgnorePhraseIndex()).toBe(1);
    facade.saveAutoIgnorePhraseEdit(2);
    expect(facade.autoIgnorePhrases()).toEqual(['да', 'нет уж']);
    expect(facade.editingAutoIgnorePhraseIndex()).toBeNull();

    facade.startEditAutoIgnorePhrase(0, 'да');
    facade.removeAutoIgnorePhrase(0);
    expect(facade.editingAutoIgnorePhraseIndex()).toBeNull();
    expect(facade.autoIgnorePhrases()).toEqual(['нет уж']);
  });

  it('phrase changes wait for the first read instead of editing the interface defaults', () => {
    const { facade, form, reads, toast } = openTab();
    facade.updateAutoIgnorePhraseDraft('принято');
    facade.addAutoIgnorePhrase();
    facade.removeAutoIgnorePhrase(0);
    expect(toast.info).toHaveBeenCalledWith('Список ещё загружается', 'Дождитесь загрузки и повторите.');
    expect(form.controls.unansweredAutoIgnorePhrases.dirty).toBe(false);

    answer(reads[0], saved({ unansweredAutoIgnorePhrases: 'ок,взаимно' }));
    expect(facade.autoIgnorePhrases()).toEqual(['ок', 'взаимно']);
    facade.addAutoIgnorePhrase();
    expect(facade.autoIgnorePhrases()).toEqual(['ок', 'взаимно', 'принято']);
  });

  it('names the phrase tab in its notices and counts phrases in words', () => {
    expect([0, 1, 2, 4, 5, 11, 14, 21, 22, 104, 112].map(phraseCountLabel)).toEqual([
      '0 фраз', '1 фраза', '2 фразы', '4 фразы', '5 фраз', '11 фраз', '14 фраз', '21 фраза', '22 фразы',
      '104 фразы', '112 фраз',
    ]);
    const { facade, reads, writes, toast } = openTab();
    answer(reads[0], saved({ unansweredAutoIgnorePhrases: 'ок,да' }));
    facade.savePhraseDictionary();
    answer(writes[0].reply, saved({ unansweredAutoIgnorePhrases: 'ок,да,нет' }));
    expect(toast.success).toHaveBeenCalledWith('Фразы сохранены', '3 фразы');

    facade.savePhraseDictionary();
    writes[1].reply.error({ status: 500 });
    expect(lastError(toast)[0]).toBe('Фразы не сохранены');
  });

  it('interface defaults match the server defaults', () => {
    const { facade, form } = setup();
    facade.resetAutoresponderForm();
    expect(form.controls.clientTextReminderIntervalDays.value).toBe(2);
    expect(facade.autoIgnorePhrases()).toEqual(expect.arrayContaining(['вам спасибо', 'и вам спасибо', 'взаимно']));
  });
});
