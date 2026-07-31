function selectGroupsCache(forceRefresh, freshCache, cachedSnapshot) {
  if (forceRefresh) {
    return { snapshot: null, stale: false };
  }
  if (freshCache) {
    return { snapshot: freshCache, stale: false };
  }
  if (cachedSnapshot) {
    return { snapshot: cachedSnapshot, stale: true };
  }
  return { snapshot: null, stale: false };
}

module.exports = { selectGroupsCache };
