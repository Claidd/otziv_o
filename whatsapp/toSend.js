const userAgents = [
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.6312.107 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.128 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.6167.85 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.224 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.6045.163 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.5993.145 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.5938.132 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.5845.98 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.5790.171 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.5735.198 Safari/537.36"
];

function getDesktopEmulationProfile(userAgent) {
    const profiles = [
        {
            regex: /Chrome\/123\.0\./i,
            viewport: { width: 1366, height: 768, deviceScaleFactor: 1 },
            platform: 'Win32',
            renderer: 'ANGLE (NVIDIA GeForce GTX 1650 Direct3D11 vs_5_0 ps_5_0)',
            vendor: 'Google Inc.'
        },
        {
            regex: /Chrome\/122\.0\./i,
            viewport: { width: 1440, height: 900, deviceScaleFactor: 1 },
            platform: 'Win32',
            renderer: 'ANGLE (Intel UHD Graphics 620 Direct3D11 vs_5_0 ps_5_0)',
            vendor: 'Google Inc.'
        },
        {
            regex: /Chrome\/121\.0\./i,
            viewport: { width: 1920, height: 1080, deviceScaleFactor: 1 },
            platform: 'Win32',
            renderer: 'ANGLE (AMD Radeon RX 580 Direct3D11 vs_5_0 ps_5_0)',
            vendor: 'Google Inc.'
        },
        {
            regex: /Chrome\/120\.0\./i,
            viewport: { width: 1600, height: 900, deviceScaleFactor: 1 },
            platform: 'Win32',
            renderer: 'ANGLE (NVIDIA Quadro T1000 Direct3D11 vs_5_0 ps_5_0)',
            vendor: 'Google Inc.'
        },
        {
            regex: /Chrome\/119\.0\./i,
            viewport: { width: 1280, height: 720, deviceScaleFactor: 1 },
            platform: 'Win32',
            renderer: 'ANGLE (Intel Iris Xe Graphics Direct3D11 vs_5_0 ps_5_0)',
            vendor: 'Google Inc.'
        }
    ];

    for (const profile of profiles) {
        if (profile.regex.test(userAgent)) return profile;
    }
    return {
        viewport: { width: 1366, height: 768, deviceScaleFactor: 1 },
        platform: 'Win32',
        renderer: 'ANGLE (Intel UHD Graphics 620 Direct3D11 vs_5_0 ps_5_0)',
        vendor: 'Google Inc.'
    };
}
const { Client, LocalAuth } = require('whatsapp-web.js');
const puppeteer = require('puppeteer');
const qrcodeTerminal = require('qrcode-terminal');
const qrcode = require('qrcode');
const express = require('express');
const bodyParser = require('body-parser');
const axios = require('axios');
const path = require('path');
const os = require('os');
const fs = require('fs');
const fsExtra = require('fs-extra');

// Локальная папка для скринов (примонтирована через Docker volume)
const localScreenshotDir = '/app/screenshots';
fsExtra.ensureDirSync(localScreenshotDir);


const proxyArg = process.env.PROXY_URL ? [`--proxy-server=${process.env.PROXY_URL}`] : [];
const clientId = process.env.CLIENT_ID || 'default';
const serverUrl = process.env.SERVER_URL || 'http://localhost:8080';
const dataPath = process.env.AUTH_PATH || path.join(os.homedir(), '.wwebjs_auth');
const qrStore = {};
let client;
let globalUserAgent = null;
let lastRestart = 0;

// --- Антидетект (с профилями) ---
async function applyAntiDetect(page) {
    try {
        const profile = getDesktopEmulationProfile(globalUserAgent || userAgents[0]);
        await page.setViewport(profile.viewport);

        await page.evaluateOnNewDocument((vendor, renderer, platform) => {
            Object.defineProperty(navigator, 'webdriver', { get: () => false });
            Object.defineProperty(navigator, 'platform', { get: () => platform });
            const origGetParameter = WebGLRenderingContext.prototype.getParameter;
            WebGLRenderingContext.prototype.getParameter = function (param) {
                if (param === 37445) return vendor;    // UNMASKED_VENDOR_WEBGL
                if (param === 37446) return renderer;  // UNMASKED_RENDERER_WEBGL
                return origGetParameter.call(this, param);
            };
            document.addEventListener('mousemove', () => {}, { once: true });
            document.addEventListener('keydown', () => {}, { once: true });
            navigator.mediaDevices = {
                enumerateDevices: async () => ([
                    { kind: "audioinput", label: "Микрофон", deviceId: "default" },
                    { kind: "videoinput", label: "Камера", deviceId: "default" }
                ])
            };
        }, profile.vendor, profile.renderer, profile.platform);

        await page.mouse.move(100 + Math.random() * 300, 100 + Math.random() * 300);
        await page.mouse.wheel({ deltaY: 50 + Math.random() * 150 });
        await page.waitForTimeout(2000 + Math.random() * 3000);
    } catch (err) {
        console.error(`[${clientId}] ❌ Ошибка в applyAntiDetect:`, err.message);
    }
}

// --- Безопасная обертка evaluate ---
async function safeEvaluate(page, fn, ...args) {
    try {
        return await page.evaluate(fn, ...args);
    } catch (e) {
        if (e.message.includes('Execution context was destroyed')) {
            throw e; // пусть перезапустится общий обработчик
        }
        throw e;
    }
}

// --- Очистка старых файлов (html/png) ---
function cleanupScreenshots(dir = localScreenshotDir, maxFiles = 500) {
    try {
        const files = fs.readdirSync(dir)
            .map(f => ({
                name: f,
                time: fs.statSync(path.join(dir, f)).mtime.getTime()
            }))
            .sort((a, b) => a.time - b.time);
        if (files.length > maxFiles) {
            const toDelete = files.slice(0, files.length - maxFiles);
            for (const file of toDelete) {
                fs.unlinkSync(path.join(dir, file.name));
            }
            console.log(`[${clientId}] 🧹 Очистка: удалено ${toDelete.length} старых файлов`);
        }
    } catch (err) {
        console.error(`[${clientId}] Ошибка очистки скринов:`, err.message);
    }
}

// --- Перезапуск при крашах ---
process.on('uncaughtException', (err) => {
    if (err.message.includes('Execution context was destroyed')) {
        console.error(`[${clientId}] 💥 Puppeteer краш: ${err.message}`);
        restartClientWithDelay(5000);
    } else {
        console.error(`[${clientId}] ❌ Необработанная ошибка:`, err);
    }
});

function restartClientWithDelay(ms = 5000) {
    const now = Date.now();
    if (now - lastRestart < 60000) {
        console.warn(`[${clientId}] 🚫 Перезапуск отменён — не прошло 60 сек.`);
        return;
    }
    lastRestart = now;
    console.warn(`[${clientId}] 🔁 Перезапуск клиента через ${ms / 1000} сек.`);
    setTimeout(() => {
        try {
            client?.destroy()?.catch(() => {});
        } catch (_) {}
        client = null;
        makeClient(clientId);
    }, ms);
}

