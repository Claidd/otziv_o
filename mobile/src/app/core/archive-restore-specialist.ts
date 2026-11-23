/** One specialist the server accepts for an archived order whose own specialist no longer works. */
export interface ArchiveRestoreWorkerOption {
  id: number;
  label: string;
  /** Already linked to the order's company. */
  companyWorker: boolean;
}

/** 409 SPECIALIST_REQUIRED of an archive restore: the person picks one of the workers and restores again. */
export interface ArchiveRestoreSpecialistRequired {
  message: string;
  workers: ArchiveRestoreWorkerOption[];
  /** The automatic rule's pick (transfer history, the company's only specialist, the team's only specialist). */
  suggestedWorkerId: number | null;
}

const SPECIALIST_REQUIRED_FALLBACK = 'Специалист заказа больше не работает — выберите действующего специалиста';

/**
 * Reads a failed restore. Returns the specialist choice for 409 SPECIALIST_REQUIRED and null for any other answer,
 * which the page shows as an ordinary error.
 */
export function archiveRestoreSpecialistRequired(error: unknown): ArchiveRestoreSpecialistRequired | null {
  const response = error as { status?: unknown; error?: unknown } | null;
  if (!response || response.status !== 409 || !response.error || typeof response.error !== 'object') {
    return null;
  }
  const body = response.error as Record<string, unknown>;
  if (String(body['code'] ?? '').toUpperCase() !== 'SPECIALIST_REQUIRED') {
    return null;
  }
  const workers = Array.isArray(body['workers']) ? body['workers'].filter(isWorkerOption) : [];
  const suggested = body['suggestedWorkerId'];
  const suggestedWorkerId =
    typeof suggested === 'number' && Number.isSafeInteger(suggested) && workers.some((worker) => worker.id === suggested)
      ? suggested
      : null;
  const message =
    typeof body['message'] === 'string' && body['message'].trim() ? body['message'].trim() : SPECIALIST_REQUIRED_FALLBACK;
  return { message, workers, suggestedWorkerId };
}

function isWorkerOption(value: unknown): value is ArchiveRestoreWorkerOption {
  if (!value || typeof value !== 'object') {
    return false;
  }
  const option = value as Record<string, unknown>;
  return typeof option['id'] === 'number' && Number.isSafeInteger(option['id']) && option['id'] > 0
    && typeof option['label'] === 'string'
    && (option['companyWorker'] === undefined || typeof option['companyWorker'] === 'boolean');
}
