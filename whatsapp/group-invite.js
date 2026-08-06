function normalizeInviteCode(raw) {
  const value = String(raw || "").trim();
  if (!value) {
    return "";
  }

  const direct = value.match(/^[A-Za-z0-9_-]{10,}$/);
  if (direct) {
    return direct[0];
  }

  const url = value.match(/^(?:https?:\/\/)?chat\.whatsapp\.com\/([A-Za-z0-9_-]{10,})(?:[/?#].*)?$/i);
  return url ? url[1] : "";
}

function serializedGroupId(value) {
  if (!value) {
    return "";
  }
  if (typeof value === "string") {
    const candidate = value.trim();
    if (/^(?=.{8,80}@g\.us$)[0-9]+(?:-[0-9]+)?@g\.us$/u.test(candidate)) {
      return candidate;
    }
    return /^(?=.{8,80}$)[0-9]+(?:-[0-9]+)?$/u.test(candidate) ? `${candidate}@g.us` : "";
  }
  if (typeof value !== "object") {
    return "";
  }

  for (const field of ["_serialized", "serialized"]) {
    const candidate = serializedGroupId(value[field]);
    if (candidate) {
      return candidate;
    }
  }

  const user = String(value.user || "").trim();
  const server = String(value.server || "").trim();
  if (server === "g.us") {
    return serializedGroupId(user);
  }

  return "";
}

function firstText(values) {
  for (const value of values) {
    if (typeof value === "string" && value.trim()) {
      return value.trim();
    }
  }
  return "";
}

function groupFromInviteInfo(info, inviteCode) {
  if (!info || typeof info !== "object") {
    return null;
  }

  const metadata = info.groupMetadata && typeof info.groupMetadata === "object"
    ? info.groupMetadata
    : {};
  const group = info.group && typeof info.group === "object" ? info.group : {};
  const idCandidates = [
    info.groupId,
    info.gid,
    info.id,
    metadata.groupId,
    metadata.id,
    group.groupId,
    group.gid,
    group.id,
  ];
  let groupId = "";
  for (const candidate of idCandidates) {
    groupId = serializedGroupId(candidate);
    if (groupId) {
      break;
    }
  }
  if (!groupId) {
    return null;
  }

  const code = normalizeInviteCode(inviteCode);
  return {
    groupId,
    id: groupId,
    chatId: groupId,
    name: firstText([
      info.subject,
      info.title,
      info.name,
      info.groupSubject,
      metadata.subject,
      metadata.title,
      metadata.name,
      group.subject,
      group.title,
      group.name,
    ]),
    inviteCode: code,
    inviteLink: code ? `https://chat.whatsapp.com/${code}` : null,
  };
}

function groupFromSnapshot(group, inviteCode) {
  if (!group || typeof group !== "object") {
    return null;
  }

  const expectedCode = normalizeInviteCode(inviteCode);
  const actualCode = normalizeInviteCode(
    group.inviteCode || group.inviteLink || group.invite || group.link || group.url
  );
  if (!expectedCode || actualCode !== expectedCode) {
    return null;
  }

  const groupId = serializedGroupId(group.groupId || group.id || group.chatId);
  if (!groupId) {
    return null;
  }

  return {
    groupId,
    id: groupId,
    chatId: groupId,
    name: firstText([group.name, group.title, group.subject]),
    inviteCode: expectedCode,
    inviteLink: `https://chat.whatsapp.com/${expectedCode}`,
  };
}

function findGroupByInviteCode(snapshot, inviteCode) {
  const groups = snapshot && Array.isArray(snapshot.groups) ? snapshot.groups : [];
  const matches = new Map();
  for (const candidate of groups) {
    const group = groupFromSnapshot(candidate, inviteCode);
    if (group) {
      matches.set(group.groupId, group);
    }
  }
  return matches.size === 1 ? matches.values().next().value : null;
}

module.exports = {
  findGroupByInviteCode,
  groupFromInviteInfo,
  groupFromSnapshot,
  normalizeInviteCode,
  serializedGroupId,
};