// --- Инициализация клиента ---
const makeClient = (id) => {
    if (client) return client;
    const uaPath = path.join(dataPath, `${id}_ua.json`);

    let selectedUserAgent, selectedProfile;
    if (fs.existsSync(uaPath)) {
        const saved = JSON.parse(fs.readFileSync(uaPath, 'utf-8'));
        selectedUserAgent = saved.userAgent;
        globalUserAgent = selectedUserAgent;
        selectedProfile = saved.profile;
        console.log(`[${id}] ✅ Загружен сохранённый User-Agent`);
    } else {
        selectedUserAgent = userAgents[Math.floor(Math.random() * userAgents.length)];
        globalUserAgent = selectedUserAgent;
        selectedProfile = getDesktopEmulationProfile(selectedUserAgent);
        fs.writeFileSync(uaPath, JSON.stringify({ userAgent: selectedUserAgent, profile: selectedProfile }, null, 2));
        console.log(`[${id}] 🆕 Сохранён новый User-Agent`);
    }

    const instance = new Client({
        authStrategy: new LocalAuth({ clientId: id, dataPath }),
        puppeteer: {
            headless: true,
            executablePath: puppeteer.executablePath(),
            timeout: 60000,
            args: [
                '--no-sandbox',
                '--disable-setuid-sandbox',
                `--user-agent=${selectedUserAgent}`,
                ...proxyArg
            ]
        }
    });

    instance.on('browser', async (browser) => {
        console.log(`[${clientId}] 🧠 Браузер подключён`);
        try {
            const pages = await browser.pages();
            const page = pages.length ? pages[0] : await browser.newPage();
            client.pupPage = page;

            await applyAntiDetect(page); // антидетект для первой страницы

            browser.on('targetcreated', async target => {
                const newPage = await target.page();
                if (newPage) {
                    console.log(`[${clientId}] 🕵️ Антидетект для новой вкладки`);
                    await applyAntiDetect(newPage);
                }
            });
        } catch (e) {
            console.error(`[${clientId}] ❌ Ошибка инициализации страницы:`, e.message);
        }
    });

    instance.on('qr', qr => {
        qrStore[id] = qr;
        console.log(`[${id}] QR-код (терминал):`);
        qrcodeTerminal.generate(qr, { small: true });
    });

    instance.on('authenticated', () => console.log(`[${id}] ✅ Авторизация завершена`));
    instance.on('ready', () => console.log(`[${id}] 🔥 Клиент готов`));
    instance.on('disconnected', (reason) => {
        console.warn(`[${id}] ⚠️ Клиент отключён: ${reason}`);
        restartClientWithDelay();
    });
    instance.on('change_state', state => {
        if (state === 'DISCONNECTED') {
            console.warn(`[${id}] ⚠️ Состояние: ${state}`);
            restartClientWithDelay();
        }
    });

    instance.initialize();
    return instance;
};

client = makeClient(clientId);

const app = express();
app.use(bodyParser.json());



// ==== QR-код в браузере ====
app.get('/qr', async (req, res) => {
    const qrData = qrStore[clientId];
    if (!qrData) {
        return res.send(`<html><body>
      <h2>QR-код пока не сгенерирован для клиента ${clientId}</h2>
      <p>Обновите страницу через 5-10 секунд.</p>
    </body></html>`);
    }
    const qrImage = await qrcode.toDataURL(qrData);
    res.send(`<html><body style="text-align:center;">
    <h2>QR-код для клиента ${clientId}</h2>
    <img src="${qrImage}" style="max-width:400px;" />
  </body></html>`);
});

// Логируем исходящие сообщения
app.post('/send', async (req, res) => {
    const { phone, message } = req.body;
    if (!client || !client.info || !client.info.wid) {
        return res.status(503).json({ status: 'error', error: 'Клиент не готов' });
    }

    try {
        const numberId = await client.getNumberId(phone);
        if (!numberId) {
            console.warn(`[${clientId}] ❌ Номер ${phone} не зарегистрирован в WhatsApp`);
            return res.status(400).json({ status: 'not_whatsapp', error: 'Номер не в WhatsApp' });
        }

        console.log(`[${clientId}] ➡️ Отправляю сообщение на ${phone}: "${message}"`);
        await client.sendMessage(numberId._serialized, message);
        console.log(`[${clientId}] ✅ Сообщение успешно отправлено на ${phone}`);
        res.json({ status: 'ok' });

    } catch (e) {
        const errorMessage = e?.message || 'Неизвестная ошибка';
        console.error(`[${clientId}] ❌ Ошибка отправки на ${phone}: ${errorMessage}`);
        res.status(500).json({ status: 'error', error: errorMessage });
    }
});

// Логируем все входящие сообщения
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

client.on('message', async msg => {
    const chat = await msg.getChat();

    // Игнорируем медиа (картинки, видео и т.д.)
    if (msg.type !== 'chat') {
        console.log(`[${clientId}] 📷 Получено медиа сообщение (${msg.type}) от ${msg.from}. Игнорируем.`);
        return;
    }

    const content = msg.body?.trim();
    if (!content) return;

    const from = msg.from.replace('@c.us', '');

    if (chat.isGroup) {
        // Обработка группового сообщения (без задержек и без "прочитано")
        const groupId = chat.id._serialized;
        const senderId = msg.author;
        const senderNumber = senderId?.replace('@c.us', '') || 'unknown';

        console.log(`📨 [${clientId}] Группа: ${chat.name}`);
        console.log(`👤 Отправитель: ${senderNumber}`);
        console.log(`💬 Текст: ${content}`);

        try {
            const response = await axios.post(`${serverUrl}/webhook/whatsapp-group-reply`, {
                clientId,
                groupId,
                groupName: chat.name,
                from: senderNumber,
                message: content
            });
            console.log(`[${clientId}] 📤 Вебхук для группы отправлен: статус ${response.status}`);
        } catch (err) {
            console.error(`[${clientId}] ❌ Ошибка отправки вебхука из группы: ${err.message}`);
            if (err.response) {
                console.error(`[${clientId}] Ответ сервера: ${err.response.status} ${err.response.statusText}`);
                console.error(`[${clientId}] Тело ответа: ${JSON.stringify(err.response.data)}`);
            }
        }

    } else {
        // Обработка личного сообщения (с задержкой и пометкой как прочитанное)
        console.log(`[${clientId}] 📥 Входящее сообщение от ${from}: ${content}`);

        const delayBeforeRead = Math.floor(Math.random() * 25000) + 5000; // 5–30 секунд
        console.log(`[${clientId}] ⏳ Ждём ${delayBeforeRead} мс перед пометкой "прочитано"...`);
        await delay(delayBeforeRead);

        try {
            await chat.sendSeen();
            console.log(`[${clientId}] ✅ Пометили сообщение от ${from} как прочитанное`);
        } catch (err) {
            console.error(`[${clientId}] ❌ Не удалось пометить как прочитанное: ${err.message}`);
        }

        try {
            const response = await axios.post(`${serverUrl}/webhook/whatsapp-reply`, {
                clientId,
                from,
                message: content
            });
            console.log(`[${clientId}] 📤 Вебхук отправлен: статус ${response.status}`);
        } catch (err) {
            console.error(`[${clientId}] ❌ Ошибка при отправке вебхука: ${err.message}`);
            if (err.response) {
                console.error(`[${clientId}] Ответ сервера: ${err.response.status} ${err.response.statusText}`);
                console.error(`[${clientId}] Тело ответа: ${JSON.stringify(err.response.data)}`);
            }
        }
    }
});




app.post('/send-group', async (req, res) => {
    const { groupId, message } = req.body;

    if (!groupId || !message) {
        return res.status(400).json({ status: 'error', error: 'groupId и message обязательны' });
    }
    if (!/^\d+@g\.us$/.test(groupId)) {
        return res.status(400).json({ status: 'error', error: 'Некорректный формат groupId' });
    }

    let attempts = 0;
    let success = false;
    let lastError = '';

    while (attempts < 3 && !success) {
        attempts++;

        if (!client || !client.info) {
            console.warn(`[${clientId}] 🚫 Клиент не готов (попытка ${attempts}/3) — жду 5 сек и повторяю...`);
            await delay(5000);
            continue;
        }

        try {
            let chat;
            try {
                chat = await client.getChatById(groupId);
            } catch (e) {
                console.warn(`[${clientId}] ⚠️ Чат ${groupId} не найден напрямую, пробую загрузить все чаты`);
            }

            if (!chat) {
                const allChats = await client.getChats();  // клиент точно инициализирован
                chat = allChats.find(c => c.id._serialized === groupId);
            }

            if (!chat || !chat.isGroup) {
                return res.status(404).json({ status: 'error', error: 'Группа не найдена или недоступна' });
            }

            console.log(`[${clientId}] ➡️ Отправляю сообщение в группу ${groupId}: "${message}"`);
            await chat.sendStateTyping();
            await delay(1500);

            const sentMsg = await client.sendMessage(groupId, message);
            if (!sentMsg?.id) {
                console.warn(`[${clientId}] ⚠️ Сообщение отправлено, но ID пустой (возможна задержка синхронизации)`);
            }

            console.log(`[${clientId}] ✅ Сообщение успешно отправлено в группу ${groupId}`);
            success = true;
            return res.json({ status: 'ok', attempts });

        } catch (e) {
            lastError = e.message;
            console.error(`[${clientId}] ❌ Ошибка отправки в группу ${groupId} (попытка ${attempts}/3):`, lastError);
            if (attempts < 3) {
                console.log(`[${clientId}] 🔄 Повторная попытка через 5 секунд...`);
                await delay(5000);
            }
        }
    }

    // Если 3 попытки не помогли
    res.status(500).json({
        status: 'error',
        error: lastError || 'Не удалось отправить сообщение',
        attempts
    });
});


