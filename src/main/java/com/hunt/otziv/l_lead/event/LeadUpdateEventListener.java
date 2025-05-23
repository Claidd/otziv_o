package com.hunt.otziv.l_lead.event;

import com.hunt.otziv.l_lead.dto.LeadUpdatedEvent;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.services.serv.LeadService;
import com.hunt.otziv.l_lead.services.serv.LeadTransferService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.repository.OperatorRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
/**

    🔄 Пошагово как работает синхронизация

    1. Пользователь на сайте нажимает кнопку "Сменить статус на 'В работе'"
    2. Контроллер или сервис меняет поле lead.lidStatus и вызывает leadService.save(lead)
    3. После успешного сохранения вызывается leadEventPublisher.publishUpdate(lead)
    4. Событие LeadUpdatedEvent помещается в очередь Spring’а
    5. После окончания транзакции Spring вызывает onLeadUpdated(...)
    6. Внутри listener-а подгружается обновлённый Lead из базы
    7. Вызывается sendLeadUpdate(lead), он мапится в LeadUpdateDto и отправляется на сервер
    8. Если сервер не отвечает — делается 3 попытки с логированием
    9. На сервере контроллер принимает PATCH, обновляет запись в базе
**/

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "lead.sync.outbound.enabled", havingValue = "true", matchIfMissing = false)
public class LeadUpdateEventListener {

    private final LeadService leadService;
    private final LeadTransferService leadTransferService;

    @PostConstruct
    public void init() {
        log.info("✅ LeadUpdateEventListener инициализирован");
    }

    @TransactionalEventListener
    public void onLeadUpdated(LeadUpdatedEvent event) {
        Lead lead = leadService.findByIdOptional(event.leadId()).orElse(null);
        if (lead == null) return;

        leadTransferService.sendLeadUpdate(lead);
    }
}

