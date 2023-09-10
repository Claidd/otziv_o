package com.hunt.otziv.b_bots.controller;

import com.hunt.otziv.b_bots.dto.BotDTO;
import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.b_bots.services.StatusBotService;
import com.hunt.otziv.b_bots.utils.BotValidation;
import com.hunt.otziv.u_users.services.service.UserService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

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
        model.addAttribute("all_bots", botService.getAllBots());
        return "bots/bots_list";
    }

    //Открываем страницу добавления бота
    @GetMapping("/bot_add")
    public String botAdd(Model model, BotDTO botsDto){
        model.addAttribute("bot", new BotDTO());
    return "bots/bot_add";
    }

    //Сохраняем нового бота
    @PostMapping ("/bot_add")
    public String saveBot(Model model,@ModelAttribute("bot") @Valid BotDTO botsDto, BindingResult bindingResult, Principal principal){
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
            return "redirect:/bots";
        } else {
            model.addAttribute("bot", botsDto);
            log.info("Ошибка сохранения нового бота");
            return "bots/bot_add";
        }
    }

    //Редактирование бота
    @GetMapping("/edit/{id}")
    public String editBot(@PathVariable(value = "id")  long id, Model model, BotDTO botsDto){
        model.addAttribute("editBotDto", botService.findById(id));
        model.addAttribute("workers", userService.getAllUsersByFio("ROLE_WORKER"));
        model.addAttribute("statuses", statusBotService.findAllBotsStatus());
        return "bots/bot_edit";
    }

    //Обновление бота
    @PostMapping("/edit/{id}")
    public String updateBot(@PathVariable(value = "id")  long id, Model model, @ModelAttribute("editBotDto") @Valid BotDTO botsDto, BindingResult bindingResult){
        System.out.println(botsDto.getWorker());
        /*Проверяем на ошибки*/
        if (bindingResult.hasErrors()) {
            log.info("Сработал биндинг - есть ошибка");
            model.addAttribute("editBotDto", botsDto);
            model.addAttribute("workers", userService.getAllUsersByFio("ROLE_WORKER"));
            model.addAttribute("statuses", statusBotService.findAllBotsStatus());
            return "bots/bot_edit";
        }
        if (botService.updateBot(botsDto, id)){
            log.info("Бот обновлен");
            return "redirect:/bots";
        } else {
            model.addAttribute("editBotDto", botsDto);
            model.addAttribute("workers", userService.getAllUsersByFio("ROLE_WORKER"));
            model.addAttribute("statuses", statusBotService.findAllBotsStatus());
            log.info("Ошибка обновления нового бота");
            return "bots/bot_edit";
        }
    }

    //Удаление бота
    @PostMapping("/delete/{id}")
    public String deleteBot(@PathVariable(value = "id")  long id, Model model, @ModelAttribute("bot") BotDTO botsDto){
        botService.deleteBot(id);
        return "bots/bots_list";
    }

}
