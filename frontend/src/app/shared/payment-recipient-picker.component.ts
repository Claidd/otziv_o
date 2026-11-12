import { Component, EventEmitter, Input, Output, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

/** A person a manager may name when a card number is issued for an invoice. */
export interface PaymentRecipientOption {
  profileId: number;
  name: string;
  role: string;
  orderSpecialist: boolean;
  manager: boolean;
  teamMember: boolean;
  workedWithClient: boolean;
  availableKopecks: number;
  overrunKopecks: number;
  /** The client already holds this person's card number. */
  current: boolean;
  /** Why the person cannot be chosen; empty when the person can. */
  blockReason?: string | null;
}

/** In place of an employee's profile: the card of the owner. The server reads the same value. */
export const OWNER_CARD_PROFILE_ID = -1;

/**
 * The owner's card as a line of the window. The server sends it only where the owner of the manager's
 * bank profile may take card transfers at all; an owner who works through the bank only is never offered.
 */
export interface PaymentRecipientOwnerCard {
  name: string;
  /** What may still be issued on the card this month. */
  remainingKopecks: number;
  /** The client already holds the owner's card. */
  current: boolean;
  /** Why the card cannot be issued for this invoice; empty when it can. */
  blockReason?: string | null;
}

/**
 * `profileId: null` lets the system choose: payment task, then the order specialist, then the manager,
 * then the owner's card. `OWNER_CARD_PROFILE_ID` names the owner's card.
 */
export interface PaymentRecipientChoice {
  profileId: number | null;
}

export function paymentRecipientRelation(option: PaymentRecipientOption): string {
  if (option.orderSpecialist) {
    return 'специалист заказа';
  }
  if (option.manager) {
    return 'менеджер';
  }
  return option.workedWithClient ? 'работал с этим клиентом' : 'сотрудник менеджера';
}

/**
 * The «Выдать номер карты» window: the system's own choice by default, or a person the manager
 * names. A person may be named above the available reserve; the window says so before the button.
 */
@Component({
  selector: 'app-payment-recipient-picker',
  imports: [FormsModule],
  template: `
<div class="pick-backdrop" role="presentation" (click)="close()">
  <section class="pick-modal" role="dialog" aria-modal="true" aria-labelledby="recipient-picker-title"
    (click)="$event.stopPropagation()" (keydown.escape)="close()">
    <header class="pick-head">
      <div>
        @if (heading) { <span class="pick-kicker">{{ heading }}</span> }
        <h2 id="recipient-picker-title">Выдать номер карты</h2>
        @if (currentRecipient) { <p>Сейчас в счёте: {{ currentRecipient }}</p> }
      </div>
      <button type="button" class="pick-close" aria-label="Закрыть" [disabled]="saving" (click)="close()">
        <span class="material-icons-sharp" aria-hidden="true">close</span>
      </button>
    </header>

    <div class="pick-amount">
      <span>Сумма счёта</span>
      <strong>{{ money(amountKopecks) }}</strong>
    </div>

    <fieldset class="pick-list" [disabled]="saving">
      <legend>Кому клиент будет переводить?</legend>
      <label class="pick-option" [class.selected]="selected() === null">
        <input type="radio" name="route-recipient" [checked]="selected() === null" (change)="select(null)" />
        <span class="pick-text">
          <strong>Подобрать автоматически</strong>
          <small>{{ autoHint }}</small>
        </span>
      </label>
    </fieldset>

    <section class="pick-people" aria-label="Выбрать получателя самостоятельно">
      <div class="pick-people-head">
        <strong>Или выберите получателя сами</strong>
        <small>{{ ownerCard
          ? 'Можно выбрать любого сотрудника, даже если резерва не хватает. Карту владельца — пока её лимит на месяц вмещает счёт.'
          : 'Можно выбрать любого сотрудника, даже если резерва не хватает.' }}</small>
      </div>
      @if (loading) {
        <p class="pick-state">Загружаем сотрудников…</p>
      } @else if (error) {
        <p class="pick-error" role="alert">{{ error }}</p>
        <button type="button" class="pick-link" (click)="retry.emit()">Повторить</button>
      } @else if (!(recipients ?? []).length && !ownerCard) {
        <p class="pick-state">Сотрудников с платёжным профилем для этого счёта нет.</p>
      } @else {
        @if ((recipients ?? []).length > 6) {
          <input type="search" autocomplete="off" placeholder="Поиск по имени" aria-label="Поиск сотрудника по имени"
            [ngModel]="query()" [disabled]="saving" (ngModelChange)="query.set($event)" />
        }
        @if (!visible().length && (recipients ?? []).length) {
          <p class="pick-state">Никого не найдено. Проверьте имя.</p>
        }
        <fieldset class="pick-list pick-list--people" [disabled]="saving">
          <legend class="pick-hidden">Владелец и сотрудники</legend>
          @if (ownerCard; as card) {
            <label class="pick-option pick-option--owner" [class.selected]="selected() === ownerCardId" [class.disabled]="!ownerCardSelectable()">
              <input type="radio" name="route-recipient" [checked]="selected() === ownerCardId"
                [disabled]="!ownerCardSelectable()" (change)="select(ownerCardId)" />
              <span class="pick-text">
                <strong>Владелец{{ card.name ? ' · ' + card.name : '' }}</strong>
                <small>карта владельца · {{ ownerCardNote() }}</small>
              </span>
              @if (card.current) {
                <span class="pick-tag">сейчас в счёте</span>
              } @else if (card.blockReason) {
                <span class="pick-tag pick-tag--muted">недоступен</span>
              } @else {
                <span class="pick-tag pick-tag--success">лимит вмещает</span>
              }
            </label>
          }
          @for (option of visible(); track option.profileId) {
            <label class="pick-option" [class.selected]="selected() === option.profileId" [class.disabled]="!selectable(option)">
              <input type="radio" name="route-recipient" [checked]="selected() === option.profileId"
                [disabled]="!selectable(option)" (change)="select(option.profileId)" />
              <span class="pick-text">
                <strong>{{ option.name || 'Без имени' }}</strong>
                <small>{{ relation(option) }} · {{ note(option) }}</small>
              </span>
              @if (option.current) {
                <span class="pick-tag">сейчас в счёте</span>
              } @else if (option.blockReason) {
                <span class="pick-tag pick-tag--muted">недоступен</span>
              } @else if (option.overrunKopecks > 0) {
                <span class="pick-tag pick-tag--warning">сверх резерва</span>
              } @else {
                <span class="pick-tag pick-tag--success">хватает</span>
              }
            </label>
          }
        </fieldset>
      }
    </section>

    <p class="pick-consequence" [class.warning]="chosenOverrun() > 0" role="status">
      <span class="material-icons-sharp" aria-hidden="true">{{ chosenOverrun() > 0 ? 'warning' : 'info' }}</span>
      <span>{{ consequence() }}</span>
    </p>

    @if (submitError) {
      <div class="pick-error" role="alert">
        <strong>Номер карты не выдан, счёт не изменён.</strong>
        <span>{{ submitError }}</span>
        @if (!submitError.includes('Что делать')) {
          <span>Что делать: выберите другого получателя в списке выше или нажмите «Отмена» и оставьте клиенту прежний способ оплаты.</span>
        }
      </div>
    }

    <div class="pick-actions">
      <button type="button" class="pick-button" [disabled]="saving" (click)="close()">Отмена</button>
      <button type="button" class="pick-button pick-button--primary" [disabled]="saving" (click)="submit()">
        {{ saving ? 'Выдаём…' : 'Выдать номер карты' }}
      </button>
    </div>
  </section>
</div>
  `,
  styles: [`
:host {
  position: fixed;
  inset: 0;
  z-index: 1200;
  --pick-surface: #ffffff;
  --pick-soft: #f5f7fa;
  --pick-border: #e3e8ef;
  --pick-control-border: #d5dde7;
  --pick-text: #1f2430;
  --pick-muted: #67758a;
  --pick-accent: #a93422;
  --pick-accent-soft: #fff2ed;
  --pick-accent-border: #e4a493;
  --pick-success: #126650;
  --pick-success-soft: #e8f7f2;
  --pick-success-border: #a7d9ca;
  --pick-warning: #805600;
  --pick-warning-soft: #fff6df;
  --pick-warning-border: #e4c36e;
  --pick-danger: #a62b48;
  --pick-danger-soft: #fff0f3;
  --pick-danger-border: #e7a5b5;
  --pick-disabled-text: #929ba8;
}
:host-context(body.otziv-dark-theme) {
  --pick-surface: #22272b;
  --pick-soft: #2a3137;
  --pick-border: #3b444c;
  --pick-control-border: #46515a;
  --pick-text: #f2f5f8;
  --pick-muted: #aeb8c3;
  --pick-accent: #ffad95;
  --pick-accent-soft: #3b2a25;
  --pick-accent-border: #765044;
  --pick-success: #79d8c2;
  --pick-success-soft: #203a34;
  --pick-success-border: #386c61;
  --pick-warning: #f0cf83;
  --pick-warning-soft: #3a3221;
  --pick-warning-border: #6b592f;
  --pick-danger: #ff9ab6;
  --pick-danger-soft: #402630;
  --pick-danger-border: #754354;
  --pick-disabled-text: #707b85;
}
* { box-sizing: border-box; }
.pick-backdrop {
  position: fixed;
  inset: 0;
  display: grid;
  place-items: center;
  padding: 1rem;
  background: rgba(12, 16, 22, 0.62);
  overflow-y: auto;
}
.pick-modal {
  width: min(100%, 34rem);
  max-height: calc(100vh - 2rem);
  overflow-y: auto;
  padding: 1.1rem 1.15rem 1.15rem;
  color: var(--pick-text);
  background: var(--pick-surface);
  border: 1px solid var(--pick-border);
  border-radius: 18px;
  box-shadow: 0 24px 60px rgba(8, 12, 18, 0.35);
  scrollbar-width: thin;
  scrollbar-color: var(--pick-control-border) transparent;
}
.pick-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 0.75rem;
  margin-bottom: 0.8rem;
}
.pick-kicker {
  display: block;
  font-size: 0.66rem;
  font-weight: 900;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--pick-accent);
}
.pick-head h2 { margin: 0.1rem 0 0; font-size: 1.15rem; line-height: 1.25; }
.pick-head p { margin: 0.2rem 0 0; font-size: 0.8rem; color: var(--pick-muted); }
.pick-modal .pick-close {
  display: grid;
  place-items: center;
  width: 2.1rem;
  height: 2.1rem;
  min-height: 0;
  padding: 0;
  color: var(--pick-text);
  background: var(--pick-soft);
  border: 1px solid var(--pick-border);
  border-radius: 999px;
  box-shadow: none;
  cursor: pointer;
}
.pick-modal .pick-close:not(:disabled):hover { color: var(--pick-text); background: var(--pick-soft); border-color: var(--pick-accent-border); }
.pick-amount {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 0.75rem;
  padding: 0.6rem 0.8rem;
  background: var(--pick-soft);
  border: 1px solid var(--pick-border);
  border-radius: 12px;
}
.pick-amount span { font-size: 0.78rem; color: var(--pick-muted); }
.pick-amount strong { font-size: 1.05rem; }
.pick-list { display: grid; gap: 0.4rem; min-width: 0; margin: 0.8rem 0 0; padding: 0; border: 0; }
.pick-list legend { padding: 0; margin-bottom: 0.4rem; font-size: 0.88rem; font-weight: 800; }
.pick-list--people { margin-top: 0.5rem; max-height: 17rem; overflow-y: auto; padding-right: 0.15rem; scrollbar-width: thin; scrollbar-color: var(--pick-control-border) transparent; }
.pick-hidden { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); }
.pick-option {
  display: flex;
  align-items: center;
  gap: 0.65rem;
  padding: 0.6rem 0.75rem;
  background: var(--pick-soft);
  border: 1px solid var(--pick-border);
  border-radius: 12px;
  cursor: pointer;
}
.pick-option:hover { border-color: var(--pick-accent-border); }
.pick-option.selected { background: var(--pick-success-soft); border-color: var(--pick-success-border); }
.pick-option.disabled { cursor: not-allowed; opacity: 0.72; }
.pick-option.disabled:hover { border-color: var(--pick-border); }
.pick-option input { flex: none; width: 1rem; height: 1rem; margin: 0; accent-color: var(--pick-success); }
.pick-option:focus-within { outline: 2px solid var(--pick-accent); outline-offset: 2px; }
.pick-text { display: grid; gap: 0.12rem; min-width: 0; flex: 1; }
.pick-text strong { font-size: 0.9rem; overflow-wrap: anywhere; }
.pick-text small { font-size: 0.75rem; line-height: 1.35; color: var(--pick-muted); }
.pick-tag {
  flex: none;
  padding: 0.15rem 0.5rem;
  border-radius: 999px;
  font-size: 0.62rem;
  font-weight: 900;
  white-space: nowrap;
  color: var(--pick-accent);
  background: var(--pick-accent-soft);
}
.pick-tag--success { color: var(--pick-success); background: var(--pick-success-soft); }
.pick-tag--warning { color: var(--pick-warning); background: var(--pick-warning-soft); }
.pick-tag--muted { color: var(--pick-muted); background: var(--pick-surface); }
.pick-people { margin-top: 0.9rem; padding: 0.75rem; border: 1px solid var(--pick-border); border-radius: 12px; }
.pick-people-head { display: grid; gap: 0.15rem; margin-bottom: 0.5rem; }
.pick-people-head strong { font-size: 0.88rem; }
.pick-people-head small { font-size: 0.75rem; color: var(--pick-muted); }
.pick-modal input[type="search"] {
  width: 100%;
  height: 2.3rem;
  padding: 0 0.7rem;
  color: var(--pick-text);
  background: var(--pick-surface);
  border: 1px solid var(--pick-control-border);
  border-radius: 10px;
  font: inherit;
  font-size: 0.85rem;
}
.pick-modal input[type="search"]:focus-visible { outline: 2px solid var(--pick-accent); outline-offset: 1px; }
.pick-state, .pick-error, .pick-consequence { margin: 0.6rem 0 0; font-size: 0.8rem; line-height: 1.4; }
.pick-state { color: var(--pick-muted); }
.pick-error { padding: 0.55rem 0.7rem; color: var(--pick-danger); background: var(--pick-danger-soft); border: 1px solid var(--pick-danger-border); border-radius: 10px; }
div.pick-error { display: grid; gap: 0.25rem; }
.pick-consequence {
  display: flex;
  gap: 0.5rem;
  margin-top: 0.9rem;
  padding: 0.6rem 0.7rem;
  background: var(--pick-soft);
  border: 1px solid var(--pick-border);
  border-radius: 10px;
}
.pick-consequence.warning { color: var(--pick-warning); background: var(--pick-warning-soft); border-color: var(--pick-warning-border); }
.pick-consequence .material-icons-sharp { flex: none; font-size: 1.05rem; color: var(--pick-accent); }
.pick-consequence.warning .material-icons-sharp { color: var(--pick-warning); }
.pick-actions { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: 0.6rem; margin-top: 1rem; }
.pick-modal .pick-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 2.5rem;
  min-height: 0;
  padding: 0 1.1rem;
  color: var(--pick-text);
  background: var(--pick-soft);
  border: 1px solid var(--pick-control-border);
  border-radius: 12px;
  box-shadow: none;
  font-weight: 700;
  cursor: pointer;
}
.pick-modal .pick-button:not(:disabled):hover { color: var(--pick-text); background: var(--pick-soft); border-color: var(--pick-accent-border); }
.pick-modal .pick-button--primary { color: var(--pick-success); background: var(--pick-success-soft); border-color: var(--pick-success-border); }
.pick-modal .pick-button--primary:not(:disabled):hover { color: var(--pick-success); background: var(--pick-success-soft); border-color: var(--pick-success); }
.pick-modal .pick-button:disabled { color: var(--pick-disabled-text); cursor: not-allowed; border-style: dashed; }
.pick-modal .pick-link {
  display: inline;
  height: auto;
  min-height: 0;
  padding: 0;
  color: var(--pick-accent);
  background: transparent;
  border: 0;
  box-shadow: none;
  font-size: 0.8rem;
  font-weight: 700;
  text-decoration: underline;
  cursor: pointer;
}
.pick-modal .pick-link:not(:disabled):hover { color: var(--pick-accent); background: transparent; }
.pick-modal .pick-button:focus-visible, .pick-modal .pick-close:focus-visible, .pick-modal .pick-link:focus-visible { outline: 2px solid var(--pick-accent); outline-offset: 2px; }
  `]
})
export class PaymentRecipientPickerComponent {
  @Input() heading = '';
  @Input() amountKopecks = 0;
  @Input() currentRecipient = '';
  @Input() autoHint = 'Система возьмёт платёжное задание, затем специалиста заказа, затем менеджера — первого, у кого хватает резерва. Если не подойдёт никто, счёт не изменится.';
  @Input() recipients: PaymentRecipientOption[] | null = null;
  /** The owner's card, where the server offers it; null hides the line. */
  @Input() ownerCard: PaymentRecipientOwnerCard | null = null;
  @Input() loading = false;
  @Input() error = '';
  @Input() saving = false;
  /** The server's refusal of the last attempt; the window stays open so another person can be chosen. */
  @Input() submitError = '';

  @Output() readonly chosen = new EventEmitter<PaymentRecipientChoice>();
  @Output() readonly closed = new EventEmitter<void>();
  @Output() readonly retry = new EventEmitter<void>();

  /** null is the automatic choice. */
  readonly selected = signal<number | null>(null);
  readonly query = signal('');
  readonly relation = paymentRecipientRelation;
  readonly ownerCardId = OWNER_CARD_PROFILE_ID;

  readonly visible = computed(() => {
    const query = this.query().trim().toLocaleLowerCase('ru');
    return (this.recipients ?? []).filter(option => !query || option.name.toLocaleLowerCase('ru').includes(query));
  });
  readonly chosenOption = computed(() => {
    const profileId = this.selected();
    return profileId == null || profileId === OWNER_CARD_PROFILE_ID
      ? null : (this.recipients ?? []).find(option => option.profileId === profileId) ?? null;
  });
  readonly chosenOverrun = computed(() => this.chosenOption()?.overrunKopecks ?? 0);
  /** What the button will do, said before it is pressed. */
  readonly consequence = computed(() => {
    const unpaid = 'Нажимая кнопку, вы подтверждаете, что клиент ещё не оплатил по прежним реквизитам.';
    const option = this.chosenOption();
    if (this.selected() === OWNER_CARD_PROFILE_ID) {
      const owner = this.ownerCard?.name ? `владельца (${this.ownerCard.name})` : 'владельца';
      return `Счёт будет выставлен на карту ${owner}: клиенту сразу уйдёт новый счёт с номером этой карты, `
        + 'резерв прежнего получателя снимется, сумма займёт месячный лимит карты. '
        + `Когда клиент переведёт, поступление подтверждает владелец. ${unpaid}`;
    }
    if (!option) {
      return `Клиенту сразу уйдёт новый счёт с номером карты, резерв прежнего получателя снимется. ${unpaid}`;
    }
    const name = option.name || 'выбранного сотрудника';
    if (option.overrunKopecks > 0) {
      return `Счёт будет выставлен на ${name} сверх резерва на ${this.money(option.overrunKopecks)}. `
        + 'Превышение сохранится для сверки, и система не будет сама назначать этого человека в новые счета, '
        + `пока его остаток не восстановится. Клиенту сразу уйдёт новый счёт, резерв прежнего получателя снимется. ${unpaid}`;
    }
    return `Счёт будет выставлен на ${name}: клиенту сразу уйдёт новый счёт с номером карты этого сотрудника, `
      + `резерв прежнего получателя снимется. ${unpaid}`;
  });

  selectable(option: PaymentRecipientOption): boolean {
    return !option.current && !option.blockReason;
  }

  ownerCardSelectable(): boolean {
    return !!this.ownerCard && !this.ownerCard.current && !this.ownerCard.blockReason;
  }

  /** The line under the owner: what is left of the month, or why the card cannot be issued. */
  ownerCardNote(): string {
    const card = this.ownerCard;
    if (!card) {
      return '';
    }
    const remaining = `осталось в этом месяце ${this.money(card.remainingKopecks)}`;
    return card.blockReason ? `${card.blockReason} (${remaining})` : remaining;
  }

  select(profileId: number | null): void {
    this.selected.set(profileId);
  }

  /** The line under a name: what the reserve says, or why the person cannot be chosen. */
  note(option: PaymentRecipientOption): string {
    if (option.blockReason) {
      return option.blockReason;
    }
    const available = `доступно ${this.money(option.availableKopecks)}`;
    return option.overrunKopecks > 0 ? `${available}, не хватает ${this.money(option.overrunKopecks)}` : available;
  }

  submit(): void {
    if (this.saving) {
      return;
    }
    if (this.selected() === OWNER_CARD_PROFILE_ID) {
      if (this.ownerCardSelectable()) {
        this.chosen.emit({ profileId: OWNER_CARD_PROFILE_ID });
      }
      return;
    }
    const option = this.chosenOption();
    if (this.selected() != null && (!option || !this.selectable(option))) {
      return;
    }
    this.chosen.emit({ profileId: this.selected() });
  }

  close(): void {
    if (!this.saving) {
      this.closed.emit();
    }
  }

  money(kopecks: number | null | undefined): string {
    return `${new Intl.NumberFormat('ru-RU', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    }).format((kopecks ?? 0) / 100)} ₽`;
  }
}
