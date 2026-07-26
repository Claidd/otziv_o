const DEFAULT_ERROR_DETAIL = 'Попробуйте обновить данные или повторить действие позже.';

export function apiErrorMessage(err: unknown, fallback: string): string {
  const statusDetail = statusErrorDetail(err);
  const serverMessage = extractUserMessage(err);
  if (serverMessage) {
    return serverMessage;
  }

  if (isServerError(err) && statusDetail) {
    return joinFallbackAndDetail(fallback, statusDetail);
  }

  return statusDetail ? joinFallbackAndDetail(fallback, statusDetail) : (normalizeText(fallback) ?? DEFAULT_ERROR_DETAIL);
}

export function apiErrorDetail(err: unknown, fallback = DEFAULT_ERROR_DETAIL): string {
  const statusDetail = statusErrorDetail(err);
  const serverMessage = extractUserMessage(err);
  if (serverMessage) {
    return serverMessage;
  }

  if (isServerError(err) && statusDetail) {
    return statusDetail;
  }

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
    return 'Ошибка: сервер не отвечает. Как исправить: проверьте интернет-соединение, подождите минуту и повторите действие.';
  }

  if (status === 401) {
    return 'Ошибка: сессия закончилась. Как исправить: войдите в систему заново и повторите действие.';
  }

  if (status === 403) {
    const endpoint = apiEndpoint(err);
    return endpoint
      ? `API ${endpoint} отклонил запрос (403) после обновления сессии. Проверьте назначенного менеджера и права вашей роли.`
      : 'API отклонил запрос (403) после обновления сессии. Проверьте назначенного менеджера и права вашей роли.';
  }

  if (status === 404) {
    return 'Ошибка: нужные данные не найдены или уже удалены. Как исправить: обновите страницу, выберите данные заново и повторите действие.';
  }

  if (status === 405) {
    return 'Ошибка: версия страницы не совпадает с версией сервера. Как исправить: полностью обновите страницу и повторите действие.';
  }

  if (status === 409) {
    return 'Ошибка: данные уже изменил другой пользователь или такая запись существует. Как исправить: обновите страницу, проверьте актуальные данные и повторите действие.';
  }

  if (status >= 500) {
    return 'Ошибка: на сервере произошёл внутренний сбой. Как исправить: повторите действие через минуту; если ошибка сохранится, сообщите администратору время попытки.';
  }

  if (status >= 400) {
    return 'Ошибка: сервер не принял введённые данные. Как исправить: проверьте заполненные поля и повторите действие.';
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

function apiEndpoint(err: unknown): string | null {
  if (typeof err !== 'object' || err === null || !('url' in err)) {
    return null;
  }
  const rawUrl = (err as { url?: unknown }).url;
  if (typeof rawUrl !== 'string' || !rawUrl.trim()) {
    return null;
  }
  try {
    const parsed = new URL(rawUrl, window.location.origin);
    return parsed.pathname.startsWith('/api/') ? parsed.pathname : null;
  } catch {
    const path = rawUrl.split('?')[0]?.trim();
    return path?.startsWith('/api/') ? path : null;
  }
}

function isServerError(err: unknown): boolean {
  return httpStatus(err) >= 500;
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
    /localhost:\d+/i,
    /\/api\//i,
    /No static resource/i,
    /access-denied/i,
    /У вас нет доступа/i,
    /Internal Server Error/i,
    /^<!doctype/i,
    /^<html/i,
    /\b[A-Za-z]+Exception\b/,
    /\borg\.springframework\b/i,
    /\bjava\./i,
    /\bTraceback\b/i
  ].some((pattern) => pattern.test(value));
}
