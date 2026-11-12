// Screenshots for the two manuals of the site: the common invoice side panel and the order's
// «Способ оплаты» block. Run against the local stand with the payment sandbox on (see README.md):
//   node scenes.mjs <light|dark|both> [scene ...]
// Stand-specific ids and the real→invented name rules come from the file named by
// OTZIV_GUIDE_SHOTS_LOCAL. It holds real names of the stand copy and is never committed.
import { readFileSync } from 'node:fs';
import { Studio, sleep } from './lib.mjs';

if (!process.env.OTZIV_GUIDE_SHOTS_LOCAL) {
  throw new Error('Set OTZIV_GUIDE_SHOTS_LOCAL to your copy of local.example.json (keep it outside the repository).');
}
const local = JSON.parse(readFileSync(process.env.OTZIV_GUIDE_SHOTS_LOCAL, 'utf8'));
const [, , theme = 'light', ...wanted] = process.argv;

const PANEL = '.invoice-side';
const ROUTE = 'Смена получателя и способа оплаты';
const MORE = 'Дополнительно';
const invoiceUrl = id => `/admin/common-billing?invoiceId=${id}`;
const boardUrl = order => `/orders?status=${encodeURIComponent('Выставлен счет')}&keyword=${order}&pageNumber=0&pageSize=10&sortDirection=desc`;

// The order line of a returned order is shown by its number, as in the confirmation text below it.
const RETURN_ROW_TITLES = `(() => {
  for (const row of document.querySelectorAll('.ret-modal .ret-row')) {
    const id = (row.querySelector('[aria-label]')?.getAttribute('aria-label') ?? '').match(/№(\d+)/)?.[1];
    const small = row.querySelector('.ret-text small');
    if (small && id) small.textContent = 'Заказ №' + id;
  }
  return true;
})()`;

const studio = await Studio.start({ theme, height: 3200 });
studio.rules = local.rules;
const page = studio.page;

const foldAll = () => studio.eval(`document.querySelectorAll('.common-billing-right-panel details').forEach(d => { d.open = false; }); true`);
const setOpen = (selector, open = true) => studio.eval(`(() => { const d = document.querySelector(${JSON.stringify(selector)}); if (!d) return false; d.open = ${open}; return true; })()`);
const exists = selector => studio.eval(`!!document.querySelector(${JSON.stringify(selector)})`);
const waitGone = selector => page.waitFor(`!document.querySelector(${JSON.stringify(selector)})`, 20000);
const waitFor = selector => page.waitFor(`!!document.querySelector(${JSON.stringify(selector)})`, 20000);

