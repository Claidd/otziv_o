(function () {
  var mobileQuery = window.matchMedia("(max-width: 768px)");

  function updateHeaderPhoneLinks() {
    var isMobile = mobileQuery.matches;

    document.querySelectorAll(".header__phone-number > .phone-number").forEach(function (link) {
      var originalHref = link.getAttribute("data-original-href") || link.getAttribute("href") || "";

      if (!link.hasAttribute("data-original-href")) {
        link.setAttribute("data-original-href", originalHref);
      }

      if (isMobile) {
        link.setAttribute("href", "/");
      } else if (originalHref) {
        link.setAttribute("href", originalHref);
      }
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", updateHeaderPhoneLinks);
  } else {
    updateHeaderPhoneLinks();
  }

  if (typeof mobileQuery.addEventListener === "function") {
    mobileQuery.addEventListener("change", updateHeaderPhoneLinks);
  } else if (typeof mobileQuery.addListener === "function") {
    mobileQuery.addListener(updateHeaderPhoneLinks);
  }
})();
