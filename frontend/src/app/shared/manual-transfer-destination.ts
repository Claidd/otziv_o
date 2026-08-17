export type ManualTransferDestinationKind = 'PHONE' | 'CARD';

export interface ManualTransferDestinationPresentation {
  kind: ManualTransferDestinationKind;
  fieldLabel: string;
  paymentTitle: string;
}

const TRANSFER_FORMATTING = /[\p{White_Space}\p{Dash_Punctuation}()]/gu;

export function normalizeManualTransferDestination(value: string | null | undefined): string {
  return (value ?? '').replace(TRANSFER_FORMATTING, '');
}

export function isManualTransferCard(value: string | null | undefined): boolean {
  const normalized = normalizeManualTransferDestination(value);
  return /^[0-9]{16,19}$/.test(normalized);
}

export function manualTransferDestinationPresentation(
  value: string | null | undefined
): ManualTransferDestinationPresentation {
  return isManualTransferCard(value)
    ? {
        kind: 'CARD',
        fieldLabel: 'Номер карты',
        paymentTitle: 'Оплата по номеру карты'
      }
    : {
        kind: 'PHONE',
        fieldLabel: 'Номер телефона',
        paymentTitle: 'Оплата через мобильный банк'
      };
}