const scenes = {
  // ---- common invoice: what is where ----
  async panel() {
    await studio.open(invoiceUrl(local.invoices.issued));
    await studio.snap('ci-panel-top', {
      clip: { union: ['.side-head', { block: ROUTE }], frame: PANEL, fromTop: true, below: 12 },
      marks: [
        { target: '.side-guide-button', n: 1, side: 'right' },
        { target: '.invoice-summary', n: 2, side: 'left', at: 0.3 },
        { target: '.side-stage', n: 3, side: 'left' },
        { target: { block: 'Оплата' }, n: 4, side: 'left', at: 0.25 },
        { target: { block: ROUTE }, n: 5, side: 'left', at: 0.1 },
        { target: { block: 'Оплата', sub: '.side-help' }, n: 6, side: 'right', rx: 16 }
      ]
    });
    await foldAll();
    await sleep(400);
    await studio.snap('ci-panel-folded', {
      clip: { union: [{ block: 'Отправка клиенту' }, '.side-history'], frame: PANEL, above: 10, below: 14 },
      marks: [
        { target: { block: 'Отправка клиенту', sub: '.side-fold-icon' }, n: 1, side: 'right', rx: 14 },
        { target: { block: 'Отправка клиенту', sub: '.side-fold-state' }, n: 2, side: 'left', rx: 12 }
      ]
    });
  },

  // ---- common invoice: mark paid ----
  async pay() {
    await studio.open(invoiceUrl(local.invoices.issued));
    await studio.snap('ci-pay-button', {
      clip: { block: 'Оплата' },
      marks: [{ target: { block: 'Оплата', find: ['.side-action', 'Оплачен'] }, n: 1, side: 'left' }]
    });
    await studio.click({ block: 'Оплата', find: ['.side-action', 'Оплачен'] }, 600);
    await waitFor('.pay-modal .pay-recipient');
    await sleep(500);
    await studio.snap('ci-pay-window', {
      clip: '.pay-modal',
      marks: [
        { target: '.pay-modal .pay-recipient.selected', n: 2, side: 'left' },
        { target: '.pay-modal .pay-add', n: 3, side: 'left' },
        { target: '.pay-modal .pay-button--primary', n: 4, side: 'right' }
      ]
    });
    await studio.click({ find: ['.pay-modal .pay-button', 'Отмена'] }, 500);
    await waitGone('.pay-modal');
    // «Оплачен переводом» of an invoice by the owner's bank link: nobody is chosen, the window explains what
    // to do when the client transferred money past the link. The invoice belongs to a manager whose owner
    // may take card transfers, so «Владелец» is in the list.
    if (local.invoices.bankLink) {
      await studio.open(invoiceUrl(local.invoices.bankLink));
      await studio.click({ block: 'Оплата', find: ['.side-action', 'Оплачен'] }, 600);
      await waitFor('.pay-modal .pay-warning');
      await sleep(600);
      await studio.snap('ci-pay-bank-window', {
        clip: '.pay-modal',
        marks: [
          { target: '.pay-modal .pay-warning', n: 1, side: 'left' },
          { target: { all: '.pay-modal .pay-recipient', nth: 0 }, n: 2, side: 'left' },
          { target: '.pay-modal .pay-add', n: 3, side: 'left' }
        ]
      });
      await studio.click({ find: ['.pay-modal .pay-button', 'Отмена'] }, 500);
      await waitGone('.pay-modal');
    }
  },

  // ---- common invoice: another recipient, a card number ----
  async route() {
    await studio.open(invoiceUrl(local.invoices.issued));
    await studio.snap('ci-route-block', {
      clip: { block: ROUTE },
      marks: [
        { target: { block: ROUTE, sub: '.route-current' }, n: 1, side: 'left' },
        { target: { block: ROUTE, find: ['.side-action', 'Выдать номер карты'] }, n: 2, side: 'left' }
      ]
    });
    await studio.snap('ci-route-bank', {
      clip: { block: ROUTE },
      marks: [{ target: { block: ROUTE, find: ['.side-action', 'Выдать ссылку банка'] }, n: 1, side: 'left' }]
    });
    await studio.click({ block: ROUTE, find: ['.side-action', 'Выдать номер карты'] }, 600);
    await waitFor('.pick-modal .pick-list--people .pick-option');
    await sleep(600);
    await studio.snap('ci-route-window', {
      clip: '.pick-modal',
      marks: [
        { target: { all: '.pick-modal .pick-option', nth: 0 }, n: 3, side: 'left' },
        { target: '.pick-modal .pick-people', n: 4, side: 'left', at: 0.55 },
        { target: '.pick-modal .pick-consequence', n: 5, side: 'left' },
        { target: '.pick-modal .pick-button--primary', n: 6, side: 'right' }
      ]
    });
    if (wanted.includes('route-change')) {
      // Really changes the recipient on the stand: the first employee with enough reserve.
      const chosen = await studio.eval(`(() => {
        const option = [...document.querySelectorAll('.pick-modal .pick-list--people .pick-option')]
          .find(o => o.querySelector('.pick-tag--success') && !o.classList.contains('disabled'));
        if (!option) return null;
        option.scrollIntoView({ block: 'nearest' });
        option.querySelector('input').click();
        return option.querySelector('strong').textContent.trim();
      })()`);
      if (!chosen) throw new Error('no employee with enough reserve');
      await sleep(500);
      await studio.click('.pick-modal .pick-button--primary', 300);
      await waitGone('.pick-modal');
      await page.waitFor(`!!document.querySelector('.route-change-result')`, 30000, 150);
      const waiting = await studio.eval(`[...document.querySelectorAll('.side-stage')].map(e => e.innerText.replace(/\\s+/g, ' ')).join(' || ')`);
      console.log('   stage lines after the change:', waiting);
      if (!/ещё отправляется/.test(waiting)) throw new Error('the wait line was not on the screen: capture again');
      await studio.snap('ci-route-result', {
        clip: { union: ['.side-stage', { block: ROUTE, sub: '.route-change-result' }], frame: PANEL, above: 10, below: 14 },
        marks: [
          { target: '.side-stage', n: 7, side: 'left' },
          { target: { block: ROUTE, sub: '.route-current' }, n: 8, side: 'left' },
          { target: { block: ROUTE, sub: '.route-change-result' }, n: 9, side: 'left' }
        ]
      });
    } else {
      await studio.click({ find: ['.pick-modal .pick-button', 'Отмена'] }, 500);
      await waitGone('.pick-modal');
    }
  },

  // ---- common invoice: sending ----
  async send() {
    await studio.open(invoiceUrl(local.invoices.issued));
    await foldAll();
    await studio.openBlock('Отправка клиенту');
    await studio.snap('ci-send', {
      clip: { block: 'Отправка клиенту' },
      marks: [
        { target: { block: 'Отправка клиенту', sub: '.side-fold-icon' }, n: 1, side: 'right', rx: 14 },
        { target: { block: 'Отправка клиенту', find: ['.side-action', 'Отправить счёт'] }, n: 2, side: 'left' },
        { target: { block: 'Отправка клиенту', find: ['.side-action', 'Скопировать ссылку на оплату'] }, n: 3, side: 'left' },
        { target: { block: 'Отправка клиенту', sub: '.side-note' }, n: 4, side: 'left', rx: 8 }
      ]
    });
  },

  // ---- common invoice: payment term ----
  async term() {
    await studio.open(invoiceUrl(local.invoices.issued));
    await foldAll();
    await studio.openBlock(MORE);
    await waitFor('.overdue-term');
    await setOpen('.overdue-term');
    await sleep(400);
    await studio.eval(`(() => { const i = document.querySelector('.overdue-term input'); i.value = '7'; i.dispatchEvent(new Event('input', { bubbles: true })); return true; })()`);
    await sleep(300);
    await studio.snap('ci-term', {
      clip: { union: [{ block: MORE, head: true }, '.overdue-term'], frame: { block: MORE }, above: 0, below: 14 },
      marks: [
        { target: { block: MORE, sub: '.side-fold-icon' }, n: 1, side: 'right', rx: 14 },
        { target: '.overdue-term summary', n: 2, side: 'left' },
        { target: '.overdue-term input', n: 3, side: 'left' },
        { target: '.overdue-term button[type=submit]', n: 4, side: 'right' }
      ]
    });
    await studio.eval(`(() => { const i = document.querySelector('.overdue-term input'); i.value = '0'; i.dispatchEvent(new Event('input', { bubbles: true })); return true; })()`);
  },

  // ---- common invoice: unpaid, ban, archive ----
  async danger() {
    await studio.open(invoiceUrl(local.invoices.issued));
    await foldAll();
    await studio.openBlock(MORE);
    await setOpen('.side-group--danger');
    await sleep(400);
    await studio.snap('ci-danger', {
      clip: '.side-group--danger',
      marks: [
        { target: { find: ['.side-group--danger .side-action', 'Не оплачен'] }, n: 1, side: 'left' }
      ]
    });
  },

  // ---- common invoice: Tochka documents ----
  async paper() {
    await studio.open(invoiceUrl(local.invoices.issued));
    await foldAll();
    await studio.openBlock('Счёт и акт Точки');
    await sleep(700);
    await studio.snap('ci-paper-start', {
      clip: { block: 'Счёт и акт Точки' },
      marks: [
        { target: { block: 'Счёт и акт Точки', sub: '.side-fold-icon' }, n: 1, side: 'right', rx: 14 },
        { target: { block: 'Счёт и акт Точки', find: ['.side-action', 'Выставить счёт и акт'] }, n: 2, side: 'left' }
      ]
    });
    await studio.open(invoiceUrl(local.invoices.paper));
    await foldAll();
    await studio.openBlock('Счёт и акт Точки');
    await sleep(900);
    await studio.snap('ci-paper-flow', {
      clip: { block: 'Счёт и акт Точки' },
      marks: [
        { target: '.paper-invoice-workflow', n: 3, side: 'left', at: 0.5 },
        { target: { block: 'Счёт и акт Точки', find: ['.side-action', 'Открыть счёт и акт'] }, n: 4, side: 'left' },
        { target: 'app-paper-invoice-list', n: 5, side: 'left', at: 0.35 }
      ]
    });
    await studio.snap('ci-paper-pay', {
      clip: { block: 'Оплата' },
      marks: [{ target: { block: 'Оплата', find: ['.side-action', 'Проверить оплату в банке'] }, n: 6, side: 'left' }]
    });
  },

  // ---- common invoice: return an excluded order ----
  async orders() {
    await studio.open(invoiceUrl(local.invoices.collecting));
    await foldAll();
    await studio.openBlock(MORE);
    await studio.openBlock('Заказы счёта');
    let hasReturn = await studio.eval(`!!window.__shots.find('.side-action', 'Вернуть заказ в счёт')`);
    if (!hasReturn) throw new Error('«Вернуть заказ в счёт» is not offered for this invoice');
    await studio.click({ find: ['.side-action', 'Вернуть заказ в счёт'] }, 600);
    await waitFor('.ret-modal');
    await sleep(1200);
    if (!(await exists('.ret-modal .ret-row'))) {
      // Nothing to return yet: exclude one order of this invoice on the stand first.
      await studio.click({ find: ['.ret-modal .ret-button', 'Закрыть'] }, 500);
      page.acceptDialogs = true;
      const detached = await studio.eval(`(() => { const b = [...document.querySelectorAll('.toolbar-action--detach')].find(x => !x.disabled); if (!b) return false; b.click(); return true; })()`);
      if (!detached) throw new Error('no order of the collecting invoice can be excluded');
      await sleep(4000);
      page.acceptDialogs = false;
      await studio.open(invoiceUrl(local.invoices.collecting));
      await foldAll();
      await studio.openBlock(MORE);
      await studio.openBlock('Заказы счёта');
    } else {
      await studio.click({ find: ['.ret-modal .ret-button', 'Закрыть'] }, 500);
    }
    await waitGone('.ret-modal');
    await studio.snap('ci-orders-return', {
      clip: { union: [{ block: MORE, head: true }, { block: 'Заказы счёта' }], frame: { block: MORE }, below: 14 },
      marks: [
        { target: { block: MORE, sub: '.side-fold-icon' }, n: 1, side: 'right', rx: 14 },
        { target: { block: 'Заказы счёта', sub: '.side-fold-icon' }, n: 2, side: 'right', rx: 14 },
        { target: { find: ['.side-action', 'Вернуть заказ в счёт'] }, n: 3, side: 'left' }
      ]
    });
    await studio.click({ find: ['.side-action', 'Вернуть заказ в счёт'] }, 600);
    await waitFor('.ret-modal .ret-row');
    await sleep(500);
    await studio.eval(`window.__shots.rename('.ret-modal .ret-text strong', ['Студия «Лазер»', 'Салон «Линия»'])`);
    await studio.eval(RETURN_ROW_TITLES);
    await studio.snap('ci-return-window', {
      clip: '.ret-modal',
      marks: [{ target: { find: ['.ret-modal .ret-row .ret-button', 'Вернуть'] }, n: 4, side: 'right' }]
    });
    await studio.click({ find: ['.ret-modal .ret-row .ret-button', 'Вернуть'] }, 600);
    await waitFor('.ret-modal .ret-confirm');
    await studio.eval(`window.__shots.rename('.ret-modal .ret-text strong', ['Студия «Лазер»', 'Салон «Линия»'])`);
    await studio.eval(RETURN_ROW_TITLES);
    await studio.snap('ci-return-confirm', {
      clip: '.ret-modal',
      marks: [
        { target: '.ret-modal .ret-confirm p', n: 5, side: 'left', rx: 8 },
        { target: '.ret-modal .ret-button--primary', n: 6, side: 'right' }
      ]
    });
    // Put the order back, so the stand is left as it was.
    await studio.click('.ret-modal .ret-button--primary', 3500);
    if (await exists('.ret-modal')) {
      await studio.click({ find: ['.ret-modal .ret-actions .ret-button', 'Закрыть'] }, 500).catch(() => {});
    }
  },

  // ---- common invoice: companies of the link ----
  async settings() {
    await studio.open(invoiceUrl(local.invoices.paper));
    await studio.eval(`document.querySelectorAll('.common-billing-right-panel details').forEach(d => { d.open = !!d.querySelector('.company-links, .links-sub') && !d.closest('.invoice-side'); }); true`);
    await sleep(600);
    await studio.eval(`window.__shots.rename('.linked-company-list strong', ['Кофейня «Зерно» — Центр', 'Кофейня «Зерно» — Север', 'Кофейня «Зерно» — Юг', 'Пекарня «Зерно»', 'Кондитерская «Зерно»'])`);
    await studio.snap('ci-settings-companies', {
      clip: '.company-links',
      rules: [['ID \\d+', 'g', 'ID 1024']],
      marks: [
        { target: '.company-search input', n: 1, side: 'left' },
        { target: { all: '.company-remove', nth: 0 }, n: 2, side: 'right', rx: 16 }
      ]
    });
  },

  // ---- company editor: the common invoice of a company whose order already has its own invoice ----
  async company() {
    const companiesUrl = keyword => `/companies?status=${encodeURIComponent('Все')}&keyword=${encodeURIComponent(keyword)}&pageNumber=0&pageSize=5&sortDirection=desc`;
    const section = 'app-manager-company-edit-modal .company-billing-section';
    const openEditor = async keyword => {
      await studio.open(companiesUrl(keyword), `!!document.querySelector('app-manager-company-card .company-edit-link')`);
      // The card is found by its real title before the names on the page are replaced.
      const opened = await studio.eval(`(() => {
        const card = [...document.querySelectorAll('app-manager-company-card')].find(c => c.innerText.includes(${JSON.stringify(keyword)}));
        if (!card) return false;
        card.querySelector('.company-edit-link').click();
        return true;
      })()`);
      if (!opened) throw new Error('company card not found: ' + keyword);
      await waitFor(`${section} .company-billing-toggles`);
      await sleep(1200);
      // The editor scrolls inside itself and a capture resets that scroll: for the picture it is opened at full height.
      await studio.eval(`(() => {
        const modal = document.querySelector('app-manager-company-edit-modal .lead-edit-modal');
        modal.style.maxHeight = 'none';
        modal.style.overflow = 'visible';
        return true;
      })()`);
      await sleep(600);
    };
    // A company without a link: where the common invoice is switched on. Nothing is pressed here.
    await openEditor(local.company.withoutLink);
    await studio.snap('ci-company-billing-on', {
      clip: section,
      marks: [{ target: `${section} .company-billing-toggles .company-switch-field`, n: 1, side: 'left' }]
    });
    await studio.click('app-manager-company-edit-modal .lead-edit-close', 700);
    // A company whose link is already on while one of its orders keeps its own invoice.
    await openEditor(local.company.withSeparateOrder);
    await waitFor(`${section} .company-billing-note--separate`);
    await studio.snap('ci-company-billing-separate', {
      clip: section,
      rules: [['ID \\d+', 'g', 'ID 1024']],
      marks: [
        { target: `${section} .company-billing-title strong`, n: 2, side: 'right', rx: 10 },
        { target: `${section} .company-billing-note--separate`, n: 3, side: 'left' }
      ]
    });
    await studio.click('app-manager-company-edit-modal .lead-edit-close', 700);
  },

  // ---- order window: «Способ оплаты» ----
  async order() {
    await page.viewport(1180, 3200, 2);
    await studio.open(boardUrl(local.order), `!!document.querySelector('app-manager-order-card')`);
    await sleep(800);
    const card = 'app-manager-order-card > *';
    console.log('   edit button:', await studio.eval(`(() => { const b = document.querySelector('app-manager-order-card .worker-edit-button'); return b ? (b.title + ' | ' + b.getAttribute('aria-label') + ' | ' + b.innerText) : null; })()`));
    await studio.snap('op-card', {
      clip: card,
      marks: [{ target: 'app-manager-order-card .worker-edit-button', n: 1, side: 'right', rx: 8 }]
    });
    await studio.snap('op-paid-card', {
      clip: card,
      marks: [{ target: { find: ['app-manager-order-card button', 'оплатили'] }, n: 1, side: 'bottom', len: 28, inside: true }]
    });
    await studio.click('app-manager-order-card .worker-edit-button', 800);
    await waitFor('app-manager-order-edit-modal .payment-route-change');
    await sleep(800);
    const block = { find: ['app-manager-order-edit-modal .payment-route-change', 'Способ оплаты'] };
    await studio.snap('op-block', {
      clip: block,
      marks: [{ target: { find: ['app-manager-order-edit-modal .payment-route-change button', 'Сменить способ оплаты'] }, n: 2, side: 'bottom', len: 22 }]
    });
    await studio.click({ find: ['app-manager-order-edit-modal .payment-route-change button', 'Сменить способ оплаты'] }, 800);
    await page.waitFor(`!!window.__shots.find('app-manager-order-edit-modal .payment-route-change button', 'Выдать номер карты')`, 20000);
    await sleep(500);
    console.log('   block html:', await studio.eval(`window.__shots.resolve(${JSON.stringify(block)}).outerHTML.replace(/\\s+/g, ' ').replace(/_ngcontent-[a-z0-9-]+=""/g, '').slice(0, 2600)`));
    await studio.snap('op-context', {
      clip: block,
      marks: [
        { target: 'app-manager-order-edit-modal .payment-route-change__body > p', n: 3, side: 'left', rx: 6 },
        // The two buttons stand side by side: one arrow comes from the left margin, the other from the free space on the right.
        { target: { find: ['app-manager-order-edit-modal .payment-route-change button', 'Выдать номер карты'] }, n: 4, side: 'left' },
        { target: { find: ['app-manager-order-edit-modal .payment-route-change button', 'Выдать ссылку банка'] }, n: 5, side: 'right', len: 30, inside: true }
      ]
    });
    await studio.click({ find: ['app-manager-order-edit-modal .payment-route-change button', 'Выдать номер карты'] }, 800);
    await waitFor('.pick-modal .pick-list--people .pick-option');
    await sleep(600);
    await studio.snap('op-pick-window', {
      clip: '.pick-modal',
      marks: [
        { target: { all: '.pick-modal .pick-option', nth: 0 }, n: 1, side: 'left' },
        { target: '.pick-modal .pick-people', n: 2, side: 'left', at: 0.55 },
        { target: '.pick-modal .pick-consequence', n: 3, side: 'left' },
        { target: '.pick-modal .pick-button--primary', n: 4, side: 'right' }
      ]
    });
    await studio.click({ find: ['.pick-modal .pick-button', 'Отмена'] }, 500);
    await waitGone('.pick-modal');
    await studio.click({ find: ['app-manager-order-edit-modal button', 'Отмена'] }, 800);
    // «оплатили» on the card
    await studio.click({ find: ['app-manager-order-card button', 'оплатили'] }, 800);
    // The same window as «Оплачен» of a common invoice: the recipient of the invoice is already chosen.
    await waitFor('.pay-modal .pay-recipient.selected');
    await sleep(800);
    await studio.snap('op-paid-window', {
      clip: '.pay-modal',
      marks: [
        { target: '.pay-modal .pay-recipient.selected', n: 2, side: 'left' },
        { target: '.pay-modal .pay-add', n: 3, side: 'left' },
        { target: '.pay-modal .pay-button--primary', n: 4, side: 'right' }
      ]
    });
    await studio.click({ find: ['.pay-modal .pay-button', 'Отмена'] }, 600);
    await waitGone('.pay-modal');
    // «оплатили» of an order whose invoice is the owner's bank link: nobody is chosen, the window explains
    // what to do when the client transferred money past the link. The order belongs to a manager whose
    // owner may take card transfers, so «Владелец» is in the list.
    if (local.bankLinkOrder) {
      await studio.open(boardUrl(local.bankLinkOrder), `!!document.querySelector('app-manager-order-card')`);
      await sleep(800);
      await studio.click({ find: ['app-manager-order-card button', 'оплатили'] }, 800);
      await waitFor('.pay-modal .pay-warning');
      await sleep(800);
      await studio.snap('op-paid-bank-window', {
        clip: '.pay-modal',
        marks: [
          { target: '.pay-modal .pay-warning', n: 1, side: 'left' },
          { target: { all: '.pay-modal .pay-recipient', nth: 0 }, n: 2, side: 'left' },
          { target: '.pay-modal .pay-add', n: 3, side: 'left' }
        ]
      });
      await studio.click({ find: ['.pay-modal .pay-button', 'Отмена'] }, 600);
      await waitGone('.pay-modal');
    }
    await page.viewport(1440, 3200, 2);
  }
};

// ---- ordinary order: the whole invoice and payment path ----
const CARD = 'app-manager-order-card > *';
const boardWith = (status, order) => `/orders?status=${encodeURIComponent(status)}&keyword=${order}&pageNumber=0&pageSize=10&sortDirection=desc`;
// Company, branch and specialist of a card are shown by invented names whatever the stand order is.
const maskCard = () => studio.eval(`(() => {
  for (const card of document.querySelectorAll('app-manager-order-card')) {
    const title = card.querySelector('.order-title-button');
    if (title) title.textContent = 'Кафе «Уют» - Ленина, 10';
    const worker = card.querySelector('.worker-edit-button');
    if (worker) worker.textContent = 'Вера Д.';
    // The categories follow the invented company, not the real one.
    card.querySelectorAll('.category-chip').forEach((chip, index) => { chip.textContent = ['Питание', 'Кафе'][index] ?? chip.textContent; });
  }
  return true;
})()`);
const cardStatus = () => studio.eval(`(document.querySelector('app-manager-order-card .meta-row span')?.textContent ?? '').trim()`);

Object.assign(scenes, {
  // Plain stand only (sandbox off): with immediate messages disabled a published order waits for its invoice.
  async published() {
    await page.viewport(1180, 3200, 2);
    let status = '';
    // Some published orders still wait for review restoration tasks and refuse the step: take the first that agrees.
    for (const order of local.publishedOrders) {
      await studio.open(boardWith('Все', order), `!!document.querySelector('app-manager-order-card')`);
      await sleep(600);
      status = await cardStatus();
      if (status === 'Публикация') {
        await studio.click({ find: ['app-manager-order-card .order-status-actions button', 'опублик.'] }, 3500);
        await studio.open(boardWith('Все', order), `!!document.querySelector('app-manager-order-card')`);
        await sleep(600);
        status = await cardStatus();
      }
      console.log('   order', order, 'status:', status);
      if (status === 'Опубликовано') break;
    }
    if (status !== 'Опубликовано') throw new Error('no order could be moved to «Опубликовано»');
    await maskCard();
    await studio.snap('op-invoice-copy', {
      clip: CARD,
      marks: [
        { target: { find: ['app-manager-order-card .order-links button', 'счет'] }, n: 1, side: 'corner' },
        { target: { find: ['app-manager-order-card .order-status-actions button', 'счет'] }, n: 2, side: 'corner' }
      ]
    });
    await page.viewport(1440, 3200, 2);
  },

  async unpaid() {
    await page.viewport(1180, 3200, 2);
    await studio.open(boardWith('Не оплачено', local.unpaidOrder), `!!document.querySelector('app-manager-order-card')`);
    await sleep(600);
    await maskCard();
    console.log('   actions:', await studio.eval(`[...document.querySelectorAll('app-manager-order-card .order-status-actions button')].map(b => b.textContent.trim()).join(' | ')`));
    const hasBan = await studio.eval(`!!window.__shots.find('app-manager-order-card .order-status-actions button', 'в бан')`);
    await studio.snap('op-unpaid-card', {
      clip: CARD,
      marks: [
        { target: { find: ['app-manager-order-card .order-status-actions button', 'оплатили'] }, n: 1, side: 'corner' },
        ...(hasBan ? [{ target: { find: ['app-manager-order-card .order-status-actions button', 'в бан'] }, n: 2, side: 'corner' }] : [])
      ]
    });
    await page.viewport(1440, 3200, 2);
  },

  async orderflow() {
    await page.viewport(1180, 3200, 2);
    await studio.open(boardUrl(local.order), `!!document.querySelector('app-manager-order-card')`);
    await sleep(800);
    await maskCard();
    await studio.snap('op-card-map', {
      clip: CARD,
      marks: [
        { target: { all: 'app-manager-order-card .meta-row span', nth: 0 }, n: 1, side: 'left', rx: 16 },
        { target: { all: 'app-manager-order-card .meta-row span', nth: 1 }, n: 2, side: 'right', rx: 16 },
        { target: 'app-manager-order-card .phone-row a', n: 3, side: 'left', rx: 16 },
        { target: { find: ['app-manager-order-card .order-links button', 'счет'] }, n: 4, side: 'corner', rx: 16 },
        { target: 'app-manager-order-card .order-status-actions', n: 5, side: 'left', rx: 16 },
        { target: 'app-manager-order-card .worker-edit-button', n: 6, side: 'right', rx: 8 }
      ]
    });
    await studio.click('app-manager-order-card .worker-edit-button', 800);
    await waitFor('app-manager-order-edit-modal .payment-route-change');
    await sleep(800);
    const paperBlock = { find: ['app-manager-order-edit-modal .payment-route-change', 'Счёт и акт Точки'] };
    await studio.snap('op-paper-button', {
      clip: paperBlock,
      marks: [{ target: { find: ['app-manager-order-edit-modal .payment-route-change button', 'Выставить или открыть счёт и акт'] }, n: 1, side: 'bottom', len: 22 }]
    });
    const block = { find: ['app-manager-order-edit-modal .payment-route-change', 'Способ оплаты'] };
    await studio.click({ find: ['app-manager-order-edit-modal .payment-route-change button', 'Сменить способ оплаты'] }, 800);
    await page.waitFor(`!!window.__shots.find('app-manager-order-edit-modal .payment-route-change button', 'Выдать ссылку банка')`, 20000);
    await sleep(500);
    await studio.snap('op-bank-button', {
      clip: block,
      marks: [{ target: { find: ['app-manager-order-edit-modal .payment-route-change button', 'Выдать ссылку банка'] }, n: 1, side: 'right', len: 30, inside: true }]
    });
    await studio.click({ find: ['app-manager-order-edit-modal .payment-route-change button', 'Выставить или открыть счёт и акт'] }, 800);
    await waitFor('.paper-backdrop');
    await sleep(2500);
    console.log('   paper window:', await studio.eval(`document.querySelector('.paper-backdrop').innerText.replace(/\\s+/g, ' ').slice(0, 900)`));
    await studio.eval(`(() => {
      const root = document.querySelector('.paper-backdrop');
      for (const option of root.querySelectorAll('select[name=savedPayer] option')) {
        if (option.value) option.textContent = 'ООО «Зерно» · ИНН 7700000000';
      }
      // The form stays empty: an invented tax number either fails the check or may belong to somebody.
      const confirmed = root.querySelector('input[name=confirmed]');
      if (confirmed && !confirmed.checked) confirmed.click();
      return true;
    })()`);
    await sleep(700);
    await studio.snap('op-paper-window', {
      clip: '.paper-backdrop > *',
      marks: [
        { target: '.paper-backdrop input[name=inn]', n: 2, side: 'left' },
        { target: { find: ['.paper-backdrop label.check', 'Клиент ещё не оплатил'] }, n: 3, side: 'left', rx: 8 },
        { target: '.paper-backdrop button.primary[type=submit]', n: 4, side: 'right' }
      ]
    });
    await studio.click({ find: ['.paper-backdrop footer button', 'Закрыть'] }, 600);
    await studio.click({ find: ['app-manager-order-edit-modal button', 'Отмена'] }, 600).catch(() => {});
    await page.viewport(1440, 3200, 2);
  }
});

const run = wanted.filter(name => scenes[name]);
try {
  for (const name of run.length ? run : Object.keys(scenes)) {
    console.log('== ' + name);
    await scenes[name]();
  }
} finally {
  await studio.finish();
}
