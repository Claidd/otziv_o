package com.hunt.otziv.c_cities.controller;

import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_cities.dto.CityDTO;
import com.hunt.otziv.c_cities.repository.CityRepository;
import com.hunt.otziv.c_cities.sevices.CityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;

@Controller
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/cities")
public class CitiesController {

    private final CityService cityService;

    @GetMapping() // Страница просмотра городов
    public String AllCitiesList(@RequestParam(defaultValue = "") String keyword, @RequestParam(defaultValue = "Все") String status, Model model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber){
        long startTime = System.nanoTime();

            log.info("Зашли в список просмотра всех Городов");
            model.addAttribute("cities", cityService.getAllCities());
            model.addAttribute("cityDTO", new CityDTO());
            checkTimeMethod("Время выполнения CitiesController/city  city/city  для Админа: ", startTime);
            return "city/city";
    } // Страница просмотра городов


    @PostMapping
    public String createNewCity(@ModelAttribute("cityDTO") CityDTO cityDTO, RedirectAttributes rm) { // Создание новой категории
        if (cityService.saveNewCity(cityDTO)){
            rm.addFlashAttribute("saveSuccess", "true");
            return "redirect:/cities";
        }
        return "redirect:/cities";
    } // Создание новой категории

    @GetMapping("/update/{id}")
    public String editCategory(@PathVariable Long id, Model model) { // Обновление города
        model.addAttribute("cityDTO", cityService.getCityById(id));
        return "city/edit_city";
    } // Обновление города

    @PostMapping("/update/{id}")
    public String updateCategory(@PathVariable Long id, @ModelAttribute("cityDTO") CityDTO cityDTO, RedirectAttributes rm) { // Обновление категории
        cityService.updateCity(cityDTO);
        rm.addFlashAttribute("saveSuccess", "true");
        return "redirect:/cities/update/{id}";
    } // Обновление города

    @PostMapping("/delete/{id}")
    public String deleteCategory(@PathVariable Long id, RedirectAttributes rm) { // Удаление города
        log.info("входим в удаление");
        cityService.deleteCity(id);
        rm.addFlashAttribute("saveSuccess", "true");
        return "redirect:/cities";
    } // Удаление города



    private void checkTimeMethod(String text, long startTime){
        long endTime = System.nanoTime();
        double timeElapsed = (endTime - startTime) / 1_000_000_000.0;
        System.out.printf(text + "%.4f сек%n", timeElapsed);
    }

    private String getRole(Principal principal){
        // Получите текущий объект аутентификации
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // Получите имя текущего пользователя (пользователя, не роль)
        String username = principal.getName();
        // Получите роль пользователя (предположим, что она хранится в поле "role" в объекте User)
        return ((UserDetails) authentication.getPrincipal()).getAuthorities().iterator().next().getAuthority();
    } // Берем роль пользователя
}
