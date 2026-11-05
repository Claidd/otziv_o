/**
 * Photo links: the API returns a short-lived signed path (`/images/{id}?exp=…&sig=…`) next to every
 * `imageId`. The bare id path is only a fallback for a backend that does not sign links yet; the
 * server stops accepting it once older installed versions are retired.
 */
export function backendImageUrl(backendBaseUrl: string, imageId: number, signedPath?: string | null): string {
  if (signedPath && signedPath.startsWith('/images/')) {
    return `${backendBaseUrl}${signedPath}`;
  }
  return `${backendBaseUrl}/images/${imageId}`;
}
