(function () {
    'use strict';
    const marker = document.querySelector('[data-worker-account-action-cooldown]');
    if (!marker) return;

    const endpoint = '/ordersDetails/account-action-cooldown';
    const changeKey = 'otziv-worker-account-action-changed';
    const buttons = 'form[onsubmit*="changeBot"] button[type="submit"], form[onsubmit*="deActivateBot"] button[type="submit"]';
    let enabled = true;
    let durationSeconds = 60;
    let ready = false;
    let pending = false;
    let deadline = 0;
    let epoch = 0;
    let syncSequence = 0;
    let ticker = null;
    let lastSyncAt = Date.now();
    const originalButtons = new WeakMap();
    const toast = document.createElement('aside');
    toast.className = 'worker-account-action-cooldown-toast';
    toast.setAttribute('aria-label', 'Пауза между сменой и блокировкой аккаунтов');
    toast.hidden = true;
    const title = document.createElement('strong');
    const detail = document.createElement('span');
    detail.textContent = 'Можно продолжать работу на других страницах';
    toast.append(title, detail);
    document.body.appendChild(toast);

    function secondsLeft() {
        return enabled ? Math.max(0, Math.ceil((deadline - performance.now()) / 1000)) : 0;
    }

    function locked() {
        return pending || !ready || secondsLeft() > 0;
    }

    function render() {
        const seconds = secondsLeft();
        const blocked = locked();
        const message = !ready ? 'Проверяем доступность действий…'
            : pending ? 'Выполняем действие с аккаунтом…'
                : 'Смена и блокировка доступны через '
                    + String(Math.floor(seconds / 60)).padStart(2, '0') + ':'
                    + String(seconds % 60).padStart(2, '0');
        toast.hidden = !blocked;
        title.textContent = message;
        document.querySelectorAll(buttons).forEach(function (button) {
            if (blocked) {
                if (!originalButtons.has(button)) {
                    originalButtons.set(button, { disabled: button.disabled, title: button.getAttribute('title') });
                }
                button.disabled = true;
                button.title = message;
            } else if (originalButtons.has(button)) {
                const original = originalButtons.get(button);
                button.disabled = original.disabled;
                if (original.title === null) button.removeAttribute('title');
                else button.setAttribute('title', original.title);
                originalButtons.delete(button);
            }
        });
        if (seconds > 0 && ticker === null) ticker = window.setInterval(render, 250);
        if (seconds === 0 && ticker !== null) {
            window.clearInterval(ticker);
            ticker = null;
        }
        if (seconds > 0 && !pending && document.visibilityState === 'visible' && Date.now() - lastSyncAt >= 30_000) {
            void sync();
        }
    }

    function apply(state) {
        if (!state || typeof state.enabled !== 'boolean') return false;
        const now = Date.parse(state.serverNow);
        const available = state.availableAt ? Date.parse(state.availableAt) : now;
        if (!Number.isFinite(now) || !Number.isFinite(available)) return false;
        enabled = state.enabled;
        if (Number.isInteger(state.durationSeconds) && state.durationSeconds >= 0 && state.durationSeconds <= 3600) {
            durationSeconds = state.durationSeconds;
        }
        deadline = enabled ? performance.now() + Math.max(0, available - now) : 0;
        ready = true;
        render();
        return true;
    }

    function notifyOtherTabs() {
        try { window.localStorage.setItem(changeKey, Date.now() + ':' + Math.random()); } catch (_) { /* Storage is optional. */ }
    }

    async function sync() {
        lastSyncAt = Date.now();
        const requestEpoch = epoch;
        const sequence = ++syncSequence;
        try {
            const response = await fetch(endpoint, { credentials: 'same-origin', cache: 'no-store' });
            if (!response.ok) return;
            const state = await response.json();
            if (requestEpoch === epoch && sequence === syncSequence && !pending) apply(state);
        } catch (_) {
            // The mutation endpoint remains authoritative when status cannot be loaded.
        } finally {
            if (requestEpoch === epoch && sequence === syncSequence) {
                ready = true;
                render();
            }
        }
    }

    window.workerAccountActionCooldown = {
        locked: locked,
        begin: function () {
            if (locked()) return false;
            epoch++;
            pending = true;
            if (enabled) deadline = performance.now() + durationSeconds * 1000;
            render();
            return true;
        },
        response: function (response) {
            const prefix = 'X-Worker-Account-Action-';
            const isEnabled = response.headers.get(prefix + 'Enabled');
            if (isEnabled !== null && apply({
                enabled: isEnabled === 'true',
                durationSeconds: Number(response.headers.get(prefix + 'Duration-Seconds')),
                serverNow: response.headers.get(prefix + 'Server-Now'),
                availableAt: response.headers.get(prefix + 'Available-At')
            })) {
                epoch++;
                notifyOtherTabs();
            }
        },
        finish: function () {
            pending = false;
            render();
            void sync();
        }
    };

    // Fragment replacement creates new buttons; only watch DOM additions, not attributes changed above.
    new MutationObserver(function (records) {
        if (records.some(function (record) {
            return record.target !== title && record.target !== detail
                && Array.from(record.addedNodes).some(function (node) { return node.nodeType === 1; });
        })) render();
    }).observe(document.body, { childList: true, subtree: true });
    window.addEventListener('storage', function (event) {
        if (event.key === changeKey) void sync();
    });
    window.addEventListener('pageshow', function () { void sync(); });
    document.addEventListener('visibilitychange', function () {
        if (document.visibilityState === 'visible') { render(); void sync(); }
    });
    render();
    void sync();
}());
