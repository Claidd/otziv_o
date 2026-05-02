package com.hunt.otziv.b_bots.controller;

import com.hunt.otziv.b_bots.dto.BotDTO;
import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.b_bots.services.StatusBotService;
import com.hunt.otziv.b_bots.utils.BotValidation;
import com.hunt.otziv.u_users.services.service.UserService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.security.Principal;
import java.util.Comparator;

@Controller
@Slf4j
@RequestMapping("/bots")
public class BotsController {

    private final BotService botService;
    private final UserService userService;
    private final StatusBotService statusBotService;
    private final BotValidation botValidation;

    public BotsController(BotService botService, UserService userService, StatusBotService statusBotService, BotValidation botValidation) {
        this.botService = botService;
        this.userService = userService;
        this.statusBotService = statusBotService;
        this.botValidation = botValidation;
    }

    //Открываем страницу со списком ботов
    @GetMapping
    public String bots(Model model){
        model.addAttribute("all_bots", botService.getAllBots().stream().sorted(Comparator.comparing(BotDTO :: getFio)));
        return "bots/bots_list";
    } //Открываем страницу со списком ботов

    //Открываем страницу добавления бота
    @GetMapping("/bot_add")
    public String botAdd(Model model){
            model.addAttribute("bot", new BotDTO());
    return "bots/bot_add";
    } //Открываем страницу добавления бота

    //Сохраняем нового бота
    @PostMapping ("/bot_add")
    public String saveBot(Model model,@ModelAttribute("bot") @Valid BotDTO botsDto, BindingResult bindingResult, Principal principal, RedirectAttributes rm){
        log.info("Валидация на совпадение логина");
        botValidation.validate(botsDto, bindingResult);
        log.info("Вход в общую валидацию");
        /*Проверяем на ошибки*/
        if (bindingResult.hasErrors()) {
            model.addAttribute("bot", botsDto);
            return "bots/bot_add";
        }
        if (botService.createBot(botsDto, principal)){
            log.info("Новый бот сохранен");
            rm.addFlashAttribute("saveSuccess", "true");
            return "redirect:/bots/bot_add";
        } else {
            model.addAttribute("bot", botsDto);
            log.info("Ошибка сохранения нового бота");
            return "bots/bot_add";
        }
    } //Сохраняем нового бота

    //Редактирование бота
    @GetMapping("/edit/{id}")
    public String editBot(@PathVariable(value = "id")  long id, Model model, BotDTO botsDto, Principal principal){
        model.addAttribute("editBotDto", botService.findById(id));
        model.addAttribute("workers", userService.findByUserName(principal.getName()).orElseThrow().getWorkers());
        model.addAttribute("statuses", statusBotService.findAllBotsStatus());
//        System.out.println(botService.findById(id));
//        System.out.println(userService.findByUserName(principal.getName()).orElseThrow().getWorkers());
        return "bots/bot_edit";
    } //Редактирование бота

    //Обновление бота
    @PostMapping("/edit/{id}")
    public String updateBot(@PathVariable(value = "id")  long id, Model model, @ModelAttribute("editBotDto") @Valid BotDTO botsDto, BindingResult bindingResult, Principal principal, RedirectAttributes rm){
//        System.out.println(botsDto.getWorker());
//        System.out.println(botsDto);
        /*Проверяем на ошибки*/
        if (bindingResult.hasErrors()) {
            log.info("Сработал биндинг - есть ошибка");
            model.addAttribute("editBotDto", botsDto);
            model.addAttribute("workers", userService.findByUserName(principal.getName()).orElseThrow().getWorkers());
            model.addAttribute("statuses", statusBotService.findAllBotsStatus());
            return "bots/bot_edit";
        }
        if (botService.updateBot(botsDto, id)){
            log.info("Бот обновлен. - " + principal.getName());
//            return "redirect:/bots";
            rm.addFlashAttribute("saveSuccess", "true");
            return "redirect:/worker/bot";
        } else {
            log.info("Бот не обновлен");
            log.info("Ошибка обновления нового бота");
            return "redirect:/bots/edit/{id}";
        }
    } //Обновление бота

    //Удаление бота
    @PostMapping("/delete/{id}")
    public String deleteBot(@PathVariable(value = "id")  long id, Model model, @ModelAttribute("bot") BotDTO botsDto){
        botService.deleteBot(id);
        return "redirect:/worker/bot_list";
//       return "bots/bots_list";
    } //Удаление бота

    private String gerRole(Principal principal) { // Берем роль пользователя
        // Получите текущий объект аутентификации
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // Получите имя текущего пользователя (пользователя, не роль)
        String username = principal.getName();
        // Получите роль пользователя (предположим, что она хранится в поле "role" в объекте User)
        return ((UserDetails) authentication.getPrincipal()).getAuthorities().iterator().next().getAuthority();
    } // Берем роль пользователя

    @GetMapping("/{botId}/browser")
    public String botBrowserPage(@PathVariable Long botId, Model model) {
        BotDTO bot = botService.findById(botId);

        model.addAttribute("botId", bot.getId());
        model.addAttribute("bot", bot);
        return "bots/bot_browser";   // templates/bot-browser.html
    }

}
