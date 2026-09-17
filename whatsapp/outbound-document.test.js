"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { OperationLedger } = require("./operation-ledger");
const { createDocumentOutboundHandler } = require("./outbound-document");

function setup(t, send) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(),"offer-file-test-"));
  const ledger = new OperationLedger(dir);
  t.after(() => { ledger.close(); fs.rmSync(dir,{recursive:true,force:true}); });
  const handler = createDocumentOutboundHandler({ ledger,clientId:"manager",normalizeDestination: value => value,
    canStart:() => true,send });
  return async (patch = {}) => {
    const req = {get:() => undefined,body:{operationId:"offer:1",groupId:"123456789@g.us",caption:"Новая услуга 😀",
      filename:"предложение.txt",contentType:"text/plain",data:Buffer.from("fixture").toString("base64"),...patch}};
    const res = {code:200,set(){return this;},status(code){this.code=code;return this;},json(body){this.body=body;}};
    await handler(req,res); return res;
  };
}
test("attachment replays require identical bytes, caption and filename",async t => {
  let sends = 0; const call = setup(t,async () => { sends++; return "msg-id"; });
  const initial = await call();
  assert.equal(initial.body.state,"SUCCEEDED");
  assert.equal(initial.body.envelopeHash,"714ee0267e7041d61de2753bb30f1b91ba0544b63cc20f95fa37c235e6f17b75");
  assert.equal((await call()).body.state,"SUCCEEDED");
  assert.equal(sends,1);
  for (const patch of [{caption:"changed"},{filename:"changed.txt"},{data:Buffer.from("changed").toString("base64")}])
    await assert.rejects(call(patch));
  assert.equal(sends,1);
});
test("uncertain attachment delivery is fenced without retry",async t => {
  let sends = 0; const call = setup(t,async () => { sends++; throw new Error("timeout"); });
  assert.equal((await call()).body.state,"UNKNOWN"); assert.equal((await call()).body.state,"UNKNOWN");
  assert.equal(sends,1);
});
test("missing identity, bad media and path filenames never dispatch",async t => {
  const call = setup(t,async () => { throw new Error("must not send"); });
  for (const patch of [{operationId:undefined},{data:"invalid!"},{filename:"../offer.txt"},{data:""}]) await assert.rejects(call(patch));
});
test("supports a full 5 MiB attachment without parser recursion",async t => {
  const call = setup(t,async (_destination,file) => { assert.equal(file.size,5*1024*1024);return "full-size"; });
  assert.equal((await call({data:Buffer.alloc(5*1024*1024,65).toString("base64")})).body.state,"SUCCEEDED");
});
