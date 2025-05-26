const { Client, LocalAuth } = require('whatsapp-web.js');
const puppeteer = require('puppeteer');
const qrcodeTerminal = require('qrcode-terminal');
const qrcode = require('qrcode');
const express = require('express');
const bodyParser = require('body-parser');
const axios = require('axios');
const path = require('path');
const os = require('os');

const clientId = process.env.CLIENT_ID || 'default';
const serverUrl = process.env.SERVER_URL || 'http://localhost:8080';
const dataPath = process.env.AUTH_PATH || path.join(os.homedir(), '.wwebjs_auth');
const qrStore = {};
let client;

const makeClient = (id) => {
  const instance = new Client({
    authStrategy: new LocalAuth({
      clientId: id,
      dataPath: dataPath
    }),
    puppeteer: {
      headless: true,
      executablePath: puppeteer.executablePath(),
      args: ['--no-sandbox', '--disable-setuid-sandbox']
    }
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




