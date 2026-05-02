$(document).ready(function(){
//  $(".slider").owlCarousel({
//  	items: 1,
//	lazyLoad: true,
//  	loop: true,
//  	autoplay: true,
//  	autoplayTimeout: 5000
//  });
  $(".btn-nav").on("click", function() {
  	var target = $(this).data("target");
  	$(target).toggleClass("nav__list--open");

  });
  $("#btn-nav").on("click", function() {
      	var target = $(this).data("target");
      	$(target).toggleClass("nav__list--open");

      });
});