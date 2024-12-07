package com.hunt.otziv.b_bots.services;

import com.hunt.otziv.b_bots.dto.BotDTO;
import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.model.StatusBot;
import com.hunt.otziv.b_bots.repository.BotsRepository;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.security.Principal;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@Slf4j
public class BotServiceImpl implements BotService {
    private final UserService userService;
    private final StatusBotService statusBotService;
    private final BotsRepository botsRepository;
    private final WorkerService workerService;

    public BotServiceImpl(UserService userService, StatusBotService statusBotService, BotsRepository botsRepository, WorkerService workerService) {
        this.userService = userService;
        this.statusBotService = statusBotService;
        this.botsRepository = botsRepository;
        this.workerService = workerService;
    }

    @Override
    public boolean createBot(BotDTO botDTO, Principal principal) { // Создать нового бота
        botsRepository.save(toEntity(botDTO, principal));
        return true;
    } // Создать нового бота

    // Обновить бота
    @Override
    @Transactional
    public boolean updateBot(BotDTO botDTO, Long id) { // Обновление бота
        log.info("Вошли в обновление бота и ищем бота по id");
        /*Ищем пользоваеля, если пользователь не найден, то выбрасываем сообщение с ошибкой*/
        Bot saveBot = findBotById(botDTO.getId());
        log.info("Достали бота по ид из дто");
        boolean isChanged = false;

        /*Проверяем не равен ли телефон предыдущему, если нет, то меняем флаг на тру*/
        if (!Objects.equals(botDTO.getLogin(), saveBot.getLogin())){
            saveBot.setLogin(botDTO.getLogin());
            isChanged = true;
            log.info("Обновили телефон-логин");
        }
        /*Проверяем, не равен ли пароль предыдущему */
        if (botDTO.getPassword() != null && !botDTO.getPassword().isEmpty() && !Objects.equals(botDTO.getPassword(), saveBot.getPassword())){
            saveBot.setPassword(botDTO.getPassword());
            isChanged = true;
            log.info("Обновили пароль");
        }
        /*Проверяем не равен ли ФИО предыдущему, если нет, то меняем флаг на тру*/
        if (!Objects.equals(botDTO.getFio(), saveBot.getFio())){
            saveBot.setFio(botDTO.getFio());
            isChanged = true;
            log.info("Обновили ФИО");
        }
        /*Проверяем не равен ли Владельц предыдущему, если нет, то меняем флаг на тру*/
        if (!Objects.equals(botDTO.getWorker().getId(), saveBot.getWorker().getId())){
            saveBot.setWorker(workerService.getWorkerById(botDTO.getWorker().getId()));
            isChanged = true;
            log.info("Обновили Владельца");
        }

        /*Проверяем не равен ли Статус время предыдущему, если нет, то меняем флаг на тру*/
        if (!Objects.equals(botDTO.getStatus(), saveBot.getStatus().getBotStatusTitle())){
            saveBot.setStatus(statusBotService.findByTitle(botDTO.getStatus()));
            isChanged = true;
            log.info("Обновили Статус");
        }
        /*Проверяем не равены ли  кол-ва публикаций, если нет, то меняем флаг на тру*/
        if (!Objects.equals(botDTO.getCounter(), saveBot.getCounter())){
            saveBot.setCounter(botDTO.getCounter());
            isChanged = true;
            log.info("Обновили кол-во публикаций");
        }
        /*Проверяем не равен ли флаг активности, если нет, то меняем флаг на тру*/
        if (!Objects.equals(botDTO.isActive(), saveBot.isActive())){
            saveBot.setActive(botDTO.isActive());
            isChanged = true;
            log.info("Обновили Активность");
        }
        /*Проверяем не равен ли флаг города, если нет, то меняем флаг на тру*/
        if (!Objects.equals(botDTO.getBotCity(), saveBot.getBotCity())){
            saveBot.setBotCity(botDTO.getBotCity());
            isChanged = true;
            log.info("Обновили Город");
        }
        /*если какое-то изменение было и флаг сменился на тру, то только тогда мы изменяем запись в БД
         * А если нет, то и обращаться к базе данны и грузить ее мы не будем*/
        if  (isChanged){
            log.info("Начали сохранять обновленного лида в БД");
            botsRepository.save(saveBot);
            log.info("Сохранили обновленного лида в БД");
            return true;
        }
        else {
            log.info("Изменений не было, Бот в БД не изменена");
            return false;
        }
    } // Обновление бота

    // Удалить бота
    @Override
    public void deleteBot(Long id) { // Обновление бота
         botsRepository.deleteById(id);
    } // Обновление бота

    // Найти бота по id
    @Override
    public BotDTO findById(Long id) { // Найти бота по id
        Bot bot = botsRepository.findById(id).orElse(null);
        if(bot == null){
            throw new UsernameNotFoundException("User not found with name: " + bot.getLogin());
        }
        return toDto(bot);
    } // Найти бота по id