app.get('/health', async (req, res) => {
    try {
        const info = await client.getState();
        return res.status(200).json({ status: info });
    } catch (e) {
        return res.status(500).json({ status: 'DISCONNECTED', error: e.message });
    }
})


// ================== helpers ==================
const sanitizeFileName = str => String(str).replace(/[^\w.-]/g, '_');

const months = {
    января:0, февраля:1, марта:2, апреля:3, мая:4, июня:5,
    июля:6, августа:7, сентября:8, октября:9, ноября:10, декабря:11
};
const IRKUTSK_OFFSET = 8 * 60 * 60 * 1000;

function tryParseToISO(raw) {
    if (!raw) return null;
    const lower = raw.toLowerCase().trim();
    const now = new Date();
    const localNow = new Date(now.getTime() + IRKUTSK_OFFSET);
    const toISO = (d) => d.toISOString();

    const getTime = () => {
        const m = lower.match(/(\d{1,2}):(\d{2})/);
        return m ? { h: +m[1], m: +m[2] } : { h: 0, m: 0 };
    };

    if (/в сети|online|last seen/i.test(lower)) return toISO(localNow);

    const today = new Date(localNow);
    if (lower.startsWith('сегодня')) {
        const { h, m } = getTime();
        today.setHours(h, m, 0, 0);
        return toISO(today);
    }
    if (lower.startsWith('вчера')) {
        const { h, m } = getTime();
        today.setDate(today.getDate() - 1);
        today.setHours(h, m, 0, 0);
        return toISO(today);
    }

    const m1 = lower.match(/(\d{1,2})\s+([а-яё]+)\s+в\s+(\d{1,2}):(\d{2})/);
    if (m1) {
        const day = +m1[1], mon = months[m1[2]], hour = +m1[3], minute = +m1[4];
        if (mon !== undefined) {
            const d = new Date(localNow.getFullYear(), mon, day, hour, minute);
            return toISO(d);
        }
    }

    const m2 = lower.match(/(\d{1,2})\.(\d{1,2})\.(\d{4})\s+в\s+(\d{1,2}):(\d{2})/);
    if (m2) {
        const d = new Date(+m2[3], +m2[2]-1, +m2[1], +m2[4], +m2[5]);
        return toISO(d);
    }
    return null;
}

function cleanStatus(raw) {
    if (!raw) return null;
    if (/в сети|online|last seen/i.test(raw)) return raw.trim();
    // убираем имя + "был(а) (в сети)"
    const cleaned = raw.replace(/^[^\s]+ (был[аи]?)(?: в сети)?\s*/i, '').trim();
    return cleaned || raw.trim();
}

async function saveDebug(page, phone, reason = 'debug') {
    try {
        const ts = Date.now();
        const safe = sanitizeFileName(phone);
        const imgPath = path.join(localScreenshotDir, `${reason}_${safe}_${ts}.png`);
        await page.screenshot({ path: imgPath });
        console.log(`[${clientId}] 💾 Сохранён скрин (${reason}) -> ${path.basename(imgPath)}`);
    } catch (err) {
        console.warn(`[${clientId}] ⚠ Не удалось сохранить отладку: ${err.message}`);
    }
}

async function closeModals(page) {
    try {
        // Esc дважды — часто достаточно, чтобы убрать туры/баннеры
        await page.keyboard.press('Escape').catch(()=>{});
        await page.waitForTimeout(250);
        await page.keyboard.press('Escape').catch(()=>{});
        await page.waitForTimeout(350);

        // «Начало чата» -> кнопка «Отмена»
        const startDialog = await page.$('div[role="dialog"]');
        if (startDialog) {
            const [cancelBtn] = await page.$x(
                "//div[@role='dialog']//button[normalize-space(translate(string(.), 'АВМЕНО', 'авмено'))[contains(., 'отмена')]]"
            );
            if (cancelBtn) {
                await cancelBtn.click();
                console.log(`[${clientId}] 🧹 Закрыт диалог «Начало чата» (Отмена)`);
                await page.waitForTimeout(800);
            }
        }

        // Кнопки «Продолжить/Понятно/Готово/Далее/OK»
        const btns = await page.$$('div[role="dialog"] button, [data-testid="modal"] button');
        for (const btn of btns) {
            const text = await page.evaluate(el => (el.textContent || '').toLowerCase().trim(), btn);
            if (['продолжить','понятно','отлично','далее','хорошо','готово','ok','okay','continue'].some(t => text.includes(t))) {
                await btn.click();
                console.log(`[${clientId}] 🧹 Нажата кнопка диалога: "${text}"`);
                await page.waitForTimeout(800);
                break;
            }
        }

        // Крестики
        const closeBtn = await page.$('[aria-label="Закрыть"], [data-testid="x-view"], [data-icon="x"]');
        if (closeBtn) {
            await closeBtn.click().catch(()=>{});
            await page.waitForTimeout(400);
        }
    } catch (err) {
        console.warn(`[${clientId}] ⚠ Ошибка при закрытии модалки: ${err.message}`);
    }
}

// безопасная evaluate: пусть пробрасывает фатальные падения контекста
async function safeEvaluate(page, fn, ...args) {
    return page.evaluate(fn, ...args);
}

// ================== /lastseen ==================
app.get('/lastseen/:phone', async (req, res) => {
    const phone = req.params.phone;
    if (!client || !client.pupPage) {
        return res.status(503).json({ status: 'error', error: 'Клиент не инициализирован' });
    }

    const browser = await client.pupPage.browser();
    const page = await browser.newPage();

    const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
    const safePhone = sanitizeFileName(phone);
    const htmlPath = path.join(localScreenshotDir, `debug_${safePhone}_${timestamp}.html`);
    const imgPath  = path.join(localScreenshotDir, `debug_${safePhone}_${timestamp}.png`);

    try {
        if (globalUserAgent) await page.setUserAgent(globalUserAgent);
        await applyAntiDetect(page);

        console.log(`[${clientId}] 🕒 Старт проверки ${phone} (${new Date().toISOString()})`);

        const url = `https://web.whatsapp.com/send?phone=${phone}&text&app_absent=0`;
        await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60000 });
        await page.waitForTimeout(10000);

        // Ждём header, попутно закрывая любые модалки
        const start = Date.now();
        while (Date.now() - start < 30000) { // до 30с
            const anyDialog = await page.$('div[role="dialog"]');
            if (anyDialog) await closeModals(page);

            const headerExists = await page.$('header [data-testid="conversation-info-header"], header');
            if (headerExists) break;

            await page.waitForTimeout(400);
        }

        // Баннер «номер не зарегистрирован»
        const banner = await page.$('div[role="alert"]');
        if (banner) {
            console.warn(`[${clientId}] ⚠ ${phone} — баннер "не зарегистрирован"`);
            await saveDebug(page, phone, 'banner');
            return res.json({ status: 'ok', phone, registered: false, lastSeen: null, rawLastSeen: null, stage: 'banner' });
        }

        // Проверяем, появился ли header
        const header = await page.$('header');
        if (!header) {
            console.warn(`[${clientId}] ❌ header не найден после ожидания`);
            await saveDebug(page, phone, 'no-header');
            return res.json({ status: 'ok', phone, registered: false, lastSeen: null, rawLastSeen: null, stage: 'header' });
        }

        console.log(`[${clientId}] ✅ Чат загружен (номер активен)`);

        // Ждём имя контакта и дорисовку статуса
        await page.waitForSelector('header [data-testid="conversation-info-header"], header span[title]', { timeout: 20000 }).catch(()=>{});
        await page.waitForTimeout(10000);

        // --- Поиск статуса ---
        const statusText = await safeEvaluate(page, () => {
            const regex = /(в сети|был|была|online|last seen|сегодня в|вчера в|\d{1,2} \D+ в \d{1,2}:\d{2})/i;
            const elements = Array.from(document.querySelectorAll('header span, header div'));
            for (const el of elements) {
                const text = el.textContent?.trim() || '';
                const aria = el.getAttribute?.('aria-label')?.trim() || '';
                const title = el.getAttribute?.('title')?.trim() || '';
                if (regex.test(text)) return text;
                if (regex.test(aria)) return aria;
                if (regex.test(title)) return title;
            }
            return null;
        });



        // // Поиск статуса в хедере (textContent/aria/title)
//         const { statusText, fragment } = await safeEvaluate(page, () => {
//             const root = document.querySelector('header') || document;
//             const nodes = [root, ...Array.from(root.querySelectorAll('*'))];
//
//             const rx = /(в сети|online|был|была|last seen|сегодня в|вчера в|\d{1,2}\s+\D+\s+в\s+\d{1,2}:\d{2}|\d{1,2}\.\d{1,2}\.\d{4}\s+в\s+\d{1,2}:\d{2})/i;
//
//             for (const el of nodes) {
//                 const text  = (el.textContent || '').trim();
//                 const aria  = (el.getAttribute && el.getAttribute('aria-label') || '').trim();
//                 const title = (el.getAttribute && el.getAttribute('title') || '').trim();
//                 const hit = text || aria || title;
//                 if (rx.test(hit)) {
//                     return { statusText: hit, fragment: el.outerHTML || '' };
//                 }
//             }
//             return { statusText: null, fragment: '' };
//         });

        // Достаём статус
        // const { statusText, fragment } = await safeEvaluate(page, () => {
        //     const root = document.querySelector('header') || document;
        //     const nodes = [root, ...Array.from(root.querySelectorAll('*'))];
        //
        //     const rx = /(в сети|online|был|была|last seen|сегодня в|вчера в|\d{1,2}\s+\D+\s+в\s+\d{1,2}:\d{2}|\d{1,2}\.\d{1,2}\.\d{4}\s+в\s+\d{1,2}:\d{2})/i;
        //
        //     for (const el of nodes) {
        //         const text  = (el.textContent || '').trim();
        //         const aria  = (el.getAttribute && el.getAttribute('aria-label') || '').trim();
        //         const title = (el.getAttribute && el.getAttribute('title') || '').trim();
        //         const hit = text || aria || title;
        //         if (rx.test(hit)) {
        //             return { statusText: hit, fragment: el.outerHTML || '' };
        //         }
        //     }
        //     return { statusText: null, fragment: '' };
        // });



        if (!statusText) {
            console.warn(`[${clientId}] ⚠ Статус не найден`);
            // const fragPath = path.join(localScreenshotDir, `fragment_${safePhone}_${timestamp}.html`);
            // if (fragment) fs.writeFileSync(fragPath, fragment, 'utf8');
            await saveDebug(page, phone, 'no-status');
            return res.json({ status: 'ok', phone, registered: true, lastSeen: null, rawLastSeen: null, stage: 'noStatus' });
        }

        const cleaned = cleanStatus(statusText);
        console.log(`[${clientId}] 📌 Статус найден: ${statusText}`);

        // опционально: нормализовать в ISO (если нужно)
        const iso = tryParseToISO(cleaned);

        return res.json({
            status: 'ok',
            phone,
            registered: true,
            lastSeen: cleaned,
            lastSeenIso: iso,
            rawLastSeen: statusText
        });

    } catch (e) {
        console.error(`[${clientId}] ❌ Ошибка для ${phone}: ${e.message}`);
        try {
            // fs.writeFileSync(htmlPath, await page.content());
            await page.screenshot({ path: imgPath });
        } catch (_) {}
        return res.status(500).json({ status: 'error', error: e.message });

    } finally {
        try { if (page && !page.isClosed()) await page.close(); } catch (_) {}
    }
});






