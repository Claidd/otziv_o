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
const proxyArg = process.env.PROXY_URL ? [`--proxy-server=${process.env.PROXY_URL}`] : [];

const clientId = process.env.CLIENT_ID || 'default';
const serverUrl = process.env.SERVER_URL || 'http://localhost:8080';
const dataPath = process.env.AUTH_PATH || path.join(os.homedir(), '.wwebjs_auth');
const qrStore = {};
let client;


const makeClient = (id) => {
    const selectedUserAgent = userAgents[Math.floor(Math.random() * userAgents.length)];
    console.log(`[${id}] Используемый User-Agent: ${selectedUserAgent}`);

    const instance = new Client({
        authStrategy: new LocalAuth({
            clientId: id,
            dataPath: dataPath
        }),
        puppeteer: {
            headless: true,
            executablePath: puppeteer.executablePath(),
            args: [
                '--no-sandbox',
                '--disable-setuid-sandbox',
                `--user-agent=${selectedUserAgent}`,
                ...proxyArg
            ]
        }
    });

    instance.on('browser', async (browser) => {
        const pages = await browser.pages();
        const page = pages.length ? pages[0] : await browser.newPage();
        const profile = getMobileEmulationProfile(selectedUserAgent);

        await page.setViewport({
            ...profile.viewport,
            isMobile: true,
            hasTouch: true
        });

        await page.evaluateOnNewDocument((profile) => {
            // Языки и платформа
            Object.defineProperty(navigator, 'languages', { get: () => ['ru-RU', 'ru'] });
            Object.defineProperty(navigator, 'language', { get: () => 'ru-RU' });
            Object.defineProperty(navigator, 'platform', { get: () => profile.platform });
            Object.defineProperty(navigator, 'hardwareConcurrency', { get: () => 4 });
            Object.defineProperty(navigator, 'maxTouchPoints', { get: () => 5 });

            // WebGL fingerprint spoof
            const getParameter = WebGLRenderingContext.prototype.getParameter;
            WebGLRenderingContext.prototype.getParameter = function (parameter) {
                if (parameter === 37445) return profile.renderer;
                if (parameter === 37446) return profile.vendor;
                return getParameter.call(this, parameter);
            };

            // battery API
            navigator.getBattery = async () => ({
                charging: true,
                chargingTime: 0,
                dischargingTime: Infinity,
                level: 0.95,
                onchargingchange: null,
                onlevelchange: null,
                onchargingtimechange: null,
                ondischargingtimechange: null
            });

            // mediaDevices
            navigator.mediaDevices = {
                enumerateDevices: async () => ([
                    { kind: "audioinput", label: "Микрофон", deviceId: "default" },
                    { kind: "videoinput", label: "Камера", deviceId: "default" }
                ])
            };

            // webdriver = false
            Object.defineProperty(navigator, 'webdriver', {
                get: () => false
            });

            // plugins
            Object.defineProperty(navigator, 'plugins', {
                get: () => [1, 2, 3]
            });

            // mimeTypes
            Object.defineProperty(navigator, 'mimeTypes', {
                get: () => [{ type: "application/pdf" }]
            });

            // Маскировка соединения (network info API)
            Object.defineProperty(navigator, 'connection', {
                get: () => ({
                    downlink: 10,
                    effectiveType: '4g',
                    rtt: 50,
                    saveData: false,
                    type: 'wifi'
                })
            });

            // Ориентация экрана
            window.screen.orientation = {
                angle: 0,
                type: 'portrait-primary',
                onchange: null
            };

            window.chrome = {
                runtime: {},
                loadTimes: () => {},
                csi: () => {},
                app: { isInstalled: false }
            };

            const originalQuery = window.navigator.permissions?.query;
            if (originalQuery) {
                window.navigator.permissions.query = (parameters) => (
                    parameters.name === 'notifications'
                        ? Promise.resolve({ state: Notification.permission })
                        : originalQuery(parameters)
                );
            }

            const originalToString = Function.prototype.toString;
            Function.prototype.toString = function () {
                if (this === window.navigator.permissions.query) {
                    return 'function query() { [native code] }';
                }
                return originalToString.call(this);
            };
        }, profile);
    });



    instance.on('qr', qr => {
        qrStore[id] = qr;
        console.log(`[${id}] QR-код (терминал):`);
        qrcodeTerminal.generate(qr, { small: true });
    });

    instance.on('authenticated', () => {
        console.log(`[${id}] ✅ Авторизация завершена`);
    });

    instance.on('ready', () => {
        console.log(`[${id}] 🔥 Клиент готов`);
    });

    const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

    instance.on('message', async msg => {
        const chat = await msg.getChat();

        if (msg.type !== 'chat') {
            console.log(`[${id}] 📷 Получено медиа сообщение (${msg.type}) от ${msg.from}. Игнорируем.`);
            return;
        }

        const content = msg.body?.trim();
        if (!content) return;

        const from = msg.from.replace('@c.us', '');

        if (chat.isGroup) {
            // Групповое сообщение — без задержек и без markAsRead
            const groupId = chat.id._serialized;
            const senderId = msg.author;
            const senderNumber = senderId?.replace('@c.us', '') || 'unknown';

            console.log(`📨 [${id}] Группа: ${chat.name}`);
            console.log(`👤 Отправитель: ${senderNumber}`);
            console.log(`💬 Текст: ${content}`);

            try {
                await axios.post(`${serverUrl}/webhook/whatsapp-group-reply`, {
                    clientId: id,
                    groupId,
                    groupName: chat.name,
                    from: senderNumber,
                    message: content
                });
            } catch (err) {
                console.error(`[${id}] ❌ Ошибка при отправке вебхука из группы:`, err.message);
            }

        } else {
            // Личное сообщение — с задержкой и markAsRead
            console.log(`[${id}] 📥 Входящее сообщение от ${from}: ${content}`);

            const delayBeforeRead = Math.floor(Math.random() * 25000) + 5000; // 5–30 сек
            await delay(delayBeforeRead);

            try {
                await chat.sendSeen();
                console.log(`[${id}] ✅ Пометили чат с ${from} как прочитанный`);
            } catch (err) {
                console.error(`[${id}] ❌ Не удалось пометить как прочитанный: ${err.message}`);
            }


            const delayAfterRead = Math.floor(Math.random() * 5000) + 2000; // 2–7 сек
            await delay(delayAfterRead);

            try {
                await axios.post(`${serverUrl}/webhook/whatsapp-reply`, {
                    clientId: id,
                    from,
                    message: content
                });
                console.log(`[${id}] 📤 Вебхук отправлен после прочтения`);
            } catch (err) {
                console.error(`[${id}] ❌ Ошибка при отправке вебхука: ${err.message}`);
            }
        }
    });



    instance.initialize();
    return instance;
};

