const DEFAULT_DEEP_REPORT_ERROR = 'Попробуйте запустить отчёт ещё раз.';

export function deepReportErrorMessage(
  message: string | null | undefined,
  fallback = DEFAULT_DEEP_REPORT_ERROR
): string {
  const text = (message ?? '').replace(/\s+/g, ' ').trim();
  if (!text) {
    return fallback;
  }

  const lower = text.toLowerCase();
  if (lower.includes('insufficient_quota')
    || lower.includes('exceeded your current quota')
    || lower.includes('check your plan and billing')
    || lower.includes('credit balance')
    || lower.includes('credits')
    || lower.includes('spend limit')
    || lower.includes('hard limit')
    || lower.includes('billing')
    || (lower.includes('законч') && lower.includes('ден'))
    || (lower.includes('недостат') && lower.includes('баланс'))
    || (lower.includes('квот') && lower.includes('исчерп'))) {
    return 'OpenAI не запустил отчёт из-за оплаты или квоты. Проверьте баланс, лимиты и billing проекта в OpenAI, затем запустите отчёт повторно.';
  }

  if (lower.includes('rate limit reached')
    || lower.includes('tokens per min')
    || lower.includes('rate_limit_exceeded')
    || lower.includes('лимит токен')) {
    const retry = text.match(/try again in ([0-9.]+)s/i)?.[1]
      ?? text.match(/через\s+([0-9.]+)\s*с/i)?.[1];
    const retryHint = retry ? ` API просит повторить примерно через ${retry} с.` : '';
    return `OpenAI временно упёрся в лимит токенов в минуту.${retryHint} Подождите 1-2 минуты и запустите отчёт снова.`;
  }

  if (lower.includes('http 407') || lower.includes('proxy authentication required')) {
    return 'VPS-прокси не пустил запрос к OpenAI. Проверьте allowlist IP в Squid/UFW и отсутствие proxy_auth для этого маршрута.';
  }

  if (lower.includes('unsupported_country_region_territory')
    || lower.includes('country, region, or territory not supported')
    || lower.includes('неподдерживаемого региона')) {
    return 'OpenAI отклонил запрос из неподдерживаемого региона. Проверьте, что включён OpenAI proxy и приложение идёт через VPS.';
  }

  if (lower.includes('api-ключ') || lower.includes('api key') || lower.includes('unauthorized')) {
    return 'OpenAI не принял API-ключ. Проверьте OPENAI_API_KEY: ключ мог быть удалён, неверно скопирован или недоступен для этого проекта.';
  }

  if (lower.includes('оборвался на сетевом уровне')
    || lower.includes('connection refused')
    || lower.includes('connection reset')
    || lower.includes('connection closed')
    || lower.includes('timed out')
    || lower.includes('timeout')) {
    return 'Запрос к OpenAI не прошёл по сети или таймауту. Проверьте соединение, proxy/VPN и повторите запуск отчёта.';
  }

  if (lower.includes('очеред') && lower.includes('фонов')) {
    return 'Сервер не смог поставить отчёт в фоновую очередь. Освободите очередь задач или перезапустите backend, затем запустите отчёт вручную.';
  }

  if (lower.includes('поврежд') && lower.includes('json')) {
    return 'Модель вернула повреждённый JSON отчёта, восстановить структуру не удалось. Запустите отчёт повторно.';
  }

  return text;
}
