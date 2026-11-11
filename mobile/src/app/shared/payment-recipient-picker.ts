/** A person a manager may name when a card number is issued for an invoice. */
export interface MobilePaymentRecipientOption {
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

/** `profileId: null` lets the system choose: payment task, then the order specialist, then the manager. */
export interface MobilePaymentRecipientChoice {
  profileId: number | null;
}

export interface MobilePaymentRecipientTag {
  text: string;
  tone: 'current' | 'muted' | 'warning' | 'success';
}

export interface MobilePaymentRecipientLoad {
  recipients: MobilePaymentRecipientOption[];
  /** The amount the server counted the reserve against; left out when the page already knows it. */
  amountKopecks?: number | null;
}

/** What pressing «Выдать номер карты» led to. */
export type MobilePaymentRecipientIssueOutcome =
  /** The server issued the card number. */
  | { status: 'issued' }
  /** HTTP 423: the request is saved and finishes by itself after the bank closes the old link. */
  | { status: 'accepted'; message: string }
  /** The server refused: the window stays open with the reason, so another person can be chosen at once. */
  | { status: 'refused'; message: string }
  /** The page was left meanwhile: nothing is shown and nothing is changed on the new screen. */
  | { status: 'left' };

/** How the window ended for the page that opened it; null stands for «closed without a card number». */
export type MobilePaymentRecipientPickerResult = Extract<MobilePaymentRecipientIssueOutcome, { status: 'issued' | 'accepted' }>;

/** What a page hands to the «Выдать номер карты» window. */
export interface MobilePaymentRecipientPickerRequest {
  heading: string;
  currentRecipient: string;
  autoHint: string;
  /** Known before the people are loaded (common invoice); an order learns it together with the people. */
  amountKopecks: number | null;
  /** Rejects with an Error whose message can be shown to the manager. */
  loadRecipients(): Promise<MobilePaymentRecipientLoad>;
  /** Sends the choice; pressing the button is also the confirmation that the client has not paid yet. */
  issue(choice: MobilePaymentRecipientChoice): Promise<MobilePaymentRecipientIssueOutcome>;
  /** Aborted when the page that opened the window is left: the window closes without a request. */
  signal?: AbortSignal;
}

type WireRecipient = {
  profileId?: number | null;
  name?: string | null;
  role?: string | null;
  orderSpecialist?: boolean | null;
  manager?: boolean | null;
  teamMember?: boolean | null;
  workedWithClient?: boolean | null;
  availableKopecks?: number | null;
  overrunKopecks?: number | null;
  current?: boolean | null;
  blockReason?: string | null;
};

/** Server rows as the window shows them; a row without a payment profile cannot be named and is dropped. */
export function mobilePaymentRecipientOptions(rows: readonly WireRecipient[] | null | undefined): MobilePaymentRecipientOption[] {
  return (rows ?? []).flatMap(row => typeof row.profileId === 'number' ? [{
    profileId: row.profileId,
    name: (row.name ?? '').trim(),
    role: row.role ?? '',
    orderSpecialist: row.orderSpecialist === true,
    manager: row.manager === true,
    teamMember: row.teamMember === true,
    workedWithClient: row.workedWithClient === true,
    availableKopecks: row.availableKopecks ?? 0,
    overrunKopecks: Math.max(0, row.overrunKopecks ?? 0),
    current: row.current === true,
    blockReason: (row.blockReason ?? '').trim() || null
  }] : []);
}

export function mobilePaymentRecipientRelation(option: MobilePaymentRecipientOption): string {
  if (option.orderSpecialist) {
    return 'специалист заказа';
  }
  if (option.manager) {
    return 'менеджер';
  }
  return option.workedWithClient ? 'работал с этим клиентом' : 'сотрудник менеджера';
}

/** The current recipient and a person the server blocks are shown, but cannot be chosen. */
export function mobilePaymentRecipientSelectable(option: MobilePaymentRecipientOption): boolean {
  return !option.current && !option.blockReason;
}

export function mobilePaymentRecipientTag(option: MobilePaymentRecipientOption): MobilePaymentRecipientTag {
  if (option.current) {
    return { text: 'сейчас в счёте', tone: 'current' };
  }
  if (option.blockReason) {
    return { text: 'недоступен', tone: 'muted' };
  }
  return option.overrunKopecks > 0 ? { text: 'сверх резерва', tone: 'warning' } : { text: 'хватает', tone: 'success' };
}

export function mobilePaymentRecipientMoney(kopecks: number | null | undefined): string {
  return `${new Intl.NumberFormat('ru-RU', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  }).format((kopecks ?? 0) / 100)} ₽`;
}

/** The line under a name: what the reserve says, or why the person cannot be chosen. */
export function mobilePaymentRecipientNote(option: MobilePaymentRecipientOption): string {
  if (option.blockReason) {
    return option.blockReason;
  }
  const available = `доступно ${mobilePaymentRecipientMoney(option.availableKopecks)}`;
  return option.overrunKopecks > 0
    ? `${available}, не хватает ${mobilePaymentRecipientMoney(option.overrunKopecks)}`
    : available;
}

export function mobilePaymentRecipientSearch(
  options: readonly MobilePaymentRecipientOption[],
  query: string
): MobilePaymentRecipientOption[] {
  const needle = (query ?? '').trim().toLocaleLowerCase('ru');
  return options.filter(option => !needle || option.name.toLocaleLowerCase('ru').includes(needle));
}

/** The person behind a choice; null for the automatic choice and for a person who is not in the list. */
export function mobilePaymentRecipientChosen(
  options: readonly MobilePaymentRecipientOption[],
  profileId: number | null
): MobilePaymentRecipientOption | null {
  return profileId == null ? null : options.find(option => option.profileId === profileId) ?? null;
}

/** The automatic choice is always allowed; a named person must be in the list and selectable. */
export function mobilePaymentRecipientChoiceAllowed(
  options: readonly MobilePaymentRecipientOption[],
  profileId: number | null
): boolean {
  if (profileId == null) {
    return true;
  }
  const option = mobilePaymentRecipientChosen(options, profileId);
  return option !== null && mobilePaymentRecipientSelectable(option);
}

/** What the button will do, said before it is pressed. */
export function mobilePaymentRecipientConsequence(option: MobilePaymentRecipientOption | null): string {
  const unpaid = 'Нажимая кнопку, вы подтверждаете, что клиент ещё не оплатил по прежним реквизитам.';
  if (!option) {
    return `Клиенту сразу уйдёт новый счёт с номером карты, резерв прежнего получателя снимется. ${unpaid}`;
  }
  const name = option.name || 'выбранного сотрудника';
  if (option.overrunKopecks > 0) {
    return `Счёт будет выставлен на ${name} сверх резерва на ${mobilePaymentRecipientMoney(option.overrunKopecks)}. `
      + 'Превышение сохранится для сверки, и система не будет сама назначать этого человека в новые счета, '
      + `пока его остаток не восстановится. Клиенту сразу уйдёт новый счёт, резерв прежнего получателя снимется. ${unpaid}`;
  }
  return `Счёт будет выставлен на ${name}: клиенту сразу уйдёт новый счёт с номером карты этого сотрудника, `
    + `резерв прежнего получателя снимется. ${unpaid}`;
}

/** How the automatic choice works for the route the card number is issued from. */
export function mobilePaymentRecipientAutoHint(target: string): string {
  return target === 'DIFFERENT_RECIPIENT'
    ? 'Система выдаст номер другого получателя — платёжного задания или менеджера, если у него хватает резерва. '
      + 'Нынешний получатель исключается. Если не подойдёт никто, счёт не изменится.'
    : 'Система возьмёт платёжное задание, затем специалиста заказа, затем менеджера — первого, у кого хватает резерва. '
      + 'Если не подойдёт никто, счёт не изменится и ссылка останется действующей.';
}
