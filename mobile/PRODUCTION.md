# Mobile Production Notes

## Local

- `npm start` runs the browser build on port `4300` through `proxy.conf.json`.
- Native local builds use `src/app/core/mobile-build-target.ts`, currently `http://localhost:8088`.

## Production

- Production native builds replace `mobile-build-target.ts` with `mobile-build-target.prod.ts`.
- The production backend is `https://o-ogo.ru`.

Useful commands:

```powershell
npm run test:unit
npm run verify
npm run verify:prod
npm run cap:sync:prod
npm run cap:android:prod
npm run cap:ios:prod
```

Before store publication, verify Keycloak allows the mobile redirect URI:

```text
otziv://auth/callback
```

Review uploads, payments, and dictionaries should continue to use server APIs only. The mobile app must not persist business entities locally beyond UI state, tokens, and preferences.

Auth tokens are accessed through `MobileAuthStorageService`. Native builds store them in secure storage; browser/local preview falls back to Capacitor Preferences.
The mobile login requests `offline_access`, so Keycloak can keep the app refresh session alive for 30 days when the `otziv-mobile` client is configured by `infrastructure/keycloak/apply-mobile-client.ps1` or the production apply script.
