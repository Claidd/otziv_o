export interface MobileViewportMode {
  compactPhone: boolean;
  roomyPhone: boolean;
  shortPhone: boolean;
}

export function mobileViewportMode(width: number, height: number): MobileViewportMode {
  const safeWidth = Math.max(0, Math.round(width || 0));
  const safeHeight = Math.max(0, Math.round(height || 0));
  const compactPhone = safeWidth <= 380 || safeHeight <= 830 || (safeWidth <= 390 && safeHeight <= 840);
  const shortPhone = safeHeight <= 790 || (safeWidth <= 380 && safeHeight <= 810);
  const roomyPhone = !compactPhone && safeWidth >= 390 && safeHeight >= 840;

  return { compactPhone, roomyPhone, shortPhone };
}
