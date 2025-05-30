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

const userAgents = [
  "Mozilla/5.0 (Linux; Android 13; Pixel 6 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.6312.107 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.224 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 12; Samsung Galaxy S21) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.6045.163 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 11; Mi 11 Lite) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.5938.132 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 13; Redmi Note 12 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.6167.57 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 10; Samsung Galaxy A51) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.5790.171 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 13; Realme 9 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.6312.58 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 11; OnePlus 8T) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.5845.98 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 12; POCO X4 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.123 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 13; Samsung Galaxy A53) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.6167.140 Mobile Safari/537.36",

  "Mozilla/5.0 (Linux; Android 11; Nokia 5.4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.28 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 10; Huawei P30 Lite) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.5993.145 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 13; Infinix Note 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.6312.47 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 12; Vivo Y21s) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.6045.98 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 10; Samsung Galaxy M31) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.5938.62 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 11; Realme C25s) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.5993.106 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 13; Xiaomi 13 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.6312.27 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 11; Oppo Reno5 Lite) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.70 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 12; Honor 70) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.6167.61 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 10; Pixel 3 XL) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.5790.111 Mobile Safari/537.36",

  "Mozilla/5.0 (Linux; Android 13; Galaxy Z Flip 4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.6312.81 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 12; Galaxy Z Fold 3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.6167.129 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 11; Moto G60) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.6045.140 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 13; Tecno Camon 20 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.95 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 10; Redmi 9A) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.5790.161 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 12; Redmi Note 10 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.55 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 13; Vivo V27e) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.6312.48 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 11; Oppo A74) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.5993.89 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 10; Realme 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.5845.83 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 13; Honor X9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.6312.33 Mobile Safari/537.36",

  "Mozilla/5.0 (Linux; Android 12; Asus ROG Phone 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.6167.139 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 11; OnePlus Nord N10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.6045.137 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 13; Redmi K50i) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.65 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 12; Infinix Zero 5G) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.6167.78 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 10; Realme Narzo 30A) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.5938.122 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 13; Poco F4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.6312.113 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 11; Vivo Y20) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.5993.172 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 13; Galaxy M13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.40 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 12; Tecno Spark 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.6167.112 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 11; Nokia G21) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.6045.130 Mobile Safari/537.36"
];


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

