const { Client, LocalAuth } = require('whatsapp-web.js');
const puppeteer = require('puppeteer');
const qrcodeTerminal = require('qrcode-terminal');
const qrcode = require('qrcode');
const express = require('express');
const bodyParser = require('body-parser');

const clientId = process.env.CLIENT_ID || 'default';
const dataPath = `/root/.config/whatsapp-web.js`; // ✅ путь volume в контейнере
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
      executablePath: '/usr/bin/chromium',
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

  instance.on('message', msg => {
    console.log(`[${id}] Входящее сообщение от ${msg.from}: ${msg.body}`);
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
  if (!client || !client.info || !client.info.wid) {
    return res.status(503).json({ status: 'error', error: 'Клиент не готов или не авторизован' });
  }

  try {
    await client.sendMessage(`${phone}@c.us`, message);
    res.json({ status: 'ok' });
  } catch (e) {
    res.status(500).json({ status: 'error', error: e.message });
  }
});

app.listen(3000, () => {
  console.log(`🟢 API запущено на порту 3000 для клиента ${clientId}`);
});