//
//
// const sanitizeFileName = str => str.replace(/[^\w.-]/g, '_');
//
//
// // --- Парсер дат ---
// const months = {
//     января: 0, февраля: 1, марта: 2, апреля: 3, мая: 4, июня: 5,
//     июля: 6, августа: 7, сентября: 8, октября: 9, ноября: 10, декабря: 11
// };
// const IRKUTSK_OFFSET = 8 * 60 * 60 * 1000;
//
// function tryParseToISO(raw) {
//     if (!raw) return null;
//     const lower = raw.toLowerCase().trim();
//     const now = new Date(); // текущий UTC
//     const localNow = new Date(now.getTime() + IRKUTSK_OFFSET);
//
//     const toISO = (date) => date.toISOString(); // всегда в UTC
//
//     const getTime = () => {
//         const match = lower.match(/(\d{1,2}):(\d{2})/);
//         return match ? { h: +match[1], m: +match[2] } : { h: 0, m: 0 };
//     };
//
//     if (/в сети|online|last seen/i.test(lower)) {
//         return toISO(localNow);
//     }
//     const today = new Date(localNow);
//     if (lower.startsWith('сегодня')) {
//         const { h, m } = getTime();
//         today.setHours(h, m, 0, 0);
//         return toISO(today);
//     }
//     if (lower.startsWith('вчера')) {
//         const { h, m } = getTime();
//         today.setDate(today.getDate() - 1);
//         today.setHours(h, m, 0, 0);
//         return toISO(today);
//     }
//     const monthMatch = lower.match(/(\d{1,2})\s+([а-яё]+)\s+в\s+(\d{1,2}):(\d{2})/);
//     if (monthMatch) {
//         const day = +monthMatch[1];
//         const month = months[monthMatch[2]];
//         const hour = +monthMatch[3], minute = +monthMatch[4];
//         if (month !== undefined) {
//             const date = new Date(localNow.getFullYear(), month, day, hour, minute);
//             return toISO(date);
//         }
//     }
//     const numericMatch = lower.match(/(\d{1,2})\.(\d{1,2})\.(\d{4})\s+в\s+(\d{1,2}):(\d{2})/);
//     if (numericMatch) {
//         const day = +numericMatch[1], month = +numericMatch[2] - 1, year = +numericMatch[3];
//         const hour = +numericMatch[4], minute = +numericMatch[5];
//         const date = new Date(year, month, day, hour, minute);
//         return toISO(date);
//     }
//     return null;
// }
//
// function cleanStatus(raw) {
//     if (!raw) return null;
//
//     if (/в сети|online|last seen/i.test(raw)) return raw.trim();
//
//     // Убираем имя и "был(а)" + "в сети"
//     const cleaned = raw.replace(/^[^\s]+ (был[аи]?)(?: в сети)?\s*/i, '').trim();
//     return cleaned || raw.trim();
// }
//
// // --- Обработчик /lastseen ---
// app.get('/lastseen/:phone', async (req, res) => {
//     const phone = req.params.phone;
//     if (!client || !client.pupPage) {
//         return res.status(503).json({ status: 'error', error: 'Клиент не инициализирован' });
//     }
//
//     const browser = await client.pupPage.browser();
//     let page = await browser.newPage();
//     await page.setUserAgent(globalUserAgent);
//     await applyAntiDetect(page);
//
//     const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
//     const safePhone = sanitizeFileName(phone);
//     const htmlPath = path.join(localScreenshotDir, `debug_${safePhone}_${timestamp}.html`);
//     const imgPath = path.join(localScreenshotDir, `debug_${safePhone}_${timestamp}.png`);
//     const fragPath = path.join(localScreenshotDir, `fragment_${safePhone}_${timestamp}.html`);
//
//     console.log(`[${clientId}] 🕒 Старт проверки ${phone} (${new Date().toISOString()})`);
//
//     const closeModals = async (page) => {
//         try {
//             // На всякий случай — Esc дважды
//             await page.keyboard.press('Escape');
//             await page.waitForTimeout(250);
//             await page.keyboard.press('Escape');
//             await page.waitForTimeout(350);
//
//             // 1) «Начало чата» — жмём «Отмена»
//             const startDialog = await page.$('div[role="dialog"]');
//             if (startDialog) {
//                 const cancelBtn = await page.$x("//div[@role='dialog']//button[.//text()[contains(translate(., 'АВМЕНО', 'авмено'),'отмена')]]");
//                 if (cancelBtn?.[0]) {
//                     await cancelBtn[0].click();
//                     console.log(`[${clientId}] 🧹 Закрыт диалог «Начало чата» (Отмена)`);
//                     await page.waitForTimeout(800);
//                 }
//             }
//
//             // 2) Любые кнопки «Продолжить/Понятно/Готово/Далее/Ок»
//             const buttons = await page.$$('div[role="dialog"] button, [data-testid="modal"] button');
//             for (const btn of buttons) {
//                 const text = await page.evaluate(el => (el.textContent || '').toLowerCase().trim(), btn);
//                 if (['продолжить','понятно','отлично','далее','хорошо','готово','ok','okay','continue'].some(t => text.includes(t))) {
//                     await btn.click();
//                     console.log(`[${clientId}] 🧹 Нажата кнопка диалога: "${text}"`);
//                     await page.waitForTimeout(800);
//                     break;
//                 }
//             }
//
//             // 3) Крестики закрытия
//             const closeBtn = await page.$('[aria-label="Закрыть"], [data-testid="x-view"], [data-icon="x"]');
//             if (closeBtn) {
//                 await closeBtn.click().catch(()=>{});
//                 await page.waitForTimeout(400);
//             }
//         } catch (err) {
//             console.warn(`[${clientId}] ⚠ Ошибка при закрытии модалки: ${err.message}`);
//         }
//     };
//
//
//     async function saveDebug(page, phone, reason = 'debug') {
//         try {
//             const ts = Date.now();
//             const safe = sanitizeFileName(phone);
//             // Если не хочешь html — не нужно объявлять htmlPath
//             const imgPath = path.join(localScreenshotDir, `${reason}_${safe}_${ts}.png`);
//             await page.screenshot({ path: imgPath });
//             console.log(`[${clientId}] 💾 Сохранён скрин (${reason}) -> ${path.basename(imgPath)}`);
//         } catch (err) {
//             console.warn(`[${clientId}] ⚠ Не удалось сохранить отладку: ${err.message}`);
//         }
//     }
//
//     try {
//         // Загружаем чат
//         const url = `https://web.whatsapp.com/send?phone=${phone}&text&app_absent=0`;
//         await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60000 });
//
// // Дадим интерфейсу стартануть
//         await page.waitForTimeout(1200);
//
// // Ждём, пока либо появится header, либо модалка — закрываем и ждём снова
//         const start = Date.now();
//         while (Date.now() - start < 30000) { // максимум 30 сек
//                                              // если всплыло «Начало чата» или любой диалог — закрыть
//             const anyDialog = await page.$('div[role="dialog"]');
//             if (anyDialog) {
//                 await closeModals(page);
//             }
//
//             // header появился?
//             const headerExists = await page.$('header');
//             if (headerExists) break;
//
//             await page.waitForTimeout(400);
//         }
//
// // Проверка баннера «не зарегистрирован»
//         const banner = await page.$('div[role="alert"]');
//         if (banner) {
//             console.warn(`[${clientId}] ⚠ ${phone} — баннер "не зарегистрирован"`);
//             await saveDebug(page, phone, 'banner');
//             return res.json({ status: 'ok', phone, registered: false, lastSeen: null, rawLastSeen: null, stage: 'banner' });
//         }
//
// // Если так и нет хедера — считаем, что чат не раскрылся
//         const header = await page.$('header');
//         if (!header) {
//             console.warn(`[${clientId}] ❌ header не найден после ожидания`);
//             await saveDebug(page, phone, 'no-header');
//             return res.json({ status: 'ok', phone, registered: false, lastSeen: null, rawLastSeen: null, stage: 'header' });
//         }
//
//         console.log(`[${clientId}] ✅ Чат загружен (номер активен)`);
//
// // Доп. пауза, чтобы дорисовался статус
//         await page.waitForTimeout(1000);
//
// // Поиск статуса в хедере (textContent/aria/title)
//         const { statusText, fragment } = await safeEvaluate(page, () => {
//             const root = document.querySelector('header') || document;
//             const nodes = [root, ...Array.from(root.querySelectorAll('*'))];
//
//             const rx = /(в сети|online|был|была|last seen|сегодня в|вчера в|\d{1,2}\s+\D+\s+в\s+\d{1,2}:\d{2}|\d{1,2}\.\d{1,2}\.\d{4}\s+в\s+\d{1,2}:\d{2})/i;
//
//             for (const el of nodes) {
//                 const text  = (el.textContent || '').trim();
//                 const aria  = (el.getAttribute && el.getAttribute('aria-label') || '').trim();
//                 const title = (el.getAttribute && el.getAttribute('title') || '').trim();
//                 const hit = text || aria || title;
//                 if (rx.test(hit)) {
//                     return { statusText: hit, fragment: el.outerHTML || '' };
//                 }
//             }
//             return { statusText: null, fragment: '' };
//         });
//
//         if (!statusText) {
//             console.warn(`[${clientId}] ⚠ Статус не найден`);
//             if (fragment) fs.writeFileSync(path.join(localScreenshotDir, `fragment_${safePhone}_${timestamp}.html`), fragment, 'utf8');
//             await saveDebug(page, phone, 'no-status');
//             return res.json({ status: 'ok', phone, registered: true, lastSeen: null, rawLastSeen: null, stage: 'noStatus' });
//         }
//
//         const cleaned = cleanStatus(statusText);
//         console.log(`[${clientId}] 📌 Статус найден: ${statusText}`);
//         return res.json({ status: 'ok', phone, registered: true, lastSeen: cleaned, rawLastSeen: statusText });
//
//     } catch (e) {
//         console.error(`[${clientId}] ❌ Ошибка для ${phone}: ${e.message}`);
//         try {
//             fs.writeFileSync(htmlPath, await page.content());
//             await page.screenshot({ path: imgPath });
//         } catch (_) {}
//         if (!page.isClosed()) await page.close();
//         return res.status(500).json({ status: 'error', error: e.message });
//     }
// });
//


// --- Универсальный эндпоинт для проверки регистрации и lastSeen ---
app.get('/is-active-user', async (req, res) => {
    const phone = req.query.phone;
    if (!phone) return res.status(400).json({ status: 'error', message: 'phone required' });
    if (!client) return res.status(503).json({ status: 'error', message: 'client not ready' });

    try {
        const numberId = await client.getNumberId(phone);
        const registered = !!numberId;

        let lastSeenIso = null;
        if (registered && client.pupPage) {
            try {
                const rawStatus = await fetchLastSeenText(phone);
                if (rawStatus) {
                    lastSeenIso = convertStatusToIso(rawStatus);
                }
            } catch (_) {}
        }

        return res.json({ status: 'ok', registered, lastSeen: lastSeenIso });
    } catch (e) {
        console.error(`[${clientId}] ❌ Ошибка /is-active-user: ${e.message}`);
        return res.status(500).json({ status: 'error', message: e.message });
    }
});

