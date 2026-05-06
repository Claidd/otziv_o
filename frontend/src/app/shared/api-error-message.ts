const DEFAULT_ERROR_DETAIL = 'Попробуйте обновить данные или повторить действие позже.';

export function apiErrorMessage(err: unknown, fallback: string): string {
  const serverMessage = extractUserMessage(err);
  if (serverMessage) {
    return serverMessage;
  }

  const statusDetail = statusErrorDetail(err);
  return statusDetail ? joinFallbackAndDetail(fallback, statusDetail) : (normalizeText(fallback) ?? DEFAULT_ERROR_DETAIL);
}

export function apiErrorDetail(err: unknown, fallback = DEFAULT_ERROR_DETAIL): string {
  const serverMessage = extractUserMessage(err);
  if (serverMessage) {
    return serverMessage;
  }

  const statusDetail = statusErrorDetail(err);
  if (statusDetail) {
    return statusDetail;
  }

  if (typeof err === 'string') {
    return sanitizeErrorText(err, fallback) ?? fallback;
  }

  return fallback;
}

export function sanitizeErrorText(message: string | undefined, fallback = DEFAULT_ERROR_DETAIL): string | undefined {
  const text = normalizeText(message);
  if (!text) {
    return undefined;
  }

  return isTechnicalErrorText(text) ? fallback : text;
}

function statusErrorDetail(err: unknown): string | null {
  const status = httpStatus(err);
  if (status === 0) {
    return 'Сервер не отвечает. Проверьте, что серверная часть запущена, и попробуйте снова.';
  }

  if (status === 401) {
    return 'Сессия закончилась. Войдите в систему заново.';
  }

  if (status === 403) {
    return 'У вас нет доступа к этому действию.';
  }

  if (status === 404) {
    return 'Данные не найдены. Обновите страницу и попробуйте снова.';
  }

  if (status === 405) {
    return 'Сервер не принимает этот запрос. Обновите backend и страницу, затем попробуйте снова.';
  }

  if (status === 409) {
    return 'Данные уже изменились. Обновите страницу и повторите действие.';
  }

  if (status >= 500) {
    return 'На сервере произошла ошибка. Обновите данные через пару минут. Если ошибка повторится, сообщите администратору.';
  }

  if (status >= 400) {
    return 'Запрос не принят сервером. Проверьте данные и попробуйте еще раз.';
  }

  return null;
}

function extractUserMessage(err: unknown): string | null {
  if (typeof err === 'string') {
    return userFacingText(err);
  }

  if (typeof err !== 'object' || err === null) {
    return null;
  }

  const response = err as { error?: unknown; message?: unknown };
  const candidates = [...messageCandidates(response.error), response.message];

  for (const candidate of candidates) {
    if (typeof candidate !== 'string') {
      continue;
    }

    const text = userFacingText(candidate);
    if (text) {
      return text;
    }
  }

  return null;
}

function messageCandidates(value: unknown): unknown[] {
  if (typeof value === 'string') {
    return [value];
  }

  if (typeof value !== 'object' || value === null) {
    return [];
  }

  const payload = value as Record<string, unknown>;
  const candidates = [payload['detail'], payload['message'], payload['title']];
  const errors = payload['errors'];

  if (Array.isArray(errors)) {
    candidates.push(...errors);
  }

  return candidates;
}

function httpStatus(err: unknown): number {
  if (typeof err === 'object' && err !== null && 'status' in err) {
    const status = (err as { status?: unknown }).status;
    if (typeof status === 'number') {
      return status;
    }
  }

  return statusFromText(err) ?? -1;
}

function statusFromText(value: unknown): number | null {
  if (typeof value === 'string') {
    return parseStatus(value);
  }

  if (typeof value !== 'object' || value === null) {
    return null;
  }

  const response = value as { error?: unknown; message?: unknown };
  return parseStatus(response.message) ?? parseStatus(response.error);
}

function parseStatus(value: unknown): number | null {
  if (typeof value !== 'string') {
    return null;
  }

  const match = value.match(/\b([45]\d{2})\b/);
  return match ? Number(match[1]) : null;
}

function joinFallbackAndDetail(fallback: string, detail: string): string {
  const cleanFallback = normalizeText(fallback) ?? '';
  const cleanDetail = normalizeText(detail) ?? '';

  if (!cleanFallback) {
    return cleanDetail;
  }

  if (!cleanDetail || cleanDetail === cleanFallback) {
    return cleanFallback;
  }

  return `${cleanFallback.replace(/[.!?]+$/, '')}. ${cleanDetail}`;
}

function normalizeText(value: string | undefined): string | null {
  const text = value?.replace(/\s+/g, ' ').trim();
  return text ? text : null;
}

function userFacingText(value: string | undefined): string | null {
  const text = normalizeText(value);
  if (!text || isTechnicalErrorText(text)) {
    return null;
  }

  return text;
}

function isTechnicalErrorText(value: string): boolean {
  return [
    /Http failure response/i,
    /https?:\/\//i,
    /localhost:\d+/i,
    /\/api\//i,
    /No static resource/i,
    /access-denied/i,
    /Internal Server Error/i,
    /^<!doctype/i,
    /^<html/i,
    /\b[A-Za-z]+Exception\b/,
    /\borg\.springframework\b/i,
    /\bjava\./i,
    /\bTraceback\b/i
  ].some((pattern) => pattern.test(value));
}
