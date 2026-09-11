(function () {
  'use strict';
  var panel = document.getElementById('app-startup');
  if (!panel) return;
  var title = document.getElementById('app-startup-title');
  var message = document.getElementById('app-startup-message');
  var retry = document.getElementById('app-startup-retry');
  var finished = false;
  var timer = window.setTimeout(function () {
    showIssue('Загрузка занимает больше времени');
  }, 15000);
  function showIssue(heading) {
    if (finished) return;
    window.clearTimeout(timer);
    title.textContent = heading;
    message.textContent = 'Проверьте подключение к интернету и попробуйте обновить страницу.';
    retry.hidden = false;
    panel.setAttribute('data-state', 'error');
  }
  function failed() { showIssue('Не удалось загрузить страницу'); }
  function resourceFailed(event) {
    if (event.target && event.target.tagName === 'SCRIPT') failed();
  }
  function ready() {
    if (finished) return;
    finished = true;
    window.clearTimeout(timer);
    window.removeEventListener('otziv:startup-ready', ready);
    window.removeEventListener('otziv:startup-error', failed);
    window.removeEventListener('error', resourceFailed, true);
    panel.parentNode.removeChild(panel);
  }
  retry.addEventListener('click', function () { window.location.reload(); });
  window.addEventListener('otziv:startup-ready', ready);
  window.addEventListener('otziv:startup-error', failed);
  window.addEventListener('error', resourceFailed, true);
}());
