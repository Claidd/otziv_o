(function () {
  var widgets = document.querySelectorAll("[data-booking-widget]");

  if (!widgets.length) {
    return;
  }

  widgets.forEach(initBookingWidget);

  function initBookingWidget(widget) {
    var dateInput = widget.querySelector("[data-booking-date]");
    var refreshButton = widget.querySelector("[data-booking-refresh]");
    var statusBox = widget.querySelector("[data-booking-status]");
    var slotsBox = widget.querySelector("[data-booking-slots]");
    var form = widget.querySelector("[data-booking-form]");
    var modal = widget.querySelector("[data-booking-modal]");
    var modalCloseButtons = widget.querySelectorAll("[data-booking-close]");
    var readableDate = widget.querySelector("[data-booking-readable-date]");
    var dayNumber = widget.querySelector("[data-booking-day-number]");
    var dayName = widget.querySelector("[data-booking-day-name]");
    var selectedBox = widget.querySelector("[data-booking-selected]");
    var timeInput = widget.querySelector("[data-booking-time]");
    var resultBox = widget.querySelector("[data-booking-result]");
    var submitButton = form ? form.querySelector("[type='submit']") : null;
    var endpoints = {
      slots: "booking/slots.php",
      book: "booking/book.php",
      challenge: "booking/challenge.php"
    };
    var previewMode = false;
    var sources = parseSources(widget);
    var currentSource = sources.length ? sources[0].key : "";
    var formLoadedAt = Math.floor(Date.now() / 1000);
    var challengeState = {
      token: "",
      expiresAt: 0
    };

    if (!dateInput || !refreshButton || !statusBox || !slotsBox || !form || !modal || !submitButton) {
      return;
    }

    addSourceInput(form);
    addBotTrap(form);
    addPersonalDataNotice(form, submitButton);

    function todayValue() {
      var now = new Date();
      var offset = now.getTimezoneOffset() * 60000;
      return new Date(now.getTime() - offset).toISOString().slice(0, 10);
    }

    function setMessage(element, text, type) {
      element.textContent = text || "";
      element.classList.remove("is-error", "is-success", "is-preview");

      if (type) {
        element.classList.add("is-" + type);
      }
    }

    function appendSuccessDetail(list, labelText, valueText) {
      if (!valueText && valueText !== 0) {
        return;
      }

      var item = document.createElement("div");
      var label = document.createElement("dt");
      var value = document.createElement("dd");

      item.className = "booking-success__detail";
      label.textContent = labelText;
      value.textContent = valueText;
      item.appendChild(label);
      item.appendChild(value);
      list.appendChild(item);
    }

    function setBookingSuccess(details) {
      var success = document.createElement("div");
      var icon = document.createElement("div");
      var title = document.createElement("h3");
      var message = document.createElement("p");
      var list = document.createElement("dl");
      var actions = document.createElement("div");
      var backButton = document.createElement("button");

      resultBox.textContent = "";
      resultBox.classList.remove("is-error", "is-success", "is-preview");
      resultBox.classList.add("is-success");
      form.classList.add("is-complete");

      success.className = "booking-success";
      icon.className = "booking-success__icon";
      icon.textContent = "✓";
      title.className = "booking-success__title";
      title.textContent = "Запись прошла успешно";
      message.className = "booking-success__message";
      message.textContent = details.message || "Администратор свяжется с вами для подтверждения деталей.";
      list.className = "booking-success__details";

      appendSuccessDetail(list, "Что забронировали", details.quest);
      appendSuccessDetail(list, "Дата и время", details.datetime);
      appendSuccessDetail(list, "Площадка", details.source);
      appendSuccessDetail(list, "ID брони", details.bookingId);
      appendSuccessDetail(list, "Чайная зона", details.teaHouse ? "да" : "нет");
      appendSuccessDetail(list, "Телефон", details.phone);
      appendSuccessDetail(list, "Количество детей/гостей", details.players);

      actions.className = "booking-success__actions";
      backButton.className = "booking-back-button";
      backButton.type = "button";
      backButton.textContent = "Назад к расписанию";
      backButton.addEventListener("click", closeModal);

      success.appendChild(icon);
      success.appendChild(title);
      success.appendChild(message);
      success.appendChild(list);
      actions.appendChild(backButton);
      success.appendChild(actions);
      resultBox.appendChild(success);
    }

    function setLoading(isLoading) {
      refreshButton.disabled = isLoading;
      submitButton.disabled = isLoading;
    }

    function formatDateParts(value) {
      var parts = value.split("-");
      var date = new Date(Number(parts[0]), Number(parts[1]) - 1, Number(parts[2]));
      var days = ["Воскресенье", "Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота"];

      return {
        date: parts[2] + "." + parts[1] + "." + parts[0],
        dayNumber: parts[2],
        dayName: days[date.getDay()]
      };
    }

    function updateDateLabels() {
      var parts = formatDateParts(dateInput.value);

      readableDate.textContent = parts.date;
      dayNumber.textContent = parts.dayNumber;
      dayName.textContent = parts.dayName;
    }

    function sourceLabel(sourceKey) {
      var source = sources.find(function (item) {
        return item.key === sourceKey;
      });

      return source ? source.label : "";
    }

    function setCurrentSource(sourceKey) {
      var input = form.querySelector("[name='quest_source']");

      currentSource = sourceKey || "";

      if (input) {
        input.value = currentSource;
      }
    }

    function openModal(slot, sourceKey) {
      var label = sourceLabel(sourceKey);

      formLoadedAt = Math.floor(Date.now() / 1000);
      form.classList.remove("is-complete");
      setCurrentSource(sourceKey);
      timeInput.value = slot.time;
      selectedBox.textContent = "Выбрано: " + slot.datetime + (label ? ", " + label : "");
      setMessage(resultBox, "", "");
      modal.hidden = false;
      document.body.classList.add("booking-modal-open");
      ensureBookingChallenge().catch(function () {});
      form.querySelector("[name='name']").focus();
    }

    function closeModal() {
      modal.hidden = true;
      document.body.classList.remove("booking-modal-open");
    }

    function clearSelection() {
      timeInput.value = "";
      selectedBox.textContent = "Время не выбрано";
      slotsBox.querySelectorAll(".booking-slot.is-selected").forEach(function (button) {
        button.classList.remove("is-selected");
      });
    }

    function isLocalPreviewAvailable() {
      return window.location.protocol === "file:" || /^(localhost|127\.0\.0\.1|\[::1\])$/i.test(window.location.hostname);
    }

    function createPreviewSlots(date) {
      var slots = [];
      var parts = date.split("-");
      var base = new Date(Number(parts[0]), Number(parts[1]) - 1, Number(parts[2]), 10, 0, 0);

      for (var hour = 10; hour <= 24; hour++) {
        var slotDate = new Date(base.getTime() + ((hour - 10) * 60 * 60 * 1000));
        var label = hour === 24 ? "0:00" : hour + ":00";

        slots.push({
          time: Math.floor(slotDate.getTime() / 1000),
          label: label,
          datetime: formatDateParts(date).date + " " + label,
          status: "available"
        });
      }

      return slots;
    }

    function createPreviewGroups() {
      var activeSources = sources.length ? sources : [{ key: "", label: "" }];

      return activeSources.map(function (source) {
        return {
          source: source,
          slots: createPreviewSlots(dateInput.value)
        };
      });
    }

    function renderPreviewSlots(message) {
      previewMode = true;
      renderSlotGroups(createPreviewGroups());
      setMessage(statusBox, message || "Локальный предпросмотр расписания. Реальные слоты загрузятся на хостинге.", "preview");
    }

    function createSlotButton(slot, sourceKey) {
      var button = document.createElement("button");

      button.type = "button";
      button.className = "booking-slot";
      button.textContent = slot.label;
      button.dataset.time = slot.time;
      button.dataset.datetime = slot.datetime;
      button.dataset.status = slot.status;
      button.dataset.source = sourceKey || "";
      button.disabled = slot.status !== "available";

      button.addEventListener("click", function () {
        if (button.disabled) {
          return;
        }

        clearSelection();
        button.classList.add("is-selected");
        openModal(slot, sourceKey);
      });

      return button;
    }

    function renderSlotGroups(groups) {
      var showSourceRows = groups.length > 1 || (groups.length === 1 && groups[0].source.label);

      slotsBox.innerHTML = "";
      slotsBox.classList.toggle("has-source-rows", showSourceRows);
      clearSelection();

      if (!groups.length || !groups.some(function (group) { return group.slots.length; })) {
        setMessage(statusBox, "На эту дату расписание не найдено.", "error");
        return;
      }

      if (!showSourceRows) {
        groups[0].slots.forEach(function (slot) {
          slotsBox.appendChild(createSlotButton(slot, groups[0].source.key));
        });
      } else {
        groups.forEach(function (group) {
          var row = document.createElement("div");
          var label = document.createElement("div");
          var strip = document.createElement("div");

          row.className = "booking-source-row";
          label.className = "booking-source-row__label";
          strip.className = "booking-source-row__slots";
          label.textContent = group.source.label;

          group.slots.forEach(function (slot) {
            strip.appendChild(createSlotButton(slot, group.source.key));
          });

          row.appendChild(label);
          row.appendChild(strip);
          slotsBox.appendChild(row);
        });
      }

      setMessage(statusBox, "Свободные слоты доступны для выбора.", "success");
    }

    function slotsUrl(date, sourceKey) {
      var sourceParam = sourceKey ? "&source=" + encodeURIComponent(sourceKey) : "";

      return endpoints.slots + "?date=" + encodeURIComponent(date) + sourceParam;
    }

    function loadSlots() {
      var date = dateInput.value;
      var activeSources = sources.length ? sources : [{ key: "", label: "" }];

      if (!date) {
        setMessage(statusBox, "Выберите дату.", "error");
        return;
      }

      updateDateLabels();
      setLoading(true);
      setMessage(statusBox, "Загружаем расписание...", "");
      setMessage(resultBox, "", "");

      if (window.location.protocol === "file:") {
        renderPreviewSlots("Локальный предпросмотр расписания. Откройте страницу на PHP-хостинге, чтобы увидеть реальные слоты.");
        setLoading(false);
        return;
      }

      Promise.all(activeSources.map(function (source) {
        return fetch(slotsUrl(date, source.key), {
          headers: { "Accept": "application/json" }
        })
          .then(readJsonResponse)
          .then(function (data) {
            return {
              source: source,
              slots: data.slots || []
            };
          });
      }))
        .then(function (groups) {
          previewMode = false;
          updateDateLabels();
          renderSlotGroups(groups);
        })
        .catch(function (error) {
          if (isLocalPreviewAvailable()) {
            renderPreviewSlots("Локальный предпросмотр расписания. PHP-эндпоинт сейчас недоступен, поэтому показаны примерные слоты.");
            return;
          }

          slotsBox.innerHTML = "";
          slotsBox.classList.remove("has-source-rows");
          clearSelection();
          setMessage(statusBox, error.message || "Не удалось загрузить расписание.", "error");
        })
        .finally(function () {
          setLoading(false);
        });
    }

    function formDataToJson(formElement) {
      var data = {};
      var formData = new FormData(formElement);

      formData.forEach(function (value, key) {
        data[key] = value;
      });

      data.tea_house = formElement.querySelector("[name='tea_house']")
        ? formElement.querySelector("[name='tea_house']").checked
        : false;
      data.quest_source = currentSource;
      data.page_url = window.location.href.split("#")[0];
      data.page_title = document.title || "";
      data.form_loaded_at = formLoadedAt;
      data.booking_token = challengeState.token;

      return data;
    }

    function ensureBookingChallenge() {
      if (challengeState.token && Date.now() < challengeState.expiresAt) {
        return Promise.resolve(challengeState.token);
      }

      return fetch(endpoints.challenge, {
        headers: { "Accept": "application/json" }
      })
        .then(readJsonResponse)
        .then(function (data) {
          var expiresIn = Number(data.expires_in || 3600);

          challengeState.token = data.token || "";
          challengeState.expiresAt = Date.now() + Math.max(60, expiresIn - 30) * 1000;

          return challengeState.token;
        });
    }

    form.addEventListener("submit", function (event) {
      event.preventDefault();

      if (!timeInput.value) {
        setMessage(resultBox, "Сначала выберите свободное время.", "error");
        return;
      }

      if (previewMode) {
        setMessage(resultBox, "Это локальный предпросмотр. Реальная запись сохранится после загрузки сайта на PHP-хостинг с доступом к старой БД.", "preview");
        return;
      }

      var bookingPayload = formDataToJson(form);

      setLoading(true);
      setMessage(resultBox, "Отправляем бронь...", "");

      ensureBookingChallenge()
        .then(function () {
          return fetch(endpoints.book, {
            method: "POST",
            headers: {
              "Accept": "application/json",
              "Content-Type": "application/json"
            },
            body: JSON.stringify(bookingPayload)
          });
        })
        .then(readJsonResponse)
        .then(function (data) {
          var selectedSlot = slotsBox.querySelector(".booking-slot.is-selected");

          setBookingSuccess({
            message: data.message,
            quest: bookingPayload.quest || "Бронь с сайта",
            datetime: data.datetime || "",
            source: sourceLabel(bookingPayload.quest_source) || "",
            bookingId: data.booking_id || "",
            teaHouse: !!bookingPayload.tea_house,
            phone: bookingPayload.phone || "",
            players: bookingPayload.players || ""
          });
          selectedBox.textContent = "Бронь создана: " + (data.datetime || "");
          form.reset();
          setCurrentSource(currentSource);

          if (selectedSlot) {
            selectedSlot.classList.remove("is-selected");
            selectedSlot.dataset.status = "booked";
            selectedSlot.disabled = true;
          }
        })
        .catch(function (error) {
          setMessage(resultBox, error.message || "Не удалось создать бронь.", "error");
        })
        .finally(function () {
          setLoading(false);
        });
    });

    refreshButton.addEventListener("click", loadSlots);
    dateInput.addEventListener("change", loadSlots);
    modalCloseButtons.forEach(function (button) {
      button.addEventListener("click", closeModal);
    });
    document.addEventListener("keydown", function (event) {
      if (event.key === "Escape" && !modal.hidden) {
        closeModal();
      }
    });

    dateInput.value = todayValue();
    setCurrentSource(currentSource);
    updateDateLabels();
    loadSlots();
  }

  function parseSources(widget) {
    var rawSources = widget.getAttribute("data-booking-sources");
    var singleSource = widget.getAttribute("data-booking-source");
    var singleLabel = widget.getAttribute("data-booking-source-label");

    if (rawSources) {
      return rawSources.split("|").map(function (item) {
        var divider = item.indexOf(":");

        if (divider === -1) {
          return null;
        }

        return {
          key: item.slice(0, divider).trim(),
          label: item.slice(divider + 1).trim()
        };
      }).filter(function (item) {
        return item && item.key && item.label;
      });
    }

    if (singleSource) {
      return [{
        key: singleSource.trim(),
        label: singleLabel || singleSource.trim()
      }];
    }

    return [];
  }

  function addSourceInput(form) {
    if (form.querySelector("[name='quest_source']")) {
      return;
    }

    var input = document.createElement("input");

    input.type = "hidden";
    input.name = "quest_source";
    form.appendChild(input);
  }

  function addBotTrap(form) {
    if (form.querySelector("[name='website']")) {
      return;
    }

    var label = document.createElement("label");
    var input = document.createElement("input");

    label.setAttribute("aria-hidden", "true");
    label.style.cssText = "position:absolute;left:-10000px;top:auto;width:1px;height:1px;overflow:hidden;";
    input.type = "text";
    input.name = "website";
    input.tabIndex = -1;
    input.autocomplete = "off";
    label.appendChild(input);
    form.appendChild(label);
  }

  function addPersonalDataNotice(form, submitButton) {
    if (form.querySelector(".booking-personal-data")) {
      return;
    }

    var notice = document.createElement("p");

    notice.className = "booking-personal-data";
    notice.innerHTML = "Нажимая кнопку, вы соглашаетесь на обработку персональных данных и принимаете <a href=\"politika-personalnyh-dannyh\" target=\"_blank\" rel=\"noopener\">политику обработки персональных данных</a>.";
    submitButton.parentNode.insertBefore(notice, submitButton);
  }

  function readJsonResponse(response) {
    return response.text().then(function (text) {
      var data;
      text = text.replace(/^\uFEFF/, "").trim();

      if (text.indexOf("<?php") === 0) {
        throw new Error("Локальный сервер не выполняет PHP. Проверьте бронирование на хостинге или через PHP-сервер.");
      }

      try {
        data = JSON.parse(text);
      } catch (error) {
        throw new Error("Сервер бронирования вернул некорректный ответ.");
      }

      if (!response.ok || !data.ok) {
        throw new Error(data.message || "Сервер бронирования недоступен.");
      }

      return data;
    });
  }
})();