client = makeClient(clientId);

const app = express();
app.use(bodyParser.json());

app.get('/qr', async (req, res) => {
    const qrData = qrStore[clientId];
    if (!qrData) return res.status(404).send('QR-код не найден');

    const qrImage = await qrcode.toDataURL(qrData);
    res.send(`
    <html>
      <head><title>QR-код</title></head>
      <body>
        <h2>QR-код для ${clientId}</h2>
        <img src="${qrImage}" />
      </body>
    </html>
  `);
});

app.post('/send', async (req, res) => {
    const { phone, message } = req.body;
    console.log(`📤 Отправка в личку ${phone}: ${message}`);
    if (!client || !client.info || !client.info.wid) {
        return res.status(503).json({ status: 'error', error: 'Клиент не готов или не авторизован' });
    }

    try {
        console.log(`[${clientId}] ➡️ Отправка POST на ${serverUrl}/webhook/whatsapp-reply`);
        await client.sendMessage(`${phone}@c.us`, message);
        res.json({ status: 'ok' });
    } catch (e) {
        res.status(500).json({ status: 'error', error: e.message });
    }
});

app.post('/send-group', async (req, res) => {
    const { groupId, message } = req.body;
    console.log(`📤 Отправка в группу ${groupId}: ${message}`);

    try {
        console.log(`[${clientId}] ➡️ Отправка POST на ${serverUrl}/webhook/whatsapp-reply`);

        await client.sendMessage(groupId, message);
        res.json({ status: 'ok' });
    } catch (e) {
        console.error(`❌ Ошибка при отправке в группу: ${e.message}`);
        res.status(500).json({ status: 'error', error: e.message });
    }
});

app.get('/health', async (req, res) => {
    try {
        const info = await client.getState(); // например, "CONNECTED"
        return res.status(200).json({ status: info });
    } catch (e) {
        return res.status(500).json({ status: 'DISCONNECTED', error: e.message });
    }
});

app.listen(3000, () => {
    console.log(`🟢 API запущено на порту 3000 для клиента ${clientId}`);
});
