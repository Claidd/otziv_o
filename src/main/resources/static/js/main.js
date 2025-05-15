// $(".btn-nav").on("click", function() {
//   	var target = $(this).data("target");
//     const logo = document.querySelector('.header');

//   	$(target).toggleClass("nav__list--open");
//     $(logo).toggleClass("logo");

//   });

function goBack(event) {
  event.preventDefault();
  window.history.back();
}

$(document).ready(function() {
  $(".btn-nav").on("click", function() {
      var target = $(this).data("target");
      const logo = document.querySelector('.header');

      $(target).toggleClass("nav__list--open");
      $(logo).toggleClass("logo");
  });
});


function redirect(element) {
    const selectedValue = element.value;
    window.location.href = '/' + selectedValue;
}









// Обработчик события изменения значения в первом селекторе
$("#category").change(function() {
  var categoryId = $(this).val();
  // Отправляем AJAX запрос на сервер для получения списка подкатегорий
    $.ajax({
        url: "/categories/getSubcategories", // URL для обработки запроса на сервере
        data: {categoryId: categoryId}, // Параметр с идентификатором категории
        success: function(data) {
            // При успешном ответе сервера обновляем список подкатегорий во втором селекторе
            $("#subcategory").empty();
            $.each(data, function(index, subcategory) {
                $("#subcategory").append('<option value="' + subcategory.id + '">' + subcategory.subCategoryTitle + '</option>');
            });
        },
        error: function() {
            // Обработка ошибки
            alert("Произошла ошибка при загрузке подкатегорий.");
        }
    });
});



const tabsItems = document.querySelectorAll('.tabs__btn__lead-item');
const tabsContents = document.querySelectorAll('.tabs__content__lead-item, .tabs__content__lead-item2');

tabsItems.forEach(function(element) {
  element.addEventListener('click', open);
});

function open(evt) {
  const tabTarget = evt.currentTarget;
  const button = tabTarget.dataset.button;

  tabsItems.forEach(function(item) {
    item.classList.remove('tabs__btn__lead-item--active');
  });

  tabTarget.classList.add('tabs__btn__lead-item--active');

  tabsContents.forEach(function(item) {
    item.classList.remove('tabs__content__lead-item--active');
  });

  document.querySelector(`#${button}`).classList.add('tabs__content__lead-item--active');
  document.querySelector(`#pagination-${button.slice(-1)}`).classList.add('tabs__content__lead-item--active');

  sessionStorage.setItem('currentTab', button);
}

