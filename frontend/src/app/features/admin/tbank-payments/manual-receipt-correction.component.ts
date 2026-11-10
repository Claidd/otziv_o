import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DecimalPipe } from '@angular/common';
import { firstValueFrom } from 'rxjs';
import { AuthService } from '../../../core/auth.service';
import { PaymentsApi, ManualReceiptEntry, ManualReceiptCorrection } from '../../../core/payments.api';
import { apiErrorDetail } from '../../../shared/api-error-message';

@Component({
  selector: 'app-manual-receipt-correction',
  imports: [FormsModule, DecimalPipe],
  templateUrl: './manual-receipt-correction.component.html',
  styleUrl: './specialist-payment-reviews.component.scss'
})
export class ManualReceiptCorrectionComponent {
  readonly auth = inject(AuthService);
  private readonly api = inject(PaymentsApi);
  readonly entries = signal<ManualReceiptEntry[] | null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly editing = signal<ManualReceiptEntry | null>(null);
  readonly recipients = signal<{id: number; name: string}[]>([]);
  kind = 'ORDER';
  target: number | null = null;
  action: ManualReceiptCorrection['action'] = 'REASSIGN';
  recipient = '';
  reason = '';
  confirmed = false;
  private key = '';

  async search(): Promise<void> {
    if (!this.target || this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    this.entries.set(null);
    this.editing.set(null);
    try {
      this.entries.set(await firstValueFrom(this.api.getManualReceipts(this.target, this.kind === 'INVOICE')));
    } catch (e) { this.error.set(apiErrorDetail(e, 'Не удалось получить ручные поступления')); }
    finally { this.busy.set(false); }
  }

  async edit(row: ManualReceiptEntry): Promise<void> {
    this.reason = '';
    this.recipient = '';
    this.action = 'REASSIGN';
    this.confirmed = false;
    this.key = crypto.randomUUID();
    this.busy.set(true);
    this.error.set('');
    try {
      this.recipients.set(await firstValueFrom(this.api.getCorrectionRecipients()));
      this.editing.set(row);
    } catch (e) { this.error.set(apiErrorDetail(e, 'Не удалось получить список получателей')); }
    finally { this.busy.set(false); }
  }

  async save(): Promise<void> {
    const row = this.editing();
    if (!row || !this.confirmed || !this.reason.trim() || this.busy() || (this.action === 'REASSIGN' && !this.recipient)) return;
    this.busy.set(true);
    this.error.set('');
    try {
      const reassign = this.action === 'REASSIGN';
      await firstValueFrom(this.api.correctManualReceipt(row.id, {
        action: this.action, requestKey: this.key, reason: this.reason.trim(),
        owner: reassign && this.recipient === 'OWNER',
        recipientProfileId: reassign && this.recipient !== 'OWNER' ? Number(this.recipient) : null
      }));
      this.editing.set(null);
      this.entries.set(await firstValueFrom(this.api.getManualReceipts(row.invoiceId ?? row.orderId!, row.invoiceId !== null)));
    } catch (e) { this.error.set(apiErrorDetail(e, 'Не удалось провести исправление')); }
    finally { this.busy.set(false); }
  }
}
