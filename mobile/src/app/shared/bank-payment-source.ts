import { PaymentInstructionSource } from '../core/api.service';

const BANK_LINK_SOURCES = new Set(['BANK_LINK', 'TBANK_LINK', 'TOCHKA_LINK']);

export function isBankPaymentInstructionSource(source?: string | null): boolean {
  return BANK_LINK_SOURCES.has((source ?? '').trim().toUpperCase());
}

export function paymentInstructionSourceFormValue(source?: string | null): 'MANAGER_TEXT' | 'BANK_LINK' {
  return isBankPaymentInstructionSource(source) ? 'BANK_LINK' : 'MANAGER_TEXT';
}

export function normalizePaymentInstructionSource(source?: string | null): PaymentInstructionSource {
  return isBankPaymentInstructionSource(source) ? 'BANK_LINK' : 'MANAGER_TEXT';
}

export function isBankPaymentRoute(route?: string | null): boolean {
  const normalized = (route ?? '').trim().toUpperCase();
  return !normalized || BANK_LINK_SOURCES.has(normalized);
}
