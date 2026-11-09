export interface ManualTransferLine {
  orderId: number;
  amountKopecks: number;
}

export interface ManualTransferRequest {
  bankReference: string;
  recipientProfileId: number;
  amountKopecks: number;
  orders: ManualTransferLine[];
  reason: string;
  receiptUrl: string | null;
}

export function prepareManualTransfer(
  orders: ManualTransferLine[], bankReference: string, rubles: string, recipientProfileId: number | null | undefined,
  reason: string, receiptUrl: string | null
): ManualTransferRequest {
  if (!recipientProfileId || !bankReference.trim()) throw new Error('Укажите получателя и номер операции из выписки.');
  const parts = /^(\d+)(?:[.,](\d{1,2}))?$/.exec(rubles.trim());
  if (!parts) throw new Error('Укажите сумму перевода в рублях с точностью до копейки.');
  const amountKopecks = Number(parts[1]) * 100 + Number((parts[2] ?? '').padEnd(2, '0'));
  if (!Number.isSafeInteger(amountKopecks) || amountKopecks <= 0 || orders.reduce((sum, item) => sum + item.amountKopecks, 0) !== amountKopecks)
    throw new Error('Сумма распределения по заказам должна совпадать с суммой операции в выписке.');
  return { orders, amountKopecks, bankReference: bankReference.trim(), recipientProfileId, reason, receiptUrl };
}
