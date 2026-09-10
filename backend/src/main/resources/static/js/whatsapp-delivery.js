(() => {
  const element = document.getElementById('whatsapp-delivery-status');
  if (!element) return;
  const pending = state => ['QUEUED', 'SENDING', 'RETRYABLE'].includes(state);
  const messages = { QUEUED: 'Сообщение сохранено в очереди.', SENDING: 'Сообщение отправляется. Можно продолжать работу.',
    RETRYABLE: 'Отправка отложена. Система повторит попытку автоматически.', SENT: 'Отправка подтверждена.',
    UNKNOWN: 'Исход отправки уточняется. Повторная рассылка заблокирована.', FAILED: 'Сообщение не отправлено. Требуется проверка.' };
  const controller = new AbortController();
  let timer, reads = 0;
  const stop = () => { clearTimeout(timer); controller.abort(); };
  window.addEventListener('pagehide', stop, { once: true });
  const read = async () => {
    try {
      const response = await fetch(element.dataset.statusUrl, { credentials: 'same-origin', signal: controller.signal, headers: { Accept: 'application/json' } });
      if (!response.ok) return;
      const operation = await response.json();
      if (controller.signal.aborted) return;
      element.textContent = messages[operation.status] || 'Статус отправки требует проверки.';
      if (pending(operation.status) && ++reads < 60) timer = setTimeout(read, 3000);
    } catch { /* A failed read does not authorize another message. */ }
  };
  if (pending(element.dataset.state)) timer = setTimeout(read, 2000);
})();
