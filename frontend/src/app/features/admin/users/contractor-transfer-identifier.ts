import {
  isManualTransferCard,
  normalizeManualTransferDestination
} from '../../../shared/manual-transfer-destination';

export type ContractorTransferIdentifierKind = 'EMPTY' | 'PHONE' | 'CARD';

export interface ContractorTransferIdentifierValidation {
  valid: boolean;
  kind: ContractorTransferIdentifierKind;
  normalizedValue: string;
  error: string | null;
}

export function validateContractorTransferIdentifier(
  value: string | null | undefined,
  required = false
): ContractorTransferIdentifierValidation {
  const rawValue = value ?? '';
  const normalizedValue = normalizeManualTransferDestination(rawValue);
  if (!rawValue.trim()) {
    return required
      ? invalid('EMPTY', normalizedValue, 'Укажите номер телефона или карты получателя.')
      : valid('EMPTY', normalizedValue);
  }

  const phoneDigits = normalizedValue.startsWith('+') ? normalizedValue.slice(1) : normalizedValue;
  if (/^[0-9]{10,15}$/.test(phoneDigits)
      && (!normalizedValue.startsWith('+') || normalizedValue === `+${phoneDigits}`)) {
    return valid('PHONE', normalizedValue);
  }
  if (isManualTransferCard(normalizedValue)) {
    return valid('CARD', normalizedValue);
  }

  return invalid(
    phoneDigits.length >= 16 ? 'CARD' : 'PHONE',
    normalizedValue,
    'Введите телефон из 10–15 цифр или номер карты из 16–19 цифр.'
  );
}

export function validateContractorTransferIdentifierForSave(
  value: string | null | undefined,
  validationRequired: boolean
): ContractorTransferIdentifierValidation {
  if (validationRequired) {
    return validateContractorTransferIdentifier(value, true);
  }
  const unchangedValue = value ?? '';
  const kind: ContractorTransferIdentifierKind = !unchangedValue.trim()
    ? 'EMPTY'
    : isManualTransferCard(unchangedValue) ? 'CARD' : 'PHONE';
  return valid(kind, unchangedValue);
}

function valid(
  kind: ContractorTransferIdentifierKind,
  normalizedValue: string
): ContractorTransferIdentifierValidation {
  return { valid: true, kind, normalizedValue, error: null };
}

function invalid(
  kind: ContractorTransferIdentifierKind,
  normalizedValue: string,
  error: string
): ContractorTransferIdentifierValidation {
  return { valid: false, kind, normalizedValue, error };
}