document.addEventListener("DOMContentLoaded", function() {
  const currentTab = sessionStorage.getItem('currentTab');
  const element = document.querySelector(`[data-button="${currentTab}"]`);
  if (currentTab && element) {
    element.click();
  }
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
    // alert("Copied the text: " + copyText1.value);
  }

  function myFunction2() {
    /* Get the text field */
    var copyText2 = document.getElementById("myInput2");

  
    /* Select the text field */
    copyText2.select();

  
    /* Copy the text inside the text field */
    document.execCommand("copy");
  
    /* Alert the copied text */
    // alert("Copied the text: " + copyText2.value);
  }

  function myFunction3() {
    /* Get the text field */
    var copyText3 = document.getElementById("myInput3");
  
    /* Select the text field */
    copyText3.select();

  
    /* Copy the text inside the text field */
    document.execCommand("copy");
  
    /* Alert the copied text */
    // alert("Copied the text: " + copyText3.value);
  }

  function myFunction4() {
    /* Get the text field */
    var copyText4 = document.getElementById("myInput4");
  
    /* Select the text field */
    copyText4.select();

  
    /* Copy the text inside the text field */
    document.execCommand("copy");
  
    /* Alert the copied text */
    // alert("Copied the text: " + copyText4.value);
  }

  function myFunction5() {
    /* Get the text field */
    var copyText5 = document.getElementById("myInput5");

    /* Select the text field */
    copyText5.select();


    /* Copy the text inside the text field */
    document.execCommand("copy");

    /* Alert the copied text */
    // alert("Copied the text: " + copyText5.value);
  }

  function copyTelephone(button) {
    /* Get the text field */
    var phoneNumber = button.getAttribute("data-ordertel");

    /* Create a temporary textarea element */
    var tempTextArea = document.createElement("textarea");
    tempTextArea.value = phoneNumber;

    /* Append the textarea to the DOM */
    document.body.appendChild(tempTextArea);

    /* Select the text in the textarea */
    tempTextArea.select();

    /* Copy the text inside the textarea */
    document.execCommand("copy");

    /* Remove the temporary textarea from the DOM */
    document.body.removeChild(tempTextArea);

    /* Alert the copied text */
    // alert("Copied the text: " + phoneNumber);
}

  // function copyTelephone(button) {
  //   /* Get the text field */
  //   var phoneNumber = button.getAttribute("data-ordertel");
  //   // var copyTelephone2 = document.getElementById("copyPhone");

  //   /* Select the text field */
  //   phoneNumber.select();


  //   /* Copy the text inside the text field */
  //   document.execCommand("copy");

  //   /* Alert the copied text */
  //   alert("Copied the text: " + phoneNumber.value);
  // }

  function checkAndUrl(button) {
    var orderId = button.getAttribute("data-orderid");
    var companyTitle= button.getAttribute("data-company");
    var filialTitle = button.getAttribute("data-filial");
    var checkAndUrl = document.getElementById("checkAndUrl");
    // Получить текущее значение текстовой области
    var currentText = checkAndUrl.value;
    // Создать новую строку с добавленным orderId
    var newText = companyTitle + '. ' + filialTitle + '.' + '\n\n' + currentText + orderId;
    // Установить новое значение в текстовой области
    checkAndUrl.value = newText;
    // Выделить и скопировать текст
    checkAndUrl.select();
    document.execCommand("copy");
    // Оповестить пользователя
    // alert("Copied the text: " + newText);
    checkAndUrl.value = currentText;
}

function onPayment(button) {
  var onPayment = document.getElementById("onPayment");
  var companyTitle= button.getAttribute("data-company");
  var filialTitle = button.getAttribute("data-filial");
  var sum = button.getAttribute("data-sum");
  var currentText = onPayment.value;
    // Создать новую строку с добавленным orderId
  var newText = companyTitle + '. ' + filialTitle + '.' + '\n\n' + currentText + sum + ' руб.';
    // Установить новое значение в текстовой области
  onPayment.value = newText;
  onPayment.select();
  document.execCommand("copy");
  // alert("Copied the text: " + newText);
  onPayment.value = currentText;
}
    // <!-- ==================== COPY BUTTON REVIEW ====================== -->

    function myFunctionBotLogin(reviewId) { // BOT LOGIN 
      /* Get the text field */
      var copyTextLogin = document.getElementById('botLogin_' + reviewId);
      /* Select the text field */
      copyTextLogin.select();
      /* Copy the text inside the text field */
      document.execCommand("copy");
      /* Alert the copied text */
      // alert("Copied the text: " + copyTextLogin.value);
    }
  
    function myFunctionBotPassword(reviewId) { // BOT PASSWORD 
      /* Get the text field */
      var copyTextLogin = document.getElementById('botPassword_' + reviewId);
      /* Select the text field */
      copyTextLogin.select();
      /* Copy the text inside the text field */
      document.execCommand("copy");
      /* Alert the copied text */
      // alert("Copied the text: " + copyTextLogin.value);
    }
  
    function myFunctionReviewText(reviewId) { // Review Text 
      /* Get the text field */
      var copyTextLogin = document.getElementById('ReviewText_' + reviewId);
      /* Select the text field */
      copyTextLogin.select();
      /* Copy the text inside the text field */
      document.execCommand("copy");
      /* Alert the copied text */
      // alert("Copied the text: " + copyTextLogin.value);
    }
  
    function myFunctionReviewAnswer(reviewId) { // Review Answer 
      /* Get the text field */
      var copyTextLogin = document.getElementById('ReviewAnswer_' + reviewId);
      /* Select the text field */
      copyTextLogin.select();
      /* Copy the text inside the text field */
      document.execCommand("copy");
      /* Alert the copied text */
      // alert("Copied the text: " + copyTextLogin.value);
    }

    function myFunctionVk() {
      /* Get the text field */
      var copyText5 = document.getElementById("myInput5");
      /* Select the text field */
      copyText5.select();
      /* Copy the text inside the text field */
      document.execCommand("copy");
      /* Alert the copied text */
      // alert("Copied the text: " + copyText5.value);
    }

document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('form.change-text-form').forEach(form => {
        form.addEventListener('submit', function () {
            const btn = form.querySelector('button');
            btn.innerText = '⏳ Ждите...';
            btn.disabled = true;
        });
    });
});


    function myFunction2Gis(button) {
      var copyText6 = button.getAttribute("data-orderfilial");
      if (copyText6) {
          navigator.clipboard.writeText(copyText6)
              .then(() => {
                  alert("Не забудьте нагулять аккаунт в 2ГИС. Для этого нужно сделать разные рандомные действия, погулять по карте, понажимать организации, почитать отзывы, посмотреть график работы и т. д.");
              })
              .catch(err => {
                  console.error("Ошибка копирования: ", err);
              });
      } else {
          console.error("Атрибут data-orderfilial не найден");
      }
  }
  