    @Override
    public BotDTO findByWorker(Principal principal) { // Найти бота по Работнику
        Worker worker = workerService.getWorkerByUserId(Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getId());
        log.info("вошли в поиск бота по работнику");
        System.out.println(botsRepository.findFirstByWorkerOrderByIdDesc(worker).orElse(null));
        if (worker != null){
            log.info("работник не нулл");
            Bot bot = botsRepository.findFirstByWorkerOrderByIdDesc(worker).orElse(null);
            if(bot != null){
                log.info("бот не нулл");
                BotDTO botDTO = new BotDTO();
                botDTO.setPassword(bot.getPassword());
                return botDTO;
            }
            else throw new UsernameNotFoundException("User not found with name: " + bot.getLogin());
        }
        else return new BotDTO();
    } // Найти бота по Работнику

    @Override
    public Bot findBotById(Long id) { // Найти бота по id
        /*Ищем пользоваеля, если пользователь не найден, то выбрасываем сообщение с ошибкой*/
        Bot saveBot = botsRepository.findById(id).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель с ID '%s' не найден", id)
        ));
        return saveBot;
    } // Найти бота по id

    @Override
    public List<BotDTO> getAllBots() { // Найти всех ботов
        log.info("Берем все юзеров");
        return botsRepository.findAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    } // Найти всех ботов

    public List<Bot> getAllBotsByWorkerId(Long id){ // Взять всех ботов по id работника
        return botsRepository.findAllByWorkerId(id);
    } // Взять всех ботов по id работника
    public List<Bot> getAllBotsByWorker(Principal principal){ // Взять всех ботов по работнику
        Worker worker = workerService.getWorkerByUserId(Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getId());
        return botsRepository.findAllByWorker(worker);
    } // Взять всех ботов по работнику

    @Override
    public StatusBot changeStatus(String status) { // взять статус бота по строке
        return statusBotService.findByTitle(status);
    } // взять статус бота по строке

    public List<Bot> getAllBotsByWorkerIdActiveIsTrue(Long id){ // Взять всех ботов по id работнику и активности
        return botsRepository.findAllByWorkerIdAndActiveIsTrue(id);
    } // Взять всех ботов по id работнику и активности

    public List<BotDTO> getAllBotsByWorkerActiveIsTrue(Principal principal){ // Взять всех ботов по работнику и активности
        Worker worker = workerService.getWorkerByUserId(Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getId());
        return botsRepository.findAllByWorkerAndActiveIsTrue(worker).stream().map(this::toDto).collect(Collectors.toList());
    } // Взять всех ботов по работнику и активности

    public List<Bot> getFindAllByFilialCityId(Long cityId){ // Взять всех ботов по id работнику и активности
        return botsRepository.findAllByFilialCityId(cityId);
    } // Взять всех ботов по id работнику и активности


    @Override
    public Bot save(Bot bot) { // Сохранение ботов
        return botsRepository.save(bot);
    } // Сохранение ботов

    private BotDTO toDto(Bot bot){ // Перевод бота в дто - начало
        return BotDTO.builder()
                .id(bot.getId())
                .login(bot.getLogin())
                .password(bot.getPassword())
                .fio(bot.getFio())
                .active(bot.isActive())
                .counter(bot.getCounter())
                .status(bot.getStatus().getBotStatusTitle())
                .worker(bot.getWorker() != null ? bot.getWorker() : null)
                .botCity(bot.getBotCity())
                .build();
    } // Перевод бота в дто - конец

    //    =============================== ПЕРЕВОД ДТО В СУЩНОСТЬ - НАЧАЛО =========================================
    public Bot toEntity(BotDTO botDTO, Principal principal){ // Перевод дто в сущность
        log.info("Заходим в метод перевода ДТО в Бота");
        User user = Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null));
        Worker worker = workerService.getWorkerByUserId(user.getId());
        Bot bot = Bot.builder()
                .login(botDTO.getLogin())
                .password(botDTO.getPassword())
                .fio(botDTO.getFio())
                .active(true)
                .counter(botDTO.getCounter())
                .status(statusBotService.findByTitle("Новый"))
                .worker( worker != null ? worker  : user.getWorkers().iterator().next())
                .build();
        log.info("БотДТО успешно переведен в Бот");
        return bot;
    } // Перевод дто в сущность
    //    =============================== ПЕРЕВОД ДТО В СУЩНОСТЬ - КОНЕЦ =========================================

    public String changeNumberPhone(String phone){ // Вспомогательный метод для корректировки номера телефона
        String[] a;
        a = phone.split("9");
        a[0] = "+79";
        String b = a[0] + a[1];
        System.out.println(b);
        return b;
    } // Вспомогательный метод для корректировки номера телефона


}



