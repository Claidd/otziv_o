export function isExplicitlyRepairableCommonInvoiceReason(
  reason: string | null | undefined
): boolean {
  const normalized = (reason ?? '').trim().toLowerCase();
  return normalized.includes('нажмите «починить»') || normalized.includes('нажмите "починить"');
}
