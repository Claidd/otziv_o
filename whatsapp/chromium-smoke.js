"use strict";

const puppeteer = require("puppeteer");
const { chromiumLaunchArgs } = require("./chromium-launch");

async function main() {
  const browser = await puppeteer.launch({
    executablePath: process.env.PUPPETEER_EXECUTABLE_PATH || "/usr/bin/chromium",
    headless: true,
    timeout: 30000,
    protocolTimeout: 30000,
    args: chromiumLaunchArgs(""),
  });

  try {
    const page = await browser.newPage();
    await page.goto("about:blank", { waitUntil: "load", timeout: 10000 });
  } finally {
    await browser.close();
  }
}

main().catch((error) => {
  console.error(error && error.stack ? error.stack : error);
  process.exit(1);
});