/* Открытие и закрытие меню*/
    function toggleMenu() {
      let menu = document.getElementById("menu");
      let toggleButton = document.querySelector(".menu-toggle");

      if (menu.style.display === "none" || menu.style.display === "") {
          menu.style.display = "block";
          toggleButton.classList.add("active"); // Поворачиваем стрелку
      } else {
          menu.style.display = "none";
          toggleButton.classList.remove("active"); // Возвращаем стрелку обратно
      }
  }

/* Замена бота*/
  function changeBot(event, form) {
    event.preventDefault();
    let formData = new FormData(form);
    let orderId = formData.get("orderId");
    let reviewId = formData.get("reviewId");
    let pageName = formData.get("pageName");
    fetch(`/ordersDetails/${orderId}/change_bot/${reviewId}`, {
        method: "POST",
        body: formData
    })
    .then(response => response.text())  // Получаем HTML с сервера
    .then(updatedReviewsHtml => {
        document.getElementById("reviewsContainer").innerHTML = updatedReviewsHtml;
    })
    .catch(error => console.error("Ошибка при смене бота:", error));
}

/* Замена бота*/
function deActivateBot(event, form) {
  event.preventDefault();
  let formData = new FormData(form);
  let orderId = formData.get("orderId");
  let reviewId = formData.get("reviewId");
  let botId = formData.get("botId");
  let pageName = formData.get("pageName");
  fetch(`/ordersDetails/${orderId}/deactivate_bot/${reviewId}/${botId}`, {
      method: "POST",
      body: formData
  })
  .then(response => response.text())  // Получаем HTML с сервера
  .then(updatedReviewsHtml => {
      document.getElementById("reviewsContainer").innerHTML = updatedReviewsHtml;
  })
  .catch(error => console.error("Ошибка при блокировке бота:", error));
}



    // function myFunction2Gis(button) {
    //   /* Get the text field */
    //   var copyText6 = document.getElementById("myInput6");
    //   /* Select the text field */
    //   copyText6.select();
    //   /* Copy the text inside the text field */
    //   document.execCommand("copy");
    //   /* Alert the copied text */
    //   // alert("Copied the text: " + copyText6.value);
    // }
  
    // <!-- ==================== COPY BUTTON REVIEW ====================== -->

  // <!-- ==================== COPY TEXT ====================== -->




    /* Скрыть сообщение об успешном сохранении через 5 секунд */
   setTimeout(function() {
        var successMessage = document.querySelector('.alert-success');
        if (successMessage) {
            successMessage.style.display = 'none';
        }
    }, 5000);





  // <!-- ==================== SLIDER STRAT ====================== -->

  // const swiper = new Swiper(".swiper", {
  //   effect: "fade",
  //   pagination: {
  //     el: ".swiper-pagination",
  //   },
  //   autoplay: {
  //       delay: 5000,
  //       disableOnInteraction: false,
  //     },
  // });

  // <!-- ===================== SLIDER END ======================= -->