// --- Эндпоинт только lastSeen (тот же движок) ---
app.get('/last-seen', async (req, res) => {
    const phone = req.query.phone;
    if (!client || !client.pupPage) {
        return res.status(503).json({ status: 'error', message: 'client not ready' });
    }

    try {
        const rawStatus = await fetchLastSeenText(phone);
        const lastSeenIso = rawStatus ? convertStatusToIso(rawStatus) : null;
        return res.json({ status: 'ok', lastSeen: lastSeenIso });
    } catch (e) {
        console.error(`[${clientId}] ❌ Ошибка last-seen: ${e.message}`);
        return res.status(500).json({ status: 'error', message: e.message });
    }
});

// --- Проверка регистрации отдельно (упрощённый) ---
app.get('/check-registered', async (req, res) => {
    const phone = req.query.phone;
    if (!phone) return res.status(400).json({ status: 'error', message: 'phone required' });
    if (!client) return res.status(503).json({ status: 'error', message: 'client not ready' });

    try {
        const numberId = await client.getNumberId(phone);
        return res.json({ status: 'ok', registered: !!numberId });
    } catch (e) {
        console.error(`[${clientId}] ❌ Ошибка check-registered: ${e.message}`);
        return res.status(500).json({ status: 'error', message: e.message });
    }
});

// --- Вспомогательные функции ---
async function fetchLastSeenText(phone) {
    const browser = await client.pupPage.browser();
    const page = await browser.newPage();
    await page.setUserAgent(globalUserAgent);

    const url = `https://web.whatsapp.com/send?phone=${phone}&text&app_absent=0`;
    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60000 });
    await page.waitForTimeout(10000);

    const statusText = await safeEvaluate(page, () => {
        const regex = /(в сети|был|сегодня|вчера|\d{1,2} \D+ в \d{1,2}:\d{2})/i;
        const elements = Array.from(document.querySelectorAll('header span, header div'));
        for (const el of elements) {
            const text = el.textContent?.trim() || '';
            if (regex.test(text)) return text;
        }
        return null;
    });
    await page.close();
    return statusText;
}

function convertStatusToIso(raw) {
    const now = new Date();
    const today = now.toISOString().split('T')[0];
    if (/в сети/i.test(raw)) return new Date().toISOString();
    if (/сегодня/i.test(raw)) return `${today}T${raw.match(/\d{1,2}:\d{2}/)?.[0] || '00:00'}:00`;
    if (/вчера/i.test(raw)) {
        const yesterday = new Date(now.setDate(now.getDate() - 1)).toISOString().split('T')[0];
        return `${yesterday}T${raw.match(/\d{1,2}:\d{2}/)?.[0] || '00:00'}:00`;
    }
    // Фоллбек — вернуть строку как есть (Java может сохранить null или строку)
    return null;
}

app.listen(3000, () => {
    console.log(`🟢 API запущено на порту 3000 для клиента ${clientId}`);
});



// ПОСЛЕДНЯЯ РАБОЧАЯ НО БЕЗ "В СЕТИ"
// app.get('/lastseen/:phone', async (req, res) => {
//   const phone = req.params.phone;
//   if (!client || !client.pupPage) {
//     return res.status(503).json({ status: 'error', error: 'Клиент не инициализирован' });
//   }
//
//   const browser = await client.pupPage.browser();
//   const page = await browser.newPage();
//   await page.setUserAgent(globalUserAgent);
//
//   const url = `https://web.whatsapp.com/send?phone=${phone}&text&app_absent=0`;
//   const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
//   const safePhone = sanitizeFileName(phone);
//   const htmlPath = `lastseen_debug_${safePhone}_${timestamp}.html`;
//   const imgPath = `lastseen_debug_${safePhone}_${timestamp}.png`;
//
//   const startTime = Date.now();
//   console.log(`[${clientId}] 🕒 Старт проверки ${phone} (${new Date().toISOString()})`);
//
//   const closeModals = async () => {
//     let closed = false;
//     try {
//       const buttons = await page.$$('div[role="dialog"] button');
//       for (const btn of buttons) {
//         const text = await page.evaluate(el => el.textContent?.toLowerCase() || '', btn);
//         if (['продолжить', 'понятно', 'отлично', 'далее', 'хорошо', 'готово'].some(t => text.includes(t))) {
//           await btn.click();
//           closed = true;
//           break;
//         }
//       }
//     } catch (_) {}
//
//     if (closed) {
//       console.log(`[${clientId}] 🧹 Закрыто модальное окно (время: ${Date.now() - startTime} мс)`);
//       await page.waitForTimeout(1500);
//     } else {
//       console.log(`[${clientId}] ℹ️ Модальное окно не обнаружено (время: ${Date.now() - startTime} мс)`);
//     }
//   };
//
//   try {
//     console.log(`[${clientId}] 🔍 Перехожу на чат ${phone} (${url})`);
//     await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60000 });
//     await page.waitForTimeout(12000);
//     await closeModals();
//
//     const banner = await page.$('div[role="alert"]');
//     if (banner) {
//       console.warn(`[${clientId}] ⚠️ ${phone} — найден баннер "не зарегистрирован"`);
//       await page.close();
//       return res.json({ status: 'ok', phone, registered: false, lastSeen: null, stage: 'banner' });
//     }
//
//
//     // Вместо isRegisteredUser используем проверку header
//     try {
//       await page.waitForSelector('header', { timeout: 15000 });
//       console.log(`[${clientId}] ✅ Чат загружен — номер активен`);
//       await page.waitForTimeout(10000);
//     } catch {
//       console.warn(`[${clientId}] ❌ header не найден — считаем номер ${phone} не зарегистрирован`);
//       await page.close();
//       return res.json({ status: 'ok', phone, registered: false, lastSeen: null, stage: 'header' });
//     }
//
//
//     // --- Поиск статуса ---
//     const statusText = await safeEvaluate(page, () => {
//       const regex = /(в сети|был|online|last seen|сегодня в|вчера в|\d{1,2} \D+ в \d{1,2}:\d{2})/i;
//       const elements = Array.from(document.querySelectorAll('header span, header div'));
//       for (const el of elements) {
//         const text = el.textContent?.trim() || '';
//         const aria = el.getAttribute?.('aria-label')?.trim() || '';
//         const title = el.getAttribute?.('title')?.trim() || '';
//         if (regex.test(text)) return text;
//         if (regex.test(aria)) return aria;
//         if (regex.test(title)) return title;
//       }
//       return null;
//     });
//
//     await page.setViewport({ width: 1920, height: 1080 });
//     await page.screenshot({ path: imgPath });
//     fs.writeFileSync(htmlPath, await page.content());
//     await page.close();
//
//     if (statusText) {
//       const cleaned = cleanStatus(statusText);
//       console.log(`[${clientId}] 📌 Статус найден: ${statusText}`);
//       return res.json({ status: 'ok', phone, registered: true, lastSeen: cleaned, stage: 'lastSeen' });
//     } else {
//       console.warn(`[${clientId}] ⚠ Статус не найден (HTML: ${htmlPath})`);
//       return res.json({ status: 'ok', phone, registered: true, lastSeen: null, stage: 'lastSeen' });
//     }
//   } catch (e) {
//     console.error(`[${clientId}] ❌ Ошибка для ${phone}: ${e.message}`);
//     try {
//       fs.writeFileSync(htmlPath, await page.content());
//       await page.screenshot({ path: imgPath });
//     } catch (_) {}
//     await page.close();
//     return res.status(500).json({ status: 'error', error: e.message, stage: 'error' });
//   }
// });






// app.get('/lastseen/:phone', async (req, res) => {
//   const phone = req.params.phone;
//   if (!client || !client.pupPage) {
//     return res.status(503).json({ status: 'error', error: 'Клиент не инициализирован' });
//   }
//
//   const browser = await client.pupPage.browser();
//   const page = await browser.newPage();
//   await page.setUserAgent(globalUserAgent);
//
//   const url = `https://web.whatsapp.com/send?phone=${phone}&text&app_absent=0`;
//   const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
//   const safePhone = sanitizeFileName(phone);
//   const htmlPath = `lastseen_debug_${safePhone}_${timestamp}.html`;
//   const imgPath = `lastseen_debug_${safePhone}_${timestamp}.png`;
//
//   const startTime = Date.now();
//   console.log(`[${clientId}] 🕒 Старт проверки ${phone} (${new Date().toISOString()})`);
//
//   const closeModals = async () => {
//     let closed = false;
//     try {
//       const buttons = await page.$$('div[role="dialog"] button');
//       for (const btn of buttons) {
//         const text = await page.evaluate(el => el.textContent?.toLowerCase() || '', btn);
//         if (['продолжить', 'понятно', 'отлично', 'далее', 'хорошо', 'готово'].some(t => text.includes(t))) {
//           await btn.click();
//           closed = true;
//           break;
//         }
//       }
//     } catch (_) {}
//
//     if (closed) {
//       console.log(`[${clientId}] 🧹 Закрыто модальное окно (время: ${Date.now() - startTime} мс)`);
//       await page.waitForTimeout(1500);
//     } else {
//       console.log(`[${clientId}] ℹ️ Модальное окно не обнаружено (время: ${Date.now() - startTime} мс)`);
//     }
//   };
//
//   try {
//     console.log(`[${clientId}] 🔍 Перехожу на чат ${phone} (${url})`);
//     await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60000 });
//     await page.waitForTimeout(12000);
//     await closeModals();
//
//     // 👉 Новая проверка через API WhatsApp
//     const isRegistered = await client.isRegisteredUser(`${phone}@c.us`);
//     if (!isRegistered) {
//       console.warn(`[${clientId}] 📵 ${phone} — не зарегистрирован (проверка через API)`);
//       await page.close();
//       return res.json({ status: 'ok', phone, registered: false, lastSeen: null });
//     }
//
//     // Дальше идёт стандартная логика (header + lastSeen)
//     try {
//       await page.waitForSelector('header', { timeout: 15000 });
//       console.log(`[${clientId}] ✅ Чат загружен`);
//       await page.waitForTimeout(10000);
//     } catch {
//       console.warn(`[${clientId}] ❌ header не найден — номер НЕ зарегистрирован в WhatsApp`);
//       await page.close();
//       return res.json({ status: 'ok', phone, registered: false, lastSeen: null });
//     }
//
//     const statusText = await safeEvaluate(page, () => {
//       const regex = /(в сети|был|online|last seen|сегодня в|вчера в|\d{1,2} \D+ в \d{1,2}:\d{2})/i;
//       const elements = Array.from(document.querySelectorAll('header span, header div'));
//       for (const el of elements) {
//         const text = el.textContent?.trim() || '';
//         const aria = el.getAttribute?.('aria-label')?.trim() || '';
//         const title = el.getAttribute?.('title')?.trim() || '';
//         if (regex.test(text)) return text;
//         if (regex.test(aria)) return aria;
//         if (regex.test(title)) return title;
//       }
//       return null;
//     });
//
//     await page.setViewport({ width: 1920, height: 1080 });
//     await page.screenshot({ path: imgPath });
//     fs.writeFileSync(htmlPath, await page.content());
//     await page.close();
//
//     if (statusText) {
//       const cleaned = cleanStatus(statusText);
//       console.log(`[${clientId}] 📌 Статус найден: ${statusText}`);
//       return res.json({ status: 'ok', phone, registered: true, lastSeen: cleaned });
//     } else {
//       console.warn(`[${clientId}] ⚠ Статус не найден (HTML: ${htmlPath})`);
//       return res.json({ status: 'ok', phone, registered: true, lastSeen: null });
//     }
//   } catch (e) {
//     console.error(`[${clientId}] ❌ Ошибка для ${phone}: ${e.message}`);
//     try {
//       fs.writeFileSync(htmlPath, await page.content());
//       await page.screenshot({ path: imgPath });
//     } catch (_) {}
//     await page.close();
//     return res.status(500).json({ status: 'error', error: e.message });
//   }
// });



// app.get('/lastseen/:phone', async (req, res) => {
//   const phone = req.params.phone;
//   if (!client || !client.pupPage) {
//     return res.status(503).json({ status: 'error', error: 'Клиент не инициализирован' });
//   }
//
//   const browser = await client.pupPage.browser();
//   const page = await browser.newPage();
//   await page.setUserAgent(globalUserAgent);
//
//   const url = `https://web.whatsapp.com/send?phone=${phone}&text&app_absent=0`;
//   const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
//   const safePhone = sanitizeFileName(phone);
//   const htmlPath = `lastseen_debug_${safePhone}_${timestamp}.html`;
//   const imgPath = `lastseen_debug_${safePhone}_${timestamp}.png`;
//
//   const startTime = Date.now();
//   console.log(`[${clientId}] 🕒 Старт проверки ${phone} (${new Date().toISOString()})`);
//
//   // 🔧 Закрытие модалки WhatsApp Web
//   const closeModals = async () => {
//     let closed = false;
//     try {
//       const buttons = await page.$$('div[role="dialog"] button');
//       for (const btn of buttons) {
//         const text = await page.evaluate(el => el.textContent?.toLowerCase() || '', btn);
//         if (['продолжить', 'понятно', 'отлично', 'далее', 'хорошо', 'готово'].some(t => text.includes(t))) {
//           await btn.click();
//           closed = true;
//           break;
//         }
//       }
//     } catch (_) {
//       // молча пропускаем
//     }
//
//     if (closed) {
//       console.log(`[${clientId}] 🧹 Закрыто модальное окно (время: ${Date.now() - startTime} мс)`);
//       await page.waitForTimeout(1500);
//     } else {
//       console.log(`[${clientId}] ℹ️ Модальное окно не обнаружено (время: ${Date.now() - startTime} мс)`);
//     }
//   };
//
//   try {
//     console.log(`[${clientId}] 🔍 Перехожу на чат ${phone} (${url})`);
//     const gotoStart = Date.now();
//     await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60000 });
//     console.log(`[${clientId}] ⏱ goto() занял ${Date.now() - gotoStart} мс (с начала: ${Date.now() - startTime} мс)`);
//
//     await page.waitForTimeout(12000);
//     console.log(`[${clientId}] ⏱ Ждём перед закрытием модалок (с начала: ${Date.now() - startTime} мс)`);
//     await closeModals();
//
//     let chatLoaded = false;
//     try {
//       const headerStart = Date.now();
//       await page.waitForSelector('header', { timeout: 15000 });
//       chatLoaded = true;
//       console.log(`[${clientId}] ✅ Чат загружен (ждали ${Date.now() - headerStart} мс, с начала: ${Date.now() - startTime} мс)`);
//       await page.waitForTimeout(10000);
//     } catch {
//       console.warn(`[${clientId}] ⚠ header не найден — номер может быть не в WhatsApp (с начала: ${Date.now() - startTime} мс)`);
//       await page.close();
//       return res.json({ status: 'ok', phone, registered: false, lastSeen: null });
//     }
//
//     const parseStart = Date.now();
//     const statusText = await safeEvaluate(page, () => {
//       const regex = /(в сети|был|online|last seen|сегодня в|вчера в|\d{1,2} \D+ в \d{1,2}:\d{2})/i;
//       const elements = Array.from(document.querySelectorAll('header span, header div'));
//       for (const el of elements) {
//         const text = el.textContent?.trim() || '';
//         const aria = el.getAttribute?.('aria-label')?.trim() || '';
//         const title = el.getAttribute?.('title')?.trim() || '';
//         if (regex.test(text)) return text;
//         if (regex.test(aria)) return aria;
//         if (regex.test(title)) return title;
//       }
//       return null;
//     });
//     console.log(`[${clientId}] ⏱ Парсинг статуса занял ${Date.now() - parseStart} мс (с начала: ${Date.now() - startTime} мс)`);
//
//     await page.setViewport({ width: 1920, height: 1080 });
//     await page.screenshot({ path: imgPath });
//     fs.writeFileSync(htmlPath, await page.content());
//     await page.close();
//
//     const totalElapsed = Date.now() - startTime;
//     if (statusText) {
//       const cleaned = cleanStatus(statusText);
//       console.log(`[${clientId}] 📌 Статус найден: ${statusText} (всего ${totalElapsed} мс)`);
//       return res.json({ status: 'ok', phone, lastSeen: cleaned });
//     } else {
//       console.warn(`[${clientId}] ⚠ Статус не найден (HTML: ${htmlPath}, всего ${totalElapsed} мс)`);
//       return res.json({ status: 'ok', phone, lastSeen: null });
//     }
//   } catch (e) {
//     const totalElapsed = Date.now() - startTime;
//     console.error(`[${clientId}] ❌ Ошибка для ${phone}: ${e.message} (всего ${totalElapsed} мс)`);
//
//     try {
//       fs.writeFileSync(htmlPath, await page.content());
//       await page.screenshot({ path: imgPath });
//     } catch (_) {}
//     await page.close();
//     return res.status(500).json({ status: 'error', error: e.message });
//   }
// });












