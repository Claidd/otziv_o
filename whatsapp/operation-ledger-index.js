"use strict";

/** Recoverable metadata only. JSON claim/result files remain authoritative. */
class OperationLedgerIndex {
  constructor() { this.records = new Map(); this.unknown = 0; this.heap = []; this.positions = new Map(); }
  get size() { return this.records.size; }
  set(key, state, startedAt) {
    const previous = this.records.get(key);
    if (previous?.state === state && previous.startedAt === startedAt) return;
    if (previous?.state === "UNKNOWN") { this.unknown--; this.remove(key); }
    this.records.set(key, { state, startedAt });
    if (state === "UNKNOWN") { this.unknown++; this.push({ key, startedAt }); }
  }
  oldestUnknown() {
    return this.heap[0]?.startedAt ?? null;
  }
  push(value) {
    this.heap.push(value); let index = this.heap.length - 1;
    while (index > 0) {
      const parent = (index - 1) >> 1;
      if (this.heap[parent].startedAt <= value.startedAt) break;
      this.place(index, this.heap[parent]); index = parent;
    }
    this.place(index, value);
  }
  place(index, value) { this.heap[index] = value; this.positions.set(value.key, index); }
  remove(key) {
    let index = this.positions.get(key);
    const tail = this.heap.pop(); this.positions.delete(key);
    if (index >= this.heap.length) return;
    if (index > 0 && tail.startedAt < this.heap[(index - 1) >> 1].startedAt) {
      while (index > 0) {
        const parent = (index - 1) >> 1;
        if (this.heap[parent].startedAt <= tail.startedAt) break;
        this.place(index, this.heap[parent]); index = parent;
      }
      this.place(index, tail); return;
    }
    while (index * 2 + 1 < this.heap.length) {
      let child = index * 2 + 1;
      if (child + 1 < this.heap.length && this.heap[child + 1].startedAt < this.heap[child].startedAt) child++;
      if (this.heap[child].startedAt >= tail.startedAt) break;
      this.place(index, this.heap[child]); index = child;
    }
    this.place(index, tail);
  }
}

module.exports = { OperationLedgerIndex };
