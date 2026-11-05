/**
 * Fixed, localized messages for OAuth error callbacks.
 *
 * error_description arrives in a URL that any app or web page can open
 * (otziv://auth/callback?...), so its text is never shown to the user.
 */
const SIGN_IN_AGAIN = 'Нужно войти заново. Нажмите «Повторить вход».';
const SERVER_UNAVAILABLE = 'Сервер входа временно недоступен. Повторите попытку позже.';

const OIDC_CALLBACK_ERROR_MESSAGES: Readonly<Record<string, string>> = {
  access_denied: 'Вход отменён. Нажмите «Повторить вход», чтобы попробовать снова.',
  login_required: SIGN_IN_AGAIN,
  interaction_required: SIGN_IN_AGAIN,
  consent_required: SIGN_IN_AGAIN,
  account_selection_required: SIGN_IN_AGAIN,
  temporarily_unavailable: SERVER_UNAVAILABLE,
  server_error: SERVER_UNAVAILABLE
};

export const OIDC_CALLBACK_GENERIC_ERROR = 'Не удалось выполнить вход. Попробуйте ещё раз.';

export function oidcCallbackErrorMessage(error: string | null | undefined): string {
  const code = oidcCallbackErrorCode(error);
  return OIDC_CALLBACK_ERROR_MESSAGES[code] ?? OIDC_CALLBACK_GENERIC_ERROR;
}

/** Error code safe for diagnostics: a known OAuth code or "other". */
export function oidcCallbackErrorCode(error: string | null | undefined): string {
  const code = (error ?? '').trim().toLowerCase();
  return Object.prototype.hasOwnProperty.call(OIDC_CALLBACK_ERROR_MESSAGES, code)
    || code === 'invalid_request'
    || code === 'unauthorized_client'
    || code === 'unsupported_response_type'
    || code === 'invalid_scope'
    ? code
    : 'other';
}
