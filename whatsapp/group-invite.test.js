const test = require("node:test");
const assert = require("node:assert/strict");
const {
  findGroupByInviteCode,
  groupFromInviteInfo,
  groupFromSnapshot,
  normalizeInviteCode,
  serializedGroupId,
} = require("./group-invite");

test("normalizes a WhatsApp invite URL without losing case-sensitive code", () => {
  assert.equal(
    normalizeInviteCode("https://chat.whatsapp.com/LcXNWVfU4RpHayV7wJOFZw?s=cl&p=i"),
    "LcXNWVfU4RpHayV7wJOFZw"
  );
  assert.equal(normalizeInviteCode("invalid"), "");
});

test("serializes WhatsApp group wid variants", () => {
  assert.equal(serializedGroupId({ _serialized: "120363501@g.us" }), "120363501@g.us");
  assert.equal(serializedGroupId({ user: "120363502", server: "g.us" }), "120363502@g.us");
  assert.equal(serializedGroupId("120363503"), "120363503@g.us");
  assert.equal(serializedGroupId({ user: "79000000000", server: "c.us" }), "");
  for (const invalid of [
    "../../internal@g.us",
    "1203--63503@g.us",
    "120363503@evil.example",
    "1@g.us",
    `${"1".repeat(81)}@g.us`,
  ]) {
    assert.equal(serializedGroupId(invalid), "");
  }
});

test("builds a group from direct invite information", () => {
  assert.deepEqual(
    groupFromInviteInfo({
      id: { user: "120363503", server: "g.us" },
      subject: "Drivevision",
    }, "LcXNWVfU4RpHayV7wJOFZw"),
    {
      groupId: "120363503@g.us",
      id: "120363503@g.us",
      chatId: "120363503@g.us",
      name: "Drivevision",
      inviteCode: "LcXNWVfU4RpHayV7wJOFZw",
      inviteLink: "https://chat.whatsapp.com/LcXNWVfU4RpHayV7wJOFZw",
    }
  );
});

test("rejects invite information without a group id", () => {
  assert.equal(groupFromInviteInfo({ subject: "Drivevision" }, "LcXNWVfU4RpHayV7wJOFZw"), null);
});

test("builds a group from an exact case-sensitive cache match", () => {
  assert.deepEqual(
    groupFromSnapshot({
      groupId: "120363503@g.us",
      name: "Drivevision",
      inviteLink: "https://chat.whatsapp.com/LcXNWVfU4RpHayV7wJOFZw?mode=gi_t",
    }, "LcXNWVfU4RpHayV7wJOFZw"),
    {
      groupId: "120363503@g.us",
      id: "120363503@g.us",
      chatId: "120363503@g.us",
      name: "Drivevision",
      inviteCode: "LcXNWVfU4RpHayV7wJOFZw",
      inviteLink: "https://chat.whatsapp.com/LcXNWVfU4RpHayV7wJOFZw",
    }
  );
  assert.equal(
    groupFromSnapshot({
      groupId: "120363503@g.us",
      inviteCode: "lcxnwvfu4rphayv7wjofzw",
    }, "LcXNWVfU4RpHayV7wJOFZw"),
    null
  );
});

test("finds one unambiguous cached group by invite code", () => {
  const snapshot = {
    groups: [
      {
        groupId: "120363503@g.us",
        name: "Drivevision",
        inviteCode: "LcXNWVfU4RpHayV7wJOFZw",
      },
      {
        groupId: "120363504@g.us",
        name: "Other",
        inviteCode: "OtherInviteCode123456",
      },
    ],
  };

  assert.equal(
    findGroupByInviteCode(snapshot, "https://chat.whatsapp.com/LcXNWVfU4RpHayV7wJOFZw").groupId,
    "120363503@g.us"
  );
});

test("rejects an ambiguous cached invite mapping", () => {
  const snapshot = {
    groups: [
      { groupId: "120363503@g.us", inviteCode: "LcXNWVfU4RpHayV7wJOFZw" },
      { groupId: "120363504@g.us", inviteCode: "LcXNWVfU4RpHayV7wJOFZw" },
    ],
  };

  assert.equal(findGroupByInviteCode(snapshot, "LcXNWVfU4RpHayV7wJOFZw"), null);
});
