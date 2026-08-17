(function () {
  var isLocalHost = /^(localhost|127\.0\.0\.1|\[::1\])$/i.test(window.location.hostname);

  if (window.location.protocol !== "file:" && !isLocalHost) {
    return;
  }

  var ownHostPattern = /^https?:\/\/(www\.)?naigru\.ru\/?/i;

  function hasFileExtension(path) {
    return /\.[a-z0-9]{2,8}$/i.test(path);
  }

  function toLocalHref(rawHref) {
    if (!rawHref || rawHref.charAt(0) === "#") {
      return rawHref;
    }

    if (/^(tel:|mailto:|javascript:)/i.test(rawHref)) {
      return rawHref;
    }

    if (/^https?:\/\//i.test(rawHref) && !ownHostPattern.test(rawHref)) {
      return rawHref;
    }

    var href = rawHref.replace(ownHostPattern, "");
    var hash = "";
    var query = "";
    var hashIndex = href.indexOf("#");
    var queryIndex = href.indexOf("?");

    if (hashIndex !== -1) {
      hash = href.slice(hashIndex);
      href = href.slice(0, hashIndex);
    }

    queryIndex = href.indexOf("?");
    if (queryIndex !== -1) {
      query = href.slice(queryIndex);
      href = href.slice(0, queryIndex);
    }

    if (href === "" || href === "/" || href === "./") {
      return "index.html" + query + hash;
    }

    href = href.replace(/^\/+/, "").replace(/\/+$/, "");

    if (href === "" || hasFileExtension(href)) {
      return rawHref;
    }

    return href + ".html" + query + hash;
  }

  function updateLinks() {
    document.querySelectorAll("a[href]").forEach(function (link) {
      var rawHref = link.getAttribute("href");
      var localHref = toLocalHref(rawHref);

      if (localHref && localHref !== rawHref) {
        link.setAttribute("href", localHref);
      }
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", updateLinks);
  } else {
    updateLinks();
  }
})();
