// Only read-only review work is cancellable on disconnect. The gateway must
// keep ambiguous outbound operations owned until their real result/cleanup.
export class TaskCancellation {
  constructor(req, res, limiter) {
    this.control = limiter.control();
    this.resources = new Map();
    this.reason = null;
    this.onAbort = () => this.cancel("disconnect");
    this.onClose = () => { if (!res.writableFinished) this.cancel("disconnect"); };
    this.req = req; this.res = res;
    req.on("aborted", this.onAbort);
    res.on("close", this.onClose);
    if (req.aborted || res.destroyed) this.cancel("disconnect");
  }
  assertActive() {
    if (this.reason) { const error = new Error("Review task cancelled"); error.code = "task_cancelled"; throw error; }
  }
  async own(resource) {
    this.resources.set(resource, null);
    if (this.reason) { await this.closeResource(resource); this.assertActive(); }
    return resource;
  }
  closeResource(resource) {
    if (!this.resources.get(resource)) this.resources.set(resource, Promise.resolve().then(() => resource.close()));
    return this.resources.get(resource);
  }
  cancel(reason) {
    if (this.reason) return;
    this.reason = reason;
    if (reason === "deadline") this.control.timeout(); else this.control.cancel();
    this.control.cleanup();
    // Rejection is observed again in close(); cancellation never certifies cleanup.
    for (const resource of this.resources.keys()) this.closeResource(resource).catch(() => {});
  }
  async close() {
    this.control.cleanup();
    this.req.off("aborted", this.onAbort); this.res.off("close", this.onClose);
    const result = await Promise.allSettled([...this.resources.keys()].map(resource => this.closeResource(resource)));
    if (result.some(value => value.status === "rejected")) throw new Error("Task cleanup failed");
  }
}
