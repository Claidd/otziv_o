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

});

// <!-- ==================== COPY TEXT ====================== -->

  function myFunction1() {
    /* Get the text field */
    var copyText1 = document.getElementById("myInput1");
    /* Select the text field */
    copyText1.select();


    /* Copy the text inside the text field */
    document.execCommand("copy");

    /* Alert the copied text */
    alert("Copied the text: " + copyText1.value);
  }

  function myFunction2() {
    /* Get the text field */
    var copyText2 = document.getElementById("myInput2");


    /* Select the text field */
    copyText2.select();


    /* Copy the text inside the text field */
    document.execCommand("copy");

    /* Alert the copied text */
    alert("Copied the text: " + copyText2.value);
  }

  function myFunction3() {
    /* Get the text field */
    var copyText3 = document.getElementById("myInput3");

    /* Select the text field */
    copyText3.select();


    /* Copy the text inside the text field */
    document.execCommand("copy");

    /* Alert the copied text */
    alert("Copied the text: " + copyText3.value);
  }

  function myFunction4() {
    /* Get the text field */
    var copyText4 = document.getElementById("myInput4");

    /* Select the text field */
    copyText4.select();


    /* Copy the text inside the text field */
    document.execCommand("copy");

    /* Alert the copied text */
    alert("Copied the text: " + copyText4.value);
  }

  function myFunctionVk() {
    /* Get the text field */
    var copyText5 = document.getElementById("myInput5");
    /* Select the text field */
    copyText5.select();
    /* Copy the text inside the text field */
    document.execCommand("copy");
    /* Alert the copied text */
    alert("Copied the text: " + copyText5.value);
  }

  function checkAndUrl(button) {
    var orderId = button.getAttribute("data-orderid");
    var checkAndUrl = document.getElementById("checkAndUrl");
    // Получить текущее значение текстовой области
    var currentText = checkAndUrl.value;
    // Создать новую строку с добавленным orderId
    var newText = currentText + orderId;
    // Установить новое значение в текстовой области
    checkAndUrl.value = newText;
    // Выделить и скопировать текст
    checkAndUrl.select();
    document.execCommand("copy");
    // Оповестить пользователя
    alert("Copied the text: " + newText);
    checkAndUrl.value = currentText;
}

function onPayment(button) {
  var onPayment = document.getElementById("onPayment");
  var sum = button.getAttribute("data-sum");
  var currentText = onPayment.value;
    // Создать новую строку с добавленным orderId
  var newText = currentText + sum + ' руб.';
    // Установить новое значение в текстовой области
  onPayment.value = newText;
  onPayment.select();
  document.execCommand("copy");
  alert("Copied the text: " + newText);
  onPayment.value = currentText;
}

  // <!-- ==================== COPY TEXT ====================== -->


  // <!-- ==================== MENU STRAT ====================== -->
    // ищем кнопку
  //   const menuBtn = document.querySelector('.menu__btn');
  //   // ищем само меню
  // const menu = document.querySelector('.menu__list');

  //   // вещаем обработчик на клик
  // menuBtn.addEventListener('click', () => {
  //   // по клику добавляем класс дял меню
  //   menu.classList.toggle('menu__list--active')
  // });

  // <!-- ===================== MENU END ======================= -->


  
  // <!-- ==================== CHOOSE STRAT ====================== -->

  // создаем переменные
//  const tabsItem = document.querySelectorAll('.tabs__btn-item');
//  const tabsContent = document.querySelectorAll('.tabs__content-item');
//
//  // вышаем слушатель
//  tabsItem.forEach(function(element){
//    element.addEventListener('click', open);
//  });
//
//  function open(evt) {
//    const tabTarget = evt.currentTarget;
//    // должны понять .что написано в дата атрибуте
//    const button = tabTarget.dataset.button;
//
//    // удаляем класс
//    tabsItem.forEach(function(item){
//      item.classList.remove('tabs__btn-item--active')
//    });
//
//    // добавляем класс
//    tabTarget.classList.add('tabs__btn-item--active');
//
//    // удаляем класс
//    tabsContent.forEach(function(item){
//      item.classList.remove('tabs__content-item--active');
//    });
//
//    // добавляем класс
//    document.querySelector(`#${button}`).classList.add('tabs__content-item--active');
//  }

  // <!-- ===================== CHOOSE END ======================= -->

    // <!-- ==================== CHOOSE STRAT ====================== -->

  // создаем переменные
