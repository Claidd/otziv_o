import { chromium } from "playwright";
import { chromiumLaunchArgs } from "./chromium-security.js";

const browser = await chromium.launch({
  headless: true,
  executablePath: process.env.CHROMIUM_EXECUTABLE_PATH || undefined,
  args: chromiumLaunchArgs(),
  timeout: 30_000,
});
try {
  const page = await browser.newPage();
  await page.setContent("<title>r7-sandbox-smoke</title><p>ok</p>");
  if (await page.title() !== "r7-sandbox-smoke") {
    throw new Error("Chromium smoke page did not render");
  }
  console.log("chromium sandbox smoke passed");
} finally {
  await browser.close();
}
