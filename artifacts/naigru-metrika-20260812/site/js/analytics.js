(function () {
  if (window.naigruAnalyticsScheduled) {
    return;
  }

  window.naigruAnalyticsScheduled = true;

  function loadScript(src) {
    var script = document.createElement("script");

    script.async = true;
    script.src = src;
    document.head.appendChild(script);
  }

  function startAnalytics() {
    if (window.naigruAnalyticsLoaded) {
      return;
    }

    window.naigruAnalyticsLoaded = true;

    window.ym = window.ym || function () {
      (window.ym.a = window.ym.a || []).push(arguments);
    };
    window.ym.l = window.ym.l || 1 * new Date();

    loadScript("https://mc.yandex.ru/metrika/tag.js");
    window.ym(74893675, "init", {
      clickmap: true,
      trackLinks: true,
      accurateTrackBounce: true,
      webvisor: true
    });

    window.dataLayer = window.dataLayer || [];
    window.gtag = window.gtag || function () {
      window.dataLayer.push(arguments);
    };

    loadScript("https://www.googletagmanager.com/gtag/js?id=G-1GZ7KGNNKD");
    window.gtag("js", new Date());
    window.gtag("config", "G-1GZ7KGNNKD");
  }

  function scheduleAnalytics() {
    var events = ["pointerdown", "keydown", "scroll", "touchstart"];
    var timeoutId = window.setTimeout(startAnalytics, 7000);

    function startFromInteraction() {
      window.clearTimeout(timeoutId);
      events.forEach(function (eventName) {
        window.removeEventListener(eventName, startFromInteraction);
      });
      startAnalytics();
    }

    events.forEach(function (eventName) {
      window.addEventListener(eventName, startFromInteraction, { once: true, passive: true });
    });
  }

  if (document.readyState === "complete") {
    scheduleAnalytics();
  } else {
    window.addEventListener("load", scheduleAnalytics, { once: true });
  }
})();
