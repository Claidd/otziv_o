import { createWorker } from "tesseract.js";

// Keep Tesseract's own Worker threads inside one disposable process. Its v5
// terminate() resolves before the thread exits, so it cannot own a task permit.
let worker;
process.on("message", async (request) => {
  try {
    if (request.type === "initialize") {
      worker = await createWorker("rus+eng", undefined, {
        langPath: request.modelDirectory,
        cachePath: request.cacheDirectory,
        cacheMethod: "none",
        gzip: true,
        logger: () => {},
        errorHandler: () => {},
      });
      process.send?.({ id: request.id, ok: true });
    } else if (request.type === "recognize" && worker) {
      const result = await worker.recognize(request.image);
      process.send?.({ id: request.id, ok: true, text: result?.data?.text || "" });
    } else {
      process.send?.({ id: request.id, ok: false });
    }
  } catch {
    process.send?.({ id: request.id, ok: false });
  }
});
process.on("disconnect", () => process.exit(0));
