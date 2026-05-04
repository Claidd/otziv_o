<#import "template.ftl" as layout>

<@layout.registrationLayout displayMessage=!messagesPerField.existsError('username','password') displayInfo=false; section>
  <#if section = "header">
    ${msg("otzivLoginTitle")}
  <#elseif section = "form">
    <#assign appBaseUrl=(properties.appBaseUrl!'')>

    <section class="otziv-login">
      <div class="otziv-login__brand" aria-hidden="true">
        <span class="otziv-login__mark"></span>
        <strong>Компания<span>О!</span></strong>
      </div>

      <div class="otziv-login__intro">
        <h1>${msg("otzivLoginTitle")}</h1>
        <p>${msg("otzivLoginSubtitle")}</p>
      </div>

      <#if realm.password>
        <form id="kc-form-login" class="otziv-login__form" onsubmit="login.disabled = true; return true;" action="${url.loginAction}" method="post">
          <#if !usernameHidden??>
            <label class="otziv-login__field" for="username">
              <span><#if !realm.loginWithEmailAllowed>${msg("username")}<#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}<#else>${msg("email")}</#if></span>
              <input
                tabindex="1"
                id="username"
                name="username"
                value="${(login.username!'')}"
                type="text"
                autofocus
                autocomplete="${(enableWebAuthnConditionalUI?has_content)?then('username webauthn', 'username')}"
                aria-invalid="<#if messagesPerField.existsError('username','password')>true</#if>"
                dir="ltr"
              >
            </label>
          </#if>

          <label class="otziv-login__field" for="password">
            <span>${msg("password")}</span>
            <input
              tabindex="2"
              id="password"
              name="password"
              type="password"
              autocomplete="current-password"
              aria-invalid="<#if messagesPerField.existsError('username','password')>true</#if>"
              dir="ltr"
            >
          </label>

          <#if messagesPerField.existsError('username','password')>
            <p id="input-error" class="otziv-login__error" aria-live="polite">
              ${kcSanitize(messagesPerField.getFirstError('username','password'))?no_esc}
            </p>
          </#if>

          <div class="otziv-login__options">
            <#if realm.rememberMe && !usernameHidden??>
              <label class="otziv-login__remember">
                <input tabindex="3" id="rememberMe" name="rememberMe" type="checkbox" <#if login.rememberMe??>checked</#if>>
                <span>${msg("rememberMe")}</span>
              </label>
            </#if>

            <#if realm.resetPasswordAllowed>
              <a tabindex="4" href="${url.loginResetCredentialsUrl}">${msg("doForgotPassword")}</a>
            </#if>
          </div>

          <input type="hidden" id="id-hidden-input" name="credentialId" <#if auth.selectedCredential?has_content>value="${auth.selectedCredential}"</#if>>

          <button tabindex="5" class="otziv-login__submit" name="login" id="kc-login" type="submit">
            ${msg("doLogIn")}
          </button>

          <#if appBaseUrl?has_content>
            <div class="otziv-login__links">
              <a href="${appBaseUrl}/register-client">${msg("otzivRegisterLink")}</a>
              <a href="${appBaseUrl}/legacy-migration">${msg("otzivMigrationLink")}</a>
            </div>
          </#if>
        </form>
      </#if>
    </section>
  <#elseif section = "socialProviders">
    <#if realm.password && social?? && social.providers?has_content>
      <section class="otziv-social">
        <h2>${msg("identity-provider-login-label")}</h2>
        <div class="otziv-social__list">
          <#list social.providers as p>
            <a id="social-${p.alias}" href="${p.loginUrl}" class="otziv-social__item">
              ${p.displayName!}
            </a>
          </#list>
        </div>
      </section>
    </#if>
  </#if>
</@layout.registrationLayout>
