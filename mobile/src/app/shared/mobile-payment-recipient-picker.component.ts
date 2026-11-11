import { ChangeDetectionStrategy, Component, Input, OnDestroy, OnInit, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  IonButton,
  IonButtons,
  IonContent,
  IonFooter,
  IonHeader,
  IonSpinner,
  IonTitle,
  IonToolbar,
  ModalController
} from '@ionic/angular';
import {
  mobilePaymentRecipientAutoHint,
  mobilePaymentRecipientChoiceAllowed,
  mobilePaymentRecipientChosen,
  mobilePaymentRecipientConsequence,
  mobilePaymentRecipientMoney,
  mobilePaymentRecipientNote,
  mobilePaymentRecipientRelation,
  mobilePaymentRecipientSearch,
  mobilePaymentRecipientSelectable,
  mobilePaymentRecipientTag,
  type MobilePaymentRecipientIssueOutcome,
  type MobilePaymentRecipientOption,
  type MobilePaymentRecipientPickerRequest
} from './payment-recipient-picker';

/**
 * The «Выдать номер карты» window: the system's own choice by default, or a person the manager
 * names. A person may be named above the available reserve; the window says so before the button.
 */
@Component({
  changeDetection: ChangeDetectionStrategy.Eager,
  selector: 'app-mobile-payment-recipient-picker',
  imports: [
    FormsModule,
    IonButton,
    IonButtons,
    IonContent,
    IonFooter,
    IonHeader,
    IonSpinner,
    IonTitle,
    IonToolbar
  ],
  templateUrl: './mobile-payment-recipient-picker.component.html',
  styleUrl: './mobile-payment-recipient-picker.component.scss'
})
export class MobilePaymentRecipientPickerComponent implements OnInit, OnDestroy {
  @Input() heading = '';
  @Input() currentRecipient = '';
  @Input() autoHint = mobilePaymentRecipientAutoHint('EMPLOYEE_REQUISITES');
  @Input() amountKopecks: number | null = null;
  @Input({ required: true }) loadRecipients!: MobilePaymentRecipientPickerRequest['loadRecipients'];
  @Input({ required: true }) issue!: MobilePaymentRecipientPickerRequest['issue'];

  readonly recipients = signal<MobilePaymentRecipientOption[]>([]);
  readonly amount = signal<number | null>(null);
  readonly loading = signal(true);
  readonly loadError = signal('');
  readonly saving = signal(false);
  /** The server's refusal of the last attempt; the window stays open so another person can be chosen. */
  readonly submitError = signal('');
  /** null is the automatic choice. */
  readonly selected = signal<number | null>(null);
  readonly query = signal('');

  readonly relation = mobilePaymentRecipientRelation;
  readonly selectable = mobilePaymentRecipientSelectable;
  readonly note = mobilePaymentRecipientNote;
  readonly tag = mobilePaymentRecipientTag;
  readonly money = mobilePaymentRecipientMoney;

  readonly visible = computed(() => mobilePaymentRecipientSearch(this.recipients(), this.query()));
  readonly chosenOption = computed(() => mobilePaymentRecipientChosen(this.recipients(), this.selected()));
  readonly chosenOverrun = computed(() => this.chosenOption()?.overrunKopecks ?? 0);
  /** What the button will do, said before it is pressed. */
  readonly consequence = computed(() => mobilePaymentRecipientConsequence(this.chosenOption()));

  private loadRun = 0;
  /** Set once the window is closing or gone: a late answer must not touch it or another dialog. */
  private closed = false;

  constructor(private readonly modalController: ModalController) {}

  ngOnInit(): void {
    this.amount.set(this.amountKopecks);
    void this.load();
  }

  ngOnDestroy(): void {
    this.closed = true;
  }

  async load(): Promise<void> {
    const run = ++this.loadRun;
    this.loading.set(true);
    this.loadError.set('');
    try {
      const loaded = await this.loadRecipients();
      if (run !== this.loadRun || this.closed) {
        return;
      }
      this.recipients.set(loaded.recipients ?? []);
      if (loaded.amountKopecks != null) {
        this.amount.set(loaded.amountKopecks);
      }
    } catch (error) {
      if (run !== this.loadRun || this.closed) {
        return;
      }
      this.recipients.set([]);
      this.loadError.set(this.errorText(error, 'Не удалось загрузить сотрудников'));
    } finally {
      if (run === this.loadRun) {
        this.loading.set(false);
      }
    }
  }

  select(profileId: number | null): void {
    if (!this.saving()) {
      this.selected.set(profileId);
    }
  }

  close(): void {
    if (this.saving() || this.closed) {
      return;
    }
    this.closed = true;
    void this.modalController.dismiss(null, 'cancel');
  }

  /** Pressing the button is also the confirmation that the client has not paid yet. */
  async submit(): Promise<void> {
    const profileId = this.selected();
    if (this.saving() || this.closed || !mobilePaymentRecipientChoiceAllowed(this.recipients(), profileId)) {
      return;
    }
    this.saving.set(true);
    this.submitError.set('');
    let outcome: MobilePaymentRecipientIssueOutcome;
    try {
      outcome = await this.issue({ profileId });
    } catch (error) {
      outcome = { status: 'refused', message: this.errorText(error, 'Не удалось выдать номер карты') };
    }
    if (this.closed) {
      return;
    }
    if (outcome.status === 'refused') {
      // The window stays open with the refusal, so another person can be chosen at once.
      this.saving.set(false);
      this.submitError.set(outcome.message);
      return;
    }
    this.closed = true;
    await this.modalController.dismiss(outcome, outcome.status === 'left' ? 'cancel' : 'issued');
  }

  private errorText(error: unknown, fallback: string): string {
    return error instanceof Error && error.message.trim() ? error.message.trim() : fallback;
  }
}
