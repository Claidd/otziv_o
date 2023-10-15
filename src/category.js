
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
