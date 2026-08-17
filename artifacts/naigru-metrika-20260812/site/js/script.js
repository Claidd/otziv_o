document.addEventListener("DOMContentLoaded", function () {
  if (window.jQuery) {
    var $ = window.jQuery;

    if ($.fn.owlCarousel) {
      $(".slider").owlCarousel({
        items: 1,
        lazyLoad: true,
        loop: true,
        autoplay: true,
        autoplayTimeout: 5000
      });
    }

    $(".btn-nav").on("click", function () {
      var target = $(this).data("target");
      $(target).toggleClass("nav__list--open");
    });

    return;
  }

  document.querySelectorAll(".btn-nav").forEach(function (button) {
    button.addEventListener("click", function () {
      var target = button.getAttribute("data-target");
      var menu = target ? document.querySelector(target) : null;

      if (menu) {
        menu.classList.toggle("nav__list--open");
      }
    });
  });
});