// app.get('/lastseen/:phone', async (req, res) => {
//   const phone = req.params.phone;
//   if (!client || !client.pupPage) {
//     return res.status(503).json({ status: 'error', error: 'Клиент не инициализирован' });
//   }
//
//   const browser = await client.pupPage.browser();
//   const page = await browser.newPage();
//   await page.setUserAgent(globalUserAgent);
//
//   const url = `https://web.whatsapp.com/send?phone=${phone}&text&app_absent=0`;
//   const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
//   const safePhone = sanitizeFileName(phone);
//   const htmlPath = `lastseen_debug_${safePhone}_${timestamp}.html`;
//   const imgPath = `lastseen_debug_${safePhone}_${timestamp}.png`;
//
//   // 🔧 Закрытие модалки WhatsApp Web
//   const closeModals = async () => {
//     let closed = false;
//     try {
//       const buttons = await page.$$('div[role="dialog"] button');
//       for (const btn of buttons) {
//         const text = await page.evaluate(el => el.textContent?.toLowerCase() || '', btn);
//         if (['продолжить', 'понятно', 'отлично', 'далее', 'хорошо', 'готово'].some(t => text.includes(t))) {
//           await btn.click();
//           closed = true;
//           break;
//         }
//       }
//     } catch (_) {
//       // молча пропускаем
//     }
//
//     if (closed) {
//       console.log(`[${clientId}] 🧹 Закрыто модальное окно`);
//       await page.waitForTimeout(1500);
//     } else {
//       console.log(`[${clientId}] ℹ️ Модальное окно не обнаружено`);
//     }
//   };
//
//   try {
//     console.log(`[${clientId}] 🔍 Перехожу на чат с ${phone}`);
//     await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60000 });
//     // await closeModals();
//     await page.waitForTimeout(12000);
//     await closeModals();
//
//     try {
//       await page.waitForSelector('header', { timeout: 15000 });
//       console.log(`[${clientId}] ✅ Чат загружен`);
//       await page.waitForTimeout(10000);
//     } catch {
//       console.warn(`[${clientId}] ⚠ header не найден — возможно, номер не зарегистрирован`);
//     }
//
//     const statusText = await safeEvaluate(page, () => {
//       const regex = /(в сети|был|online|last seen|сегодня в|вчера в|\d{1,2} \D+ в \d{1,2}:\d{2})/i;
//       const elements = Array.from(document.querySelectorAll('header span, header div'));
//       for (const el of elements) {
//         const text = el.textContent?.trim() || '';
//         const aria = el.getAttribute?.('aria-label')?.trim() || '';
//         const title = el.getAttribute?.('title')?.trim() || '';
//         if (regex.test(text)) return text;
//         if (regex.test(aria)) return aria;
//         if (regex.test(title)) return title;
//       }
//       return null;
//     });





// const sanitizeFileName = str => str.replace(/[^\w.-]/g, '_');
//
// app.get('/lastseen/:phone', async (req, res) => {
//   const phone = req.params.phone;
//   if (!client || !client.pupPage) {
//     return res.status(503).json({ status: 'error', error: 'Клиент не инициализирован' });
//   }
//
//   const browser = await client.pupPage.browser();
//   const page = await browser.newPage();
//   await page.setUserAgent(globalUserAgent);
//
//   const url = `https://web.whatsapp.com/send?phone=${phone}&text&app_absent=0`;
//   const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
//   const safePhone = sanitizeFileName(phone);
//   const htmlPath = `lastseen_debug_${safePhone}_${timestamp}.html`;
//   const imgPath = `lastseen_debug_${safePhone}_${timestamp}.png`;
//
//   // 🔧 Закрытие модалки WhatsApp Web
//   const closeModals = async () => {
//     let closed = false;
//     try {
//       const buttons = await page.$$('div[role="dialog"] button');
//       for (const btn of buttons) {
//         const text = await page.evaluate(el => el.textContent?.toLowerCase() || '', btn);
//         if (['продолжить', 'понятно', 'отлично', 'далее', 'хорошо', 'готово'].some(t => text.includes(t))) {
//           await btn.click();
//           closed = true;
//           break;
//         }
//       }
//     } catch (_) {
//       // молча пропускаем
//     }
//
//     if (closed) {
//       console.log(`[${clientId}] 🧹 Закрыто модальное окно`);
//       await page.waitForTimeout(1500);
//     } else {
//       console.log(`[${clientId}] ℹ️ Модальное окно не обнаружено`);
//     }
//   };
//
//   try {
//     console.log(`[${clientId}] 🔍 Перехожу на чат с ${phone}`);
//     await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60000 });
//     await closeModals();
//     await page.waitForTimeout(10000);
//     await closeModals();
//
//     try {
//       await page.waitForSelector('header', { timeout: 15000 });
//       console.log(`[${clientId}] ✅ Чат загружен`);
//       await page.waitForTimeout(10000);
//     } catch {
//       console.warn(`[${clientId}] ⚠ header не найден — возможно, номер не зарегистрирован`);
//     }
//
//     const statusText = await page.evaluate(() => {
//       const regex = /(в сети|был|online|last seen|сегодня в|вчера в|\d{1,2} \D+ в \d{1,2}:\d{2})/i;
//       const elements = Array.from(document.querySelectorAll('header span, header div'));
//       for (const el of elements) {
//         const text = el.textContent?.trim() || '';
//         const aria = el.getAttribute?.('aria-label')?.trim() || '';
//         const title = el.getAttribute?.('title')?.trim() || '';
//         if (regex.test(text)) return text;
//         if (regex.test(aria)) return aria;
//         if (regex.test(title)) return title;
//       }
//       return null;
//     });
//
//     await page.setViewport({ width: 1920, height: 1080 });
//     await page.screenshot({ path: imgPath });
//     fs.writeFileSync(htmlPath, await page.content());
//     await page.close();
//
//     if (statusText) {
//       console.log(`[${clientId}] 📌 Статус найден: ${statusText}`);
//       return res.json({ phone, status: statusText });
//     } else {
//       console.warn(`[${clientId}] ⚠ Статус не найден, HTML: ${htmlPath}`);
//       return res.json({ phone, status: 'не удалось получить статус' });
//     }
//   } catch (e) {
//     console.error(`[${clientId}] ❌ Ошибка: ${e.message}`);
//     try {
//       fs.writeFileSync(htmlPath, await page.content());
//       await page.screenshot({ path: imgPath });
//     } catch (_) {}
//     await page.close();
//     return res.status(500).json({ status: 'error', error: e.message });
//   }
// });