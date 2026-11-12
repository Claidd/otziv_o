import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import { apiTransportSource } from './api-source.mjs';
import { loadTsModule } from './load-ts-module.mjs';

/** Some sources are checked out with CRLF; the checks below read every file with plain line feeds. */
const read = file => fs.readFileSync(file, 'utf8').replace(/\r\n/gu, '\n');

const picker = loadTsModule('src/app/shared/payment-recipient-picker.ts');
const componentSource = read('src/app/shared/mobile-payment-recipient-picker.component.ts');
const templateSource = read('src/app/shared/mobile-payment-recipient-picker.component.html');
const styleSource = read('src/app/shared/mobile-payment-recipient-picker.component.scss');
const flowSource = read('src/app/shared/mobile-payment-recipient-picker-flow.service.ts');
const helperSource = read('src/app/shared/payment-recipient-picker.ts');
const commonPageSource = read('src/app/features/common-billing.page.ts');
const orderFacadeSource = read('src/app/features/order-details/order-payment.facade.ts');
const orderPageSource = read('src/app/features/order-details.page.ts');
const apiSource = apiTransportSource();

const webPickerSource = read('../frontend/src/app/shared/payment-recipient-picker.component.ts');
const webCommonSource = read('../frontend/src/app/features/admin/common-billing/common-billing.component.ts');
const webOrderFacadeSource = read('../frontend/src/app/features/manager/manager-board-order.facade.ts');
const webApiSource = read('../frontend/src/app/core/common-billing.api.ts')
  + read('../frontend/src/app/core/manager.api.ts');
const contract = JSON.parse(fs.readFileSync('../contracts/fixtures/client-api-current.json', 'utf8'));

const person = (overrides = {}) => ({
  profileId: 1, name: 'Лидия З.', role: 'SPECIALIST', orderSpecialist: false, manager: false, teamMember: true,
  workedWithClient: false, availableKopecks: 300_000, overrunKopecks: 0, current: false, blockReason: null, ...overrides
});
const current = person({ orderSpecialist: true, current: true });
const enough = person({ profileId: 2, name: 'Вика Ц.', role: 'MANAGER', manager: true, teamMember: false });
const above = person({ profileId: 3, name: 'Анна П.', workedWithClient: true, availableKopecks: 40_000, overrunKopecks: 60_000 });
const blocked = person({ profileId: 4, name: 'Олег К.', blockReason: 'допуск к приёму оплат выключен' });
const people = [current, enough, above, blocked];

