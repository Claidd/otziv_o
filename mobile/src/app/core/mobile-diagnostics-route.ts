/**
 * Paths for diagnostics without identifiers or capability secrets.
 *
 * Public links carry bearer-like capabilities in the path: /pay/<token> and
 * /pay/group/<token> (and their /api/payments/public/... requests) pay an
 * invoice; review links carry the order-detail UUID (the opaque review token
 * itself lives in the fragment, which is dropped). Diagnostics are uploaded
 * to the server and kept in a native ring buffer, so these segments are
 * replaced by placeholders before anything is stored.
 */
export function sanitizeDiagnosticRoute(route: string): string {
  const path = (route || '').split(/[?#]/u, 1)[0] || '/';
  return maskDiagnosticPathSegments(path)
    .replace(/[^a-z0-9_./:-]+/giu, '_')
    .slice(0, 64) || '/';
}

/** Masks capability tokens, UUIDs, numeric ids and other opaque segments of a path. */
export function maskDiagnosticPathSegments(path: string): string {
  return path
    // Matrix parameters (;key=value) can carry values as well.
    .replace(/;[^/]*/gu, '')
    .replace(/^\/pay\/group\/[^/]+/iu, '/pay/group/:token')
    .replace(/^\/pay\/(?!(?:success|fail|group)(?:\/|$))[^/]+/iu, '/pay/:token')
    .replace(/^\/api\/payments\/public\/group\/[^/]+/iu, '/api/payments/public/group/:token')
    .replace(/^\/api\/payments\/public\/(?!group(?:\/|$))[^/]+/iu, '/api/payments/public/:token')
    .replace(/\/device-tokens\/[^/]+/iu, '/device-tokens/:token')
    .replace(/\/[0-9a-f]{8}-[0-9a-f-]{27,}(?=\/|$)/giu, '/:id')
    .replace(/\/\d+(?=\/|$)/gu, '/:id')
    // Any other long opaque segment (route names are short lower-case words).
    .replace(/\/(?=[A-Za-z0-9_%-]{20,}(?:\/|$))(?=[^/]*[0-9A-Z_%])[A-Za-z0-9_%-]+(?=\/|$)/gu, '/:token');
}
