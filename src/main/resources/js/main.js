  // <!-- ==================== MENU STRAT ====================== -->
    // ищем кнопку
  const menuBtn = document.querySelector('.menu__btn');
    // ищем само меню
  const menu = document.querySelector('.menu__list');

    // вещаем обработчик на клик
  menuBtn.addEventListener('click', () => {
    // по клику добавляем класс дял меню
    menu.classList.toggle('menu__list--active')
  });

  // <!-- ===================== MENU END ======================= -->


  // <!-- ==================== CHOOSE STRAT ====================== -->

  // создаем переменные
  const tabsItem = document.querySelectorAll('.tabs__btn-item');
  const tabsContent = document.querySelectorAll('.tabs__content-item');

  // вышаем слушатель
  tabsItem.forEach(function(element){
    element.addEventListener('click', open);
  });

  function open(evt) {
    const tabTarget = evt.currentTarget;
    // должны понять .что написано в дата атрибуте
    const button = tabTarget.dataset.button;

    // удаляем класс
    tabsItem.forEach(function(item){
      item.classList.remove('tabs__btn-item--active')
    });

    // добавляем класс
    tabTarget.classList.add('tabs__btn-item--active');

    // удаляем класс
    tabsContent.forEach(function(item){
      item.classList.remove('tabs__content-item--active');
    });

    // добавляем класс
    document.querySelector(`#${button}`).classList.add('tabs__content-item--active');
  }

  // <!-- ===================== CHOOSE END ======================= -->

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

// const swiper = new Swiper('.swiper', {
//     // Optional parameters
//     direction: 'vertical',
//     loop: true,
  
//     // If we need pagination
//     pagination: {
//       el: '.swiper-pagination',
//     },
  
//     // Navigation arrows
//     navigation: {
//       nextEl: '.swiper-button-next',
//       prevEl: '.swiper-button-prev',
//     },
  
//     // And if we need scrollbar
//     scrollbar: {
//       el: '.swiper-scrollbar',
//     },
//   });