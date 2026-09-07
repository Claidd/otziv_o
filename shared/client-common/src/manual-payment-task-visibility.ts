export type ManualPaymentTaskVisibilityItem = {
  status?: string | null;
};

export function manualPaymentTaskWorklist<T extends ManualPaymentTaskVisibilityItem>(
  tasks: readonly T[] | null | undefined
): T[] {
  return (tasks ?? []).filter((task) => task.status !== 'CANCELED');
}
