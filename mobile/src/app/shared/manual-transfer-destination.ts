export type ManualTransferDestinationKind = 'PHONE' | 'CARD';

export interface ManualTransferDestinationPresentation {
  kind: ManualTransferDestinationKind;
  fieldLabel: string;
  copyLabel: string;
  paymentTitle: string;
}

const TRANSFER_FORMATTING = /[\p{White_Space}\p{Dash_Punctuation}()]/gu;

export function normalizeManualTransferDestination(value: string | null | undefined): string {
  return (value ?? '').replace(TRANSFER_FORMATTING, '');
}

export function manualTransferDestinationPresentation(
  value: string | null | undefined
): ManualTransferDestinationPresentation {
  const normalized = normalizeManualTransferDestination(value);
  const isCard = /^[0-9]{16,19}$/.test(normalized);
  return isCard
    ? {
        kind: 'CARD',
        fieldLabel: 'Номер карты',
        copyLabel: 'Скопировать карту',
        paymentTitle: 'Оплата по номеру карты'
      }
    : {
        kind: 'PHONE',
        fieldLabel: 'Номер телефона',
        copyLabel: 'Скопировать телефон',
        paymentTitle: 'Оплата через мобильный банк'
      };
}