//  const tabsItem2 = document.querySelectorAll('.tabs__btn__lead-item');
//  const tabsContent2 = document.querySelectorAll('.tabs__content__lead-item');
//
//  // вышаем слушатель
//  tabsItem2.forEach(function(element){
//    element.addEventListener('click', open);
//  });
//
//  function open(evt) {
//    const tabTarget2 = evt.currentTarget;
//    // должны понять .что написано в дата атрибуте
//    const button = tabTarget2.dataset.button;
//
//    // удаляем класс
//    tabsItem2.forEach(function(item){
//      item.classList.remove('tabs__btn__lead-item--active')
//    });
//
//    // добавляем класс
//    tabTarget2.classList.add('tabs__btn__lead-item--active');
//
//    // удаляем класс
//    tabsContent2.forEach(function(item){
//      item.classList.remove('tabs__content__lead-item--active');
//    });
//
//    // добавляем класс
//    document.querySelector(`#${button}`).classList.add('tabs__content__lead-item--active');
//  }
  // <!-- ===================== CHOOSE END ======================= -->

    // <!-- ==================== CHOOSE COMPANY STRAT ====================== -->

  // создаем переменные
  const tabsItem3 = document.querySelectorAll('.company__tabs__btn-item');
  const tabsContent3 = document.querySelectorAll('.company__tabs__content-item');

  // вышаем слушатель
  tabsItem3.forEach(function(element){
    element.addEventListener('click', open);
  });

  function open(evt) {
    const tabTarget3 = evt.currentTarget;
    // должны понять .что написано в дата атрибуте
    const button = tabTarget3.dataset.button;

    // удаляем класс
    tabsItem3.forEach(function(item){
      item.classList.remove('company__tabs__btn-item--active')
    });

    // добавляем класс
    tabTarget3.classList.add('company__tabs__btn-item--active');

    // удаляем класс
    tabsContent3.forEach(function(item){
      item.classList.remove('company__tabs__content-item--active');
    });

    // добавляем класс
    document.querySelector(`#${button}`).classList.add('company__tabs__content-item--active');
  }
  // <!-- ===================== CHOOSE COMPANY END ======================= -->

  // <!-- ==================== COPY TEXT ====================== -->

  function myFunction1() {
    /* Get the text field */
    var copyText1 = document.getElementById("myInput1");
    /* Select the text field */
    copyText1.select();


    /* Copy the text inside the text field */
    document.execCommand("copy");
  
    /* Alert the copied text */
    alert("Copied the text: " + copyText1.value);
  }

  function myFunction2() {
    /* Get the text field */
    var copyText2 = document.getElementById("myInput2");

  
    /* Select the text field */
    copyText2.select();

  
    /* Copy the text inside the text field */
    document.execCommand("copy");
  
    /* Alert the copied text */
    alert("Copied the text: " + copyText2.value);
  }

  function myFunction3() {
    /* Get the text field */
    var copyText3 = document.getElementById("myInput3");
  
    /* Select the text field */
    copyText3.select();

  
    /* Copy the text inside the text field */
    document.execCommand("copy");
  
    /* Alert the copied text */
    alert("Copied the text: " + copyText3.value);
  }

  function myFunction4() {
    /* Get the text field */
    var copyText4 = document.getElementById("myInput4");
  
    /* Select the text field */
    copyText4.select();

  
    /* Copy the text inside the text field */
    document.execCommand("copy");
  
    /* Alert the copied text */
    alert("Copied the text: " + copyText4.value);
  }// <!-- ==================== COPY TEXT ====================== -->

  // <!-- ==================== COPY BUTTON REVIEW ====================== -->

  function myFunctionBotLogin(reviewId) { // BOT LOGIN 
    /* Get the text field */
    var copyTextLogin = document.getElementById('botLogin_' + reviewId);
    /* Select the text field */
    copyTextLogin.select();
    /* Copy the text inside the text field */
    document.execCommand("copy");
    /* Alert the copied text */
    alert("Copied the text: " + copyTextLogin.value);
  }

  function myFunctionBotPassword(reviewId) { // BOT PASSWORD 
    /* Get the text field */
    var copyTextLogin = document.getElementById('botPassword_' + reviewId);
    /* Select the text field */
    copyTextLogin.select();
    /* Copy the text inside the text field */
    document.execCommand("copy");
    /* Alert the copied text */
    alert("Copied the text: " + copyTextLogin.value);
  }

  function myFunctionReviewText(reviewId) { // Review Text 
    /* Get the text field */
    var copyTextLogin = document.getElementById('ReviewText_' + reviewId);
    /* Select the text field */
    copyTextLogin.select();
    /* Copy the text inside the text field */
    document.execCommand("copy");
    /* Alert the copied text */
    alert("Copied the text: " + copyTextLogin.value);
  }

  function myFunctionReviewAnswer(reviewId) { // Review Answer 
    /* Get the text field */
    var copyTextLogin = document.getElementById('ReviewAnswer_' + reviewId);
    /* Select the text field */
    copyTextLogin.select();
    /* Copy the text inside the text field */
    document.execCommand("copy");
    /* Alert the copied text */
    alert("Copied the text: " + copyTextLogin.value);
  }

  // <!-- ==================== COPY BUTTON REVIEW ====================== -->




  // <!-- ==================== SLIDER STRAT ====================== -->

  const swiper = new Swiper(".swiper", {
    effect: "fade",
    pagination: {
      el: ".swiper-pagination",
    },
    autoplay: {
        delay: 5000,
        disableOnInteraction: false,
      },
  });

  // <!-- ===================== SLIDER END ======================= -->

