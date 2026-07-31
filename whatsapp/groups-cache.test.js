const test = require("node:test");
const assert = require("node:assert/strict");
const { selectGroupsCache } = require("./groups-cache");

test("returns a fresh cache without marking it stale", () => {
  const fresh = { groups: [{ groupId: "fresh@g.us" }] };

  assert.deepEqual(selectGroupsCache(false, fresh, { groups: [] }), {
    snapshot: fresh,
    stale: false,
  });
});

test("returns stale cache immediately when fresh cache expired", () => {
  const stale = { groups: [{ groupId: "stale@g.us" }] };

  assert.deepEqual(selectGroupsCache(false, null, stale), {
    snapshot: stale,
    stale: true,
  });
});

test("force refresh bypasses every cache", () => {
  const cached = { groups: [{ groupId: "cached@g.us" }] };

  assert.deepEqual(selectGroupsCache(true, cached, cached), {
    snapshot: null,
    stale: false,
  });
});
