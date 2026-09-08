"use strict";
const assert = require("node:assert/strict");
const test = require("node:test");
const { envelopeHash } = require("./operation-ledger");
const { serializedGroupId } = require("./group-invite");
const fixtures = require("../contracts/fixtures/whatsapp-operation-envelope-v1.json");

for (const fixture of fixtures) {
  test(`cross-language operation envelope: ${fixture.name}`, () => {
    const destination = serializedGroupId(fixture.groupId);
    const message = fixture.message?.trim() || "";
    if (fixture.invalid) {
      assert.equal(!destination || !message || !fixture.clientId, true);
      return;
    }
    assert.equal(destination, fixture.destination);
    assert.equal(envelopeHash({ clientId: fixture.clientId, kind: "send-group", destination, message }), fixture.hash);
  });
}