/** Values made inside the test loader belong to another realm; compare their plain copies. */
const plain = value => JSON.parse(JSON.stringify(value));
/** `'a ' + 'b'` and its template-literal forms read as one text, the way the user sees it. */
const joined = source => source.replace(/['`]\s*\+\s*['`]/gu, '');
/** The constant parts of every string literal in a piece of source, without the interpolated values. */
const wording = source => Array.from(joined(source).matchAll(/'((?:[^'\\\n]|\\.)*)'|`((?:[^`\\]|\\.)*)`/gu))
  .flatMap(match => (match[1] ?? match[2]).split(/\$\{[^}]*\}/u))
  .filter(fragment => fragment.trim().length > 2);

function block(text, startMarker, endMarker) {
  const start = text.indexOf(startMarker);
  assert.notEqual(start, -1, `missing start marker: ${startMarker}`);
  const end = text.indexOf(endMarker, start + startMarker.length);
  assert.notEqual(end, -1, `missing end marker: ${endMarker}`);
  return text.slice(start, end);
}

test('the app names how a person relates to the invoice in the words of the website', () => {
  const relations = [
    [person({ orderSpecialist: true, workedWithClient: true }), 'специалист заказа'],
    [person({ manager: true }), 'менеджер'],
    [person({ workedWithClient: true }), 'работал с этим клиентом'],
    [person(), 'сотрудник менеджера']
  ];
  for (const [option, relation] of relations) {
    assert.equal(picker.mobilePaymentRecipientRelation(option), relation);
  }
  const webRelations = wording(block(webPickerSource, 'export function paymentRecipientRelation', '\n}'));
  assert.deepEqual(webRelations.sort(), relations.map(([, relation]) => relation).sort());
});

test('every person carries the reserve and the same tag as on the website', () => {
  assert.deepEqual(people.map(option => plain(picker.mobilePaymentRecipientTag(option))), [
    { text: 'сейчас в счёте', tone: 'current' },
    { text: 'хватает', tone: 'success' },
    { text: 'сверх резерва', tone: 'warning' },
    { text: 'недоступен', tone: 'muted' }
  ]);
  assert.match(webPickerSource, /class="pick-tag">сейчас в счёте</u);
  assert.match(webPickerSource, /pick-tag--success">хватает</u);
  assert.match(webPickerSource, /pick-tag--warning">сверх резерва</u);
  assert.match(webPickerSource, /pick-tag--muted">недоступен</u);
  // The tone picks the colour of the tag; every tone has its rule in the styles of the window.
  assert.match(templateSource, /class="pick-tag" \[class\]="'pick-tag--' \+ tag\(option\)\.tone">\{\{ tag\(option\)\.text \}\}/u);
  for (const tone of ['success', 'warning', 'muted']) {
    assert.match(styleSource, new RegExp(`\\.pick-tag--${tone} \\{`, 'u'));
  }

  assert.equal(picker.mobilePaymentRecipientNote(enough), `доступно ${picker.mobilePaymentRecipientMoney(300_000)}`);
  assert.equal(picker.mobilePaymentRecipientNote(above), 'доступно 400,00 ₽, не хватает 600,00 ₽');
  // The server's reason stands under the name of a person who cannot be chosen.
  assert.equal(picker.mobilePaymentRecipientNote(blocked), 'допуск к приёму оплат выключен');
  assert.match(templateSource, /<small>\{\{ relation\(option\) \}\} · \{\{ note\(option\) \}\}<\/small>/u);
  const webNote = wording(block(webPickerSource, '  note(option: PaymentRecipientOption): string {', '\n  }'));
  for (const fragment of webNote) {
    assert.ok(picker.mobilePaymentRecipientNote(above).includes(fragment), `the app lost the website wording «${fragment}»`);
  }
});

test('the current recipient and a blocked person are shown but can never be submitted', () => {
  assert.deepEqual(people.map(option => picker.mobilePaymentRecipientSelectable(option)), [false, true, true, false]);
  assert.equal(picker.mobilePaymentRecipientChoiceAllowed(people, null), true, 'the automatic choice is always allowed');
  assert.deepEqual([1, 2, 3, 4, 99].map(profileId => picker.mobilePaymentRecipientChoiceAllowed(people, profileId)),
    [false, true, true, false, false]);
  assert.equal(picker.mobilePaymentRecipientChoiceAllowed([], null), true, 'the automatic choice needs no list');
  // The owner's card is a choice of its own: allowed only when the server offers it and it can take the invoice.
  const ownerCard = { name: 'Мия Р.', remainingKopecks: 4_500_000, current: false, blockReason: null };
  assert.equal(picker.MOBILE_OWNER_CARD_PROFILE_ID, -1);
  assert.match(webPickerSource, /export const OWNER_CARD_PROFILE_ID = -1;/u);
  assert.equal(picker.mobilePaymentRecipientChoiceAllowed(people, -1), false, 'no owner line — no owner card');
  assert.equal(picker.mobilePaymentRecipientChoiceAllowed(people, -1, ownerCard), true);
  assert.equal(picker.mobilePaymentRecipientChoiceAllowed(people, -1, { ...ownerCard, current: true }), false);
  assert.equal(picker.mobilePaymentRecipientChoiceAllowed(people, -1, { ...ownerCard, blockReason: 'лимит карты на этот месяц не вмещает счёт' }), false);
  assert.match(templateSource, /\[disabled\]="!ownerCardSelectable\(\)"/u);
  assert.match(webPickerSource, /\[disabled\]="!ownerCardSelectable\(\)"/u);

  assert.match(templateSource, /\[disabled\]="!selectable\(option\)"/u);
  assert.match(webPickerSource, /\[disabled\]="!selectable\(option\)"/u);
  const submit = block(componentSource, 'async submit(): Promise<void> {', '\n  }\n');
  assert.match(submit, /!mobilePaymentRecipientChoiceAllowed\(this\.recipients\(\), profileId, this\.ownerCard\(\)\)\) \{\s*return;/u);
  assert.ok(submit.indexOf('mobilePaymentRecipientChoiceAllowed') < submit.indexOf('this.issue('), 'the guard must stand before the request');
});

test('the automatic choice is preselected and the window says what the button does before it is pressed', () => {
  for (const source of [componentSource, webPickerSource]) {
    assert.match(source, /readonly selected = signal<number \| null>\(null\);/u);
  }
  assert.match(templateSource, /\[checked\]="selected\(\) === null" \(change\)="select\(null\)"/u);

  const outcomes = [
    picker.mobilePaymentRecipientConsequence(null),
    picker.mobilePaymentRecipientConsequence(enough),
    picker.mobilePaymentRecipientConsequence(above),
    picker.mobilePaymentRecipientConsequence(person({ name: '', overrunKopecks: 100 })),
    picker.mobilePaymentRecipientConsequence(null, { name: 'Мия Р.', remainingKopecks: 4_500_000, current: false, blockReason: null }),
    picker.mobilePaymentRecipientConsequence(null, { name: '', remainingKopecks: 4_500_000, current: false, blockReason: null })
  ];
  assert.equal(outcomes[0], 'Клиенту сразу уйдёт новый счёт с номером карты, резерв прежнего получателя снимется. '
    + 'Нажимая кнопку, вы подтверждаете, что клиент ещё не оплатил по прежним реквизитам.');
  assert.match(outcomes[1], /^Счёт будет выставлен на Вика Ц\.: клиенту сразу уйдёт новый счёт с номером карты этого сотрудника/u);
  assert.match(outcomes[2], /^Счёт будет выставлен на Анна П\. сверх резерва на 600,00 ₽\. Превышение сохранится для сверки/u);
  assert.match(outcomes[2], /система не будет сама назначать этого человека в новые счета, пока его остаток не восстановится/u);
  assert.match(outcomes[3], /на выбранного сотрудника сверх резерва на 1,00 ₽/u);
  assert.match(outcomes[4], /^Счёт будет выставлен на карту владельца \(Мия Р\.\): клиенту сразу уйдёт новый счёт с номером этой карты/u);
  assert.match(outcomes[4], /сумма займёт месячный лимит карты\. Когда клиент переведёт, поступление подтверждает владелец\./u);
  assert.match(outcomes[5], /^Счёт будет выставлен на карту владельца: клиенту/u);
  for (const outcome of outcomes) {
    // Pressing the button is the confirmation that the client has not paid yet: there is no separate one.
    assert.match(outcome, /Нажимая кнопку, вы подтверждаете, что клиент ещё не оплатил по прежним реквизитам\.$/u);
  }
  // Every sentence of the website is said by the app, and nothing else is.
  const webWording = wording(block(webPickerSource, 'readonly consequence = computed(() => {', '\n  });'));
  assert.ok(webWording.length >= 6, 'the website wording was not found');
  for (const fragment of webWording) {
    assert.ok(outcomes.some(outcome => outcome.includes(fragment)), `the app lost the website wording «${fragment}»`);
  }
  assert.deepEqual(wording(block(helperSource, 'export function mobilePaymentRecipientConsequence', '\n}')).sort(), [...webWording].sort());

  // The line and the refusal stay beside the button, with the warning look for a person above the reserve.
  const footer = block(templateSource, '<ion-footer>', '</ion-footer>');
  assert.match(footer, /class="pick-consequence" \[class\.warning\]="chosenOverrun\(\) > 0" role="status"/u);
  assert.match(footer, /\{\{ consequence\(\) \}\}/u);
  assert.ok(footer.indexOf('pick-consequence') < footer.indexOf('(click)="submit()"'));
});

test('the window speaks the same Russian as the website', () => {
  const appText = templateSource + helperSource + componentSource;
  for (const text of [
    'Выдать номер карты',
    'Сейчас в счёте: ',
    'Сумма счёта',
    'Кому клиент будет переводить?',
    'Подобрать автоматически',
    'Выбрать получателя самостоятельно',
    'Или выберите получателя сами',
    'Можно выбрать любого сотрудника, даже если резерва не хватает.',
    'Загружаем сотрудников…',
    'Повторить',
    'Сотрудников с платёжным профилем для этого счёта нет.',
    'Поиск по имени',
    'Поиск сотрудника по имени',
    'Никого не найдено. Проверьте имя.',
    'Владелец и сотрудники',
    'Карту владельца — пока её лимит на месяц вмещает счёт.',
    'карта владельца · ',
    'осталось в этом месяце ',
    'лимит вмещает',
    'Без имени',
    'Отмена',
    'Выдаём…'
  ]) {
    assert.ok(webPickerSource.includes(text), `the website no longer says «${text}»`);
    assert.ok(appText.includes(text), `the app does not say «${text}»`);
  }
  for (const source of [webCommonSource, webOrderFacadeSource]) {
    assert.ok(source.includes("'Не удалось загрузить сотрудников'"));
  }
  assert.ok(componentSource.includes("'Не удалось загрузить сотрудников'"));
  assert.ok(webOrderFacadeSource.includes("'Не удалось выдать номер карты'"));
  assert.ok(orderFacadeSource.includes("'Не удалось выдать номер карты'"));

  // How the automatic choice works is explained with the texts of the website for both starting routes.
  for (const target of ['EMPLOYEE_REQUISITES', 'DIFFERENT_RECIPIENT']) {
    const hint = picker.mobilePaymentRecipientAutoHint(target);
    for (const source of [webCommonSource, webOrderFacadeSource]) {
      assert.ok(joined(source).includes(hint), `the website explains ${target} differently`);
    }
  }
  // Where the owner's card may be issued, the order window of both clients says that it comes after the employees.
  for (const target of ['EMPLOYEE_REQUISITES', 'DIFFERENT_RECIPIENT']) {
    const hint = picker.mobilePaymentRecipientAutoHint(target, true);
    assert.match(hint, /Если не подойдёт никто из сотрудников, клиент получит карту владельца/u);
    assert.ok(joined(webOrderFacadeSource).includes(hint), `the website explains ${target} with the owner's card differently`);
  }
  assert.match(orderFacadeSource, /autoHintWithOwnerCard: mobilePaymentRecipientAutoHint\(target, true\)/u);
  assert.match(picker.mobilePaymentRecipientAutoHint('EMPLOYEE_REQUISITES'), /платёжное задание, затем специалиста заказа, затем менеджера/u);
  assert.match(picker.mobilePaymentRecipientAutoHint('DIFFERENT_RECIPIENT'), /Нынешний получатель исключается/u);

  // The search appears for the same length of the list and looks through names only.
  assert.match(webPickerSource, /\(recipients \?\? \[\]\)\.length > 6/u);
  assert.match(templateSource, /@if \(recipients\(\)\.length > 6\) \{\s*<input\s+type="search"/u);
  const many = [...people, person({ profileId: 5, name: 'ВИКТОР Н.' })];
  assert.deepEqual(Array.from(picker.mobilePaymentRecipientSearch(many, '  вик '), option => option.profileId), [2, 5]);
  assert.equal(picker.mobilePaymentRecipientSearch(many, '').length, many.length);
  assert.equal(picker.mobilePaymentRecipientSearch(many, 'менеджер').length, 0);
});

test('server rows of both recipient lists become the people of the window', () => {
  for (const name of ['CommonInvoiceRouteRecipientsResponseOutput', 'PaymentRouteRecipientsResponseOutput']) {
    const response = contract.responses[name];
    assert.ok(Array.isArray(response?.recipients) && response.recipients.length > 0, `${name} fixture has no recipients`);
    const [row] = response.recipients;
    const [option] = plain(picker.mobilePaymentRecipientOptions(response.recipients));
    assert.equal(option.profileId, row.profileId);
    for (const flag of ['orderSpecialist', 'manager', 'teamMember', 'workedWithClient', 'current']) {
      assert.equal(option[flag], row[flag], `${name}.${flag}`);
    }
    assert.equal(option.availableKopecks, row.availableKopecks);
    assert.equal(typeof response.amountKopecks, 'number');
  }
  // The contract allows empty values: a row without a payment profile cannot be named at all.
  assert.deepEqual(plain(picker.mobilePaymentRecipientOptions([
    { profileId: null, name: 'Без профиля' },
    { profileId: 7, name: null, role: null, availableKopecks: 500, overrunKopecks: -3, current: null, blockReason: '  ' }
  ])), [{
    profileId: 7, name: '', role: '', orderSpecialist: false, manager: false, teamMember: false, workedWithClient: false,
    availableKopecks: 500, overrunKopecks: 0, current: false, blockReason: null
  }]);
  assert.deepEqual(plain(picker.mobilePaymentRecipientOptions(null)), []);
});

test('both clients read the people from the same endpoints and name a person only on the plain route change', () => {
  for (const source of [apiSource, webApiSource]) {
    assert.match(source, /\/api\/common-billing\/invoices\/\$\{invoiceId\}\/payment-route-recipients`/u);
    assert.match(source, /\/api\/manager\/orders\/\$\{orderId\}\/payment-route-recipients`/u);
    // A person named by the manager is explicit; only the system's own choice of another person uses `/recipient`.
    assert.match(source, /const different = target === 'DIFFERENT_RECIPIENT' && recipientProfileId == null;/u);
    assert.match(source, /const different = request\.target === 'DIFFERENT_RECIPIENT' && request\.recipientProfileId == null;/u);
    assert.match(source, /target: target === 'DIFFERENT_RECIPIENT' \? 'EMPLOYEE_REQUISITES' : target/u);
    assert.match(source, /request\.target === 'DIFFERENT_RECIPIENT' \? \{ \.\.\.request, target: 'EMPLOYEE_REQUISITES' \} : request/u);
    assert.match(source, /\.\.\.\(recipientProfileId != null \? \{ recipientProfileId \} : \{\}\)/u);
    assert.match(source, /recipientProfileId\?: number \| null;/u);
  }
});

test('a common invoice issues a card number only from the window, as on the website', () => {
  const webRoute = block(webCommonSource, '  changeCommonInvoicePaymentRoute(targetOverride', '\n  reissueCommonInvoiceOwnerBankRoute');
  const appRoute = block(commonPageSource, '  async changePaymentRoute(targetOverride', '\n  /** The «Выдать номер карты» window');
  for (const route of [webRoute, appRoute]) {
    assert.match(route, /if \(target === 'EMPLOYEE_REQUISITES' \|\| target === 'DIFFERENT_RECIPIENT'\) \{/u);
  }
  // The window replaces the confirmation: the branch leaves before the dialog of the bank link is asked.
  const windowBranch = block(appRoute, "if (target === 'EMPLOYEE_REQUISITES' || target === 'DIFFERENT_RECIPIENT') {", '\n      }');
  assert.match(windowBranch, /await this\.issueCardNumber\(ticket, invoiceId, invoice, target, context\);\s*return;/u);
  assert.doesNotMatch(windowBranch, /confirm/u);
  assert.ok(appRoute.indexOf('this.issueCardNumber(') < appRoute.indexOf('this.confirm.confirm('));
  assert.match(appRoute, /Переключить общий счёт на банковскую ссылку владельца\?/u);

  const appWindow = block(commonPageSource, '  private async issueCardNumber(', '\n  private closeRecipientPicker');
  assert.doesNotMatch(appWindow, /this\.confirm\b/u);
  assert.match(appWindow, /this\.recipientPickerFlow\.open\(\{/u);
  assert.match(appWindow, /heading: `Общий счёт №\$\{invoiceId\}`/u);
  assert.match(webCommonSource, /amountKopecks: Math\.max\(0, \(invoice\.amountKopecks \?\? 0\) - \(invoice\.paidKopecks \?\? 0\)\)/u);
  assert.match(appWindow, /amountKopecks: Math\.max\(0, \(invoice\.amountKopecks \?\? 0\) - \(invoice\.paidKopecks \?\? 0\)\)/u);
  assert.match(appWindow, /getCommonInvoicePaymentRouteRecipients\(invoiceId\)/u);
  assert.match(appWindow, /changeCommonInvoicePaymentRoute\(\s*invoiceId,\s*target,\s*paymentEvidenceToken,\s*undefined,\s*choice\.profileId\s*\)/u);
  assert.match(webCommonSource, /changeCommonInvoicePaymentRoute\(\s*picker\.invoiceId,\s*picker\.target,\s*picker\.token,\s*null,\s*choice\.profileId\s*\)/u);

  // Nothing is sent, shown or changed for a screen that was left while the window was open.
  const submit = block(appWindow, '  private async submitRecipientChoice(', '\n  }\n');
  assert.equal(submit.match(/if \(!this\.routeGuard\.accepts\(ticket\)\) \{\s*return \{ outcome: \{ status: 'left' \} \};/gu)?.length, 3);
  assert.ok(submit.indexOf("status: 'left'") < submit.indexOf('changeCommonInvoicePaymentRoute('));
  // HTTP 423: the request is saved and finishes after the bank closes the old link; the window closes.
  assert.match(webCommonSource, /if \(status === 423\) \{[\s\S]*?this\.closeRecipientPicker\(\);/u);
  assert.match(submit, /error instanceof HttpErrorResponse && error\.status === 423\) \{[\s\S]*?status: 'accepted'/u);
  // Any other refusal stays in the window.
  assert.match(webCommonSource, /this\.recipientPickerSubmitError\.set\(message\);/u);
  assert.match(submit, /return \{ outcome: \{ status: 'refused', message: this\.errorMessage\(error, 'Не удалось выдать номер карты\.'\) \} \};/u);
  assert.doesNotMatch(submit, /this\.error\.set\(this\.errorMessage/u);

  for (const source of [webCommonSource, appWindow]) {
    assert.match(source, /Получатель сменится, когда банк подтвердит закрытие прежней ссылки\./u);
  }
  assert.match(commonPageSource, /ngOnDestroy\(\): void \{\s*this\.closeRecipientPicker\(\);/u);
  assert.match(commonPageSource, /private closeRecipientPicker\(\): void \{\s*this\.recipientPickerLeave\?\.abort\(\);/u);
  assert.match(commonPageSource, /<p class="invoice-action-hint">\{\{ cardNumberRouteHint\(invoice\.paymentRouteType\) \}\}<\/p>/u);
});

test('an order issues a card number from the window only for an issued invoice without a paper document', () => {
  // The website and the app decide by the same rule; a first invoice and a withdrawn paper invoice keep the old flow.
  assert.match(webOrderFacadeSource,
    /\(target === 'EMPLOYEE_REQUISITES' \|\| target === 'DIFFERENT_RECIPIENT'\)\s*&& context\.paymentLinkId != null && !context\.paperInvoiceIssued/u);
  const rule = block(orderFacadeSource, '  cardNumberOpensWindow(', '\n  }');
  assert.match(rule,
    /\(target === 'EMPLOYEE_REQUISITES' \|\| target === 'DIFFERENT_RECIPIENT'\)\s*&& context\.paymentLinkId != null && !context\.paperInvoiceIssued/u);

  // The website never disables the button; the app keeps «already set» only where no window opens.
  assert.match(block(orderFacadeSource, '  paymentRouteTargetActive(', '\n  }'),
    /context\.configuredMode === target && !this\.cardNumberOpensWindow\(target, context\)/u);

  const route = block(orderFacadeSource, '  async changePaymentRoute(target', '\n  /** The «Выдать номер карты» window');
  assert.match(route, /if \(this\.cardNumberOpensWindow\(target, context\)\) \{\s*await this\.issueCardNumber\(orderId, target, context, ticket, route\);\s*return;\s*\}/u);
  assert.ok(route.indexOf('this.issueCardNumber(') < route.indexOf('this.deps.confirm('), 'the window must replace the confirmation');
  assert.ok(route.indexOf('this.pendingRoutes.add(orderId)') < route.indexOf('this.issueCardNumber('), 'the order stays reserved while the window is open');
  // The old flow is untouched: the paper warning, the explicit unpaid confirmation and the bank profile.
  assert.match(route, /context\.paperInvoiceIssued \? \{ paperInvoiceWithdrawn: true \} : \{\}/u);
  assert.match(route, /target === 'OWNER_TBANK' \? context\.expectedTargetPaymentProfileId \?\? null : null/u);

  const windowFlow = block(orderFacadeSource, '  private async issueCardNumber(', '\n  async markPaperInvoiceIssued');
  assert.doesNotMatch(windowFlow, /deps\.confirm/u);
  assert.match(windowFlow, /heading: `Заказ №\$\{orderId\}`/u);
  assert.match(windowFlow, /this\.session\.read\(read, this\.deps\.api\.getManagerOrderPaymentRouteRecipients\(orderId\)\)/u);
  const submit = block(windowFlow, '  private async submitRecipientChoice(', '\n  }\n');
  assert.match(submit, /this\.deps\.writes\.track\(this\.deps\.api\.changeManagerOrderPaymentRoute\(orderId, \{/u);
  assert.match(submit, /expectedPaymentLinkId: paymentLinkId, target, confirmedUnpaid: true, expectedTargetPaymentProfileId: null,/u);
  assert.match(submit, /\.\.\.\(choice\.profileId != null \? \{ recipientProfileId: choice\.profileId \} : \{\}\)/u);
  assert.match(webOrderFacadeSource, /\.\.\.\(choice\.profileId != null \? \{ recipientProfileId: choice\.profileId \} : \{\}\)/u);
  assert.equal(submit.match(/if \(!this\.accepts\(ticket, route\)\) return \{ status: 'left' \};/gu)?.length, 3);
  assert.ok(submit.indexOf("status: 'left'") < submit.indexOf('changeManagerOrderPaymentRoute('));
  assert.match(webOrderFacadeSource, /if \(err\?\.status === 423\) \{[\s\S]*?this\.closeRecipientPicker\(\);/u);
  assert.match(submit, /\?\.status === 423\) \{[\s\S]*?return \{ status: 'accepted'/u);
  assert.match(submit, /return \{ status: 'refused', message: this\.deps\.errorMessage\(error, 'Не удалось выдать номер карты'\) \};/u);
  assert.doesNotMatch(submit, /this\.deps\.error\.set\(message|this\.deps\.error\.set\(this\.deps\.errorMessage/u);
  for (const source of [webOrderFacadeSource, windowFlow]) {
    assert.match(source, /Клиенту отправляется новый счёт с номером карты по заказу #\$\{(?:picker\.)?orderId\}/u);
  }
  // Leaving the order closes the window before anything else is reset.
  assert.match(orderFacadeSource, /deactivate\(\): void \{\s*this\.recipientPickerLeave\?\.abort\(\);/u);
  assert.match(orderPageSource, /pickRecipient: request => this\.recipientPickerFlow\.open\(request\)/u);
  assert.match(orderPageSource, /<small>\{\{ cardNumberHint\(context\) \}\}<\/small>/u);
});

test('the window follows the dialog conventions of the app and closes only with an answer', () => {
  for (const tag of ['<ion-header>', '<ion-content>', '<ion-footer>', '<ion-title>Выдать номер карты</ion-title>']) {
    assert.ok(templateSource.includes(tag), `${tag} is missing`);
  }
  assert.match(componentSource, /changeDetection: ChangeDetectionStrategy\.Eager,\s*selector: 'app-mobile-payment-recipient-picker'/u);
  assert.match(componentSource, /templateUrl: '\.\/mobile-payment-recipient-picker\.component\.html'/u);
  assert.match(flowSource, /component: MobilePaymentRecipientPickerComponent,\s*componentProps,\s*backdropDismiss: false/u);
  assert.match(flowSource, /htmlAttributes: \{ 'aria-label': 'Выдать номер карты' \}/u);

  // One group of radio buttons, a real fieldset with a legend, announced errors and status.
  // The automatic choice, the owner's card and a person of the list.
  assert.equal(templateSource.match(/type="radio"\s+name="route-recipient"/gu)?.length, 3);
  assert.match(templateSource, /<legend>Кому клиент будет переводить\?<\/legend>/u);
  assert.equal(templateSource.match(/role="alert"/gu)?.length, 2);
  assert.match(templateSource, /@if \(submitError\(\)\) \{\s*<p class="pick-error" role="alert">\{\{ submitError\(\) \}\}<\/p>/u);
  assert.match(templateSource, /aria-label="Поиск сотрудника по имени"/u);
  assert.equal(templateSource.match(/\[disabled\]="saving\(\)"/gu)?.length, 7, 'every control is locked while the request runs');
  assert.match(styleSource, /:host-context\(body\.otziv-dark-theme\) \{/u);
  assert.match(styleSource, /\.pick-option:focus-within \{\s*outline: 2px solid/u);
  // Ionic lays the header, the scrolling list and the footer out on the host: its display must stay untouched.
  assert.doesNotMatch(block(styleSource, ':host {', '\n}'), /\bdisplay:/u);
  // The search keyboard gets the room of the footer; the button returns with the line about what it does.
  assert.match(styleSource, /:host-context\(body\.otziv-keyboard-open\) ion-footer \{\s*display: none;/u);

  // A refusal keeps the window; only an issued number, an accepted request or a left page close it.
  const submit = block(componentSource, 'async submit(): Promise<void> {', '\n  }\n');
  assert.match(submit, /if \(outcome\.status === 'refused'\) \{[\s\S]*?this\.submitError\.set\(outcome\.message\);\s*return;\s*\}/u);
  assert.ok(submit.indexOf("outcome.status === 'refused'") < submit.indexOf('this.modalController.dismiss('));
  assert.match(submit, /dismiss\(outcome, outcome\.status === 'left' \? 'cancel' : 'issued'\)/u);
  assert.match(flowSource, /result\.role === 'issued' \? result\.data : null/u);
  assert.match(componentSource, /close\(\): void \{\s*if \(this\.saving\(\) \|\| this\.closed\) \{\s*return;/u);
  // The page that opened the window can close it; the window never outlives a left screen.
  assert.match(flowSource, /signal\?\.addEventListener\('abort', leave, \{ once: true \}\)/u);
});
