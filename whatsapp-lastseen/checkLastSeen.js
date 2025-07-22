const puppeteer = require('puppeteer');
const fs = require('fs');
const express = require('express');

const PHONE_FILE = 'phones.txt';
const OUTPUT_FILE = 'online_seen.json';
const DELAY_MS = 15000;
let lastQrData = null;

async function delay(ms) {
    return new Promise(res => setTimeout(res, ms));
}

async function getLastSeenForNumber(page, phone) {
    const url = `https://web.whatsapp.com/send?phone=${phone}&text&app_absent=0`;
    await page.goto(url, { waitUntil: 'domcontentloaded' });

    try {
        await page.waitForSelector('header span[title]', { timeout: 10000 });

        const status = await page.evaluate(() => {
            const spanElements = document.querySelectorAll('header span');
            for (const el of spanElements) {
                const text = el.textContent.toLowerCase();
                if (text.includes('в сети') || text.includes('был') || text.includes('online') || text.includes('last seen')) {
                    return el.textContent;
                }
            }
            return 'неизвестно';
        });

        return status;
    } catch (err) {
        return 'не удалось получить статус';
    }
}

async function tryGetQR(page) {
    let lastHash = null;
    let stuckCounter = 0;

    for (let i = 0; i < 30; i++) {
        const qrData = await page.evaluate(() => {
            const canvas = document.querySelector('canvas');
            if (!canvas) return null;
            return canvas.toDataURL();
        });

        if (qrData) {
            const currentHash = qrData.slice(100, 120);
            if (currentHash !== lastHash) {
                lastHash = currentHash;
                stuckCounter = 0;
                lastQrData = qrData.replace(/^data:image\/png;base64,/, '');
                console.log(`🔄 Обновился QR-код [${currentHash}]`);
            } else {
                stuckCounter++;
                if (stuckCounter >= 5) {
                    console.warn(`⚠️ QR-код не обновляется уже ${stuckCounter} попыток!`);
                }
            }
        } else {
            console.log('⏳ QR-код пока не доступен...');
        }

        if (lastQrData) break;
        await delay(1000);
    }

    if (!lastQrData) {
        console.error('❌ Не удалось получить QR-код');
    }
}

async function waitForAuthorization(page) {
    console.log('📱 Ожидаем авторизацию...');
    try {
        await page.waitForFunction(() => {
            return !document.querySelector('canvas') &&
                !!document.querySelector('[data-testid="chat-list-search"]');
        }, { timeout: 60000 });
        console.log('✅ Авторизация завершена');
        return true;
    } catch (err) {
        console.error('❌ Авторизация не подтверждена: таймаут');
        return false;
    }
}

async function runChecker() {
    const phones = fs.readFileSync(PHONE_FILE, 'utf8')
        .split('\n')
        .map(p => p.trim())
        .filter(p => p.length > 0);

    const browser = await puppeteer.launch({
        headless: 'new',
        executablePath: '/usr/bin/google-chrome-stable',
        args: ['--no-sandbox', '--disable-setuid-sandbox'],
        userDataDir: './whatsapp-session',
        defaultViewport: null
    });

    const page = await browser.newPage();

    await page.goto('https://web.whatsapp.com');
    console.log('🌐 Открыт WhatsApp Web, ищем QR...');

    await tryGetQR(page);
    const success = await waitForAuthorization(page);

    if (!success) {
        await browser.close();
        process.exit(1);
    }

    const results = {};

    for (const phone of phones) {
        console.log(`📱 Проверка номера: ${phone}`);
        const status = await getLastSeenForNumber(page, phone);
        console.log(`→ Статус: ${status}`);
        results[phone] = status;
        await delay(DELAY_MS);
    }

    fs.writeFileSync(OUTPUT_FILE, JSON.stringify(results, null, 2), 'utf8');
    console.log(`✅ Результаты сохранены в ${OUTPUT_FILE}`);

    await browser.close();
}

// === Express QR сервер ===
const expressApp = express();

expressApp.get('/qr', async (req, res) => {
    if (!lastQrData) {
        return res.status(404).send('<h2>QR-код ещё не сгенерирован</h2>');
    }
    const imgSrc = `data:image/png;base64,${lastQrData}`;
    res.send(`<html><body><h2>QR-код</h2><img src="${imgSrc}" /></body></html>`);
});

expressApp.listen(3000, () => {
    console.log(`🟢 Веб-сервер запущен на http://localhost:3000/qr`);
});

runChecker().catch(err => {
    console.error('❌ Ошибка в скрипте:', err.message);
    process.exit(1);
});


