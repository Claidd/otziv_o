export class RuntimeReadiness {
  constructor() { this.state = "starting"; }
  get ready() { return this.state === "ready"; }
  async check(probe) {
    this.state = "starting";
    try {
      await probe();
      if (this.state === "starting") this.state = "ready";
      return this.ready;
    } catch {
      if (this.state !== "draining") this.state = "failed";
      return false;
    }
  }
  stop() { this.state = "draining"; }
}

// The fixture is entirely local; readiness does not depend on an arbitrary
// review website, its CAPTCHA, or an external model download.
export async function checkRuntime({ launchBrowser, createOcr }) {
  let browser;
  let ocr;
  try {
    browser = await launchBrowser();
    const session = await browser.newBrowserCDPSession();
    try {
      const result = await session.send("Browser.getBrowserCommandLine");
      if (!Array.isArray(result.arguments) || result.arguments.some((argument) =>
        /^(--no-sandbox|--disable-setuid-sandbox|--disable-seccomp-filter-sandbox|--disable-namespace-sandbox)(=|$)/u.test(argument))) {
        throw new Error("Chromium sandbox was disabled by runtime launch arguments");
      }
    } finally { await session.detach(); }
    const page = await browser.newPage({ viewport: { width: 1000, height: 250 } });
    await page.setContent('<html><body style="margin:30px;background:white;color:black;font:48px sans-serif">OTZIV READY 12345</body></html>');
    const image = await page.screenshot({ type: "png" });
    ocr = await createOcr();
    const text = await ocr.recognize(image);
    if (!/OTZIV\s+READY\s+12345/iu.test(text)) throw new Error("OCR readiness fixture did not match");
  } finally {
    // allSettled starts every cleanup even if one resource cannot be closed.
    const cleanup = await Promise.allSettled([browser?.close(), ocr?.close()]);
    if (cleanup.some((result) => result.status === "rejected")) throw new Error("Runtime cleanup failed");
  }
}