function getMobileEmulationProfile(userAgent) {
  const profiles = [
    {
      regex: /Pixel 6 Pro/i,
      viewport: { width: 412, height: 915, deviceScaleFactor: 3.5 },
      platform: 'Linux armv8l',
      renderer: 'Mali-G78 MP20',
      vendor: 'Google Inc.'
    },
    {
      regex: /Samsung Galaxy S21/i,
      viewport: { width: 360, height: 800, deviceScaleFactor: 3 },
      platform: 'Linux armv8l',
      renderer: 'Mali-G78 MP14',
      vendor: 'Samsung'
    },
    {
      regex: /Redmi Note 12 Pro/i,
      viewport: { width: 393, height: 873, deviceScaleFactor: 2.75 },
      platform: 'Linux armv8l',
      renderer: 'Adreno 610',
      vendor: 'Xiaomi'
    },
    {
      regex: /OnePlus 8T/i,
      viewport: { width: 412, height: 915, deviceScaleFactor: 2.5 },
      platform: 'Linux armv8l',
      renderer: 'Adreno 650',
      vendor: 'OnePlus'
    },
    {
      regex: /Galaxy Z Flip/i,
      viewport: { width: 360, height: 748, deviceScaleFactor: 3 },
      platform: 'Linux armv8l',
      renderer: 'Adreno 730',
      vendor: 'Samsung'
    },
    {
      regex: /Pixel 5/i,
      viewport: { width: 393, height: 851, deviceScaleFactor: 2.75 },
      platform: 'Linux armv8l',
      renderer: 'Adreno 620',
      vendor: 'Google Inc.'
    },
    {
      regex: /Mi 11 Lite/i,
      viewport: { width: 412, height: 892, deviceScaleFactor: 2.7 },
      platform: 'Linux armv8l',
      renderer: 'Adreno 642L',
      vendor: 'Xiaomi'
    },
    {
      regex: /Samsung Galaxy A51/i,
      viewport: { width: 360, height: 800, deviceScaleFactor: 2.5 },
      platform: 'Linux armv8l',
      renderer: 'Mali-G72 MP3',
      vendor: 'Samsung'
    },
    {
      regex: /Realme 9 Pro/i,
      viewport: { width: 390, height: 844, deviceScaleFactor: 2.8 },
      platform: 'Linux armv8l',
      renderer: 'Adreno 619',
      vendor: 'Realme'
    },
    {
      regex: /POCO X4 Pro/i,
      viewport: { width: 395, height: 850, deviceScaleFactor: 2.7 },
      platform: 'Linux armv8l',
      renderer: 'Adreno 619',
      vendor: 'Xiaomi'
    },
    {
      regex: /Nokia 5.4/i,
      viewport: { width: 360, height: 780, deviceScaleFactor: 2.5 },
      platform: 'Linux armv8l',
      renderer: 'Adreno 610',
      vendor: 'Nokia'
    },
    {
      regex: /Huawei P30 Lite/i,
      viewport: { width: 360, height: 780, deviceScaleFactor: 2.4 },
      platform: 'Linux armv8l',
      renderer: 'Mali-G51 MP4',
      vendor: 'Huawei'
    },
    {
      regex: /Infinix Note 12/i,
      viewport: { width: 393, height: 851, deviceScaleFactor: 2.6 },
      platform: 'Linux armv8l',
      renderer: 'Mali-G57 MC2',
      vendor: 'Infinix'
    },
    {
      regex: /Vivo Y21s/i,
      viewport: { width: 360, height: 780, deviceScaleFactor: 2.5 },
      platform: 'Linux armv8l',
      renderer: 'PowerVR GE8320',
      vendor: 'Vivo'
    },
    {
      regex: /Samsung Galaxy M31/i,
      viewport: { width: 360, height: 800, deviceScaleFactor: 2.75 },
      platform: 'Linux armv8l',
      renderer: 'Mali-G72 MP3',
      vendor: 'Samsung'
    },
    {
      regex: /Realme C25s/i,
      viewport: { width: 360, height: 780, deviceScaleFactor: 2.4 },
      platform: 'Linux armv8l',
      renderer: 'Mali-G52',
      vendor: 'Realme'
    },
    {
      regex: /Xiaomi 13 Pro/i,
      viewport: { width: 400, height: 900, deviceScaleFactor: 3.2 },
      platform: 'Linux armv8l',
      renderer: 'Adreno 740',
      vendor: 'Xiaomi'
    },
    {
      regex: /Oppo Reno5 Lite/i,
      viewport: { width: 360, height: 780, deviceScaleFactor: 2.5 },
      platform: 'Linux armv8l',
      renderer: 'Mali-G57 MC2',
      vendor: 'Oppo'
    },
    {
      regex: /Honor 70/i,
      viewport: { width: 390, height: 844, deviceScaleFactor: 2.8 },
      platform: 'Linux armv8l',
      renderer: 'Adreno 642L',
      vendor: 'Honor'
    },
    {
      regex: /Pixel 3 XL/i,
      viewport: { width: 412, height: 847, deviceScaleFactor: 3.0 },
      platform: 'Linux armv8l',
      renderer: 'Adreno 630',
      vendor: 'Google Inc.'
    }
  ];

  for (const profile of profiles) {
    if (profile.regex.test(userAgent)) return profile;
  }

  return {
    viewport: { width: 390, height: 844, deviceScaleFactor: 3 },
    platform: 'Linux armv8l',
    renderer: 'Adreno (TM) 620',
    vendor: 'Qualcomm'
  };
}





