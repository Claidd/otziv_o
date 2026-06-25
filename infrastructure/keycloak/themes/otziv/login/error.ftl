<#assign appBaseUrl=(properties.appBaseUrl!'')>
<#assign summary=(message.summary!'')>
<#assign lowerSummary=summary?lower_case>
<#assign shouldRestartLogin=appBaseUrl?has_content && lowerSummary?contains("restart login cookie")>
<#assign restartUrl=appBaseUrl + "/auth/restart?reason=keycloak-cookie">
<!doctype html>
<html lang="${locale.currentLanguageTag!'ru'}">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <#if shouldRestartLogin>
      <meta http-equiv="refresh" content="1;url=${restartUrl}">
    </#if>
    <title>${msg("otzivAuthRecoveryTitle")}</title>
    <link rel="stylesheet" href="${url.resourcesPath}/css/otziv-login.css">
  </head>
  <body>
    <main class="login-pf-page">
      <section class="card-pf otziv-login">
        <div class="otziv-login__brand" aria-hidden="true">
          <span class="otziv-login__mark"></span>
          <strong>Компания <span>О!</span></strong>
        </div>

        <div class="otziv-login__intro">
          <#if shouldRestartLogin>
            <h1>${msg("otzivAuthRecoveryTitle")}</h1>
            <p>${msg("otzivAuthRecoveryText")}</p>
          <#else>
            <h1>${msg("errorTitle")}</h1>
            <p>${kcSanitize(summary)?no_esc}</p>
          </#if>
        </div>

        <#if appBaseUrl?has_content>
          <a class="otziv-login__submit otziv-login__submit--link" href="${restartUrl}">
            ${msg("otzivAuthRecoveryAction")}
          </a>
        </#if>
      </section>
    </main>

    <#if shouldRestartLogin>
      <script>
        window.setTimeout(function () {
          window.location.replace("${restartUrl?js_string}");
        }, 250);
      </script>
    </#if>
  </body>
</html>
