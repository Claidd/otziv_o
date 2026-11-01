package com.hunt.otziv.u_users.service;

import com.hunt.otziv.u_users.api.PaymentNoticeRecipients;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class PaymentNoticeRecipientsService implements PaymentNoticeRecipients {
    private final UserService users;
    private final UserRepository userRepository;
    private final ManagerRepository managers;
    @Transactional(readOnly=true)
    public List<Recipient> forManagers(Collection<Long> managerIds) {
        var result=new LinkedHashMap<Long,Recipient>();
        var admins=users.getAllOwners("ROLE_ADMIN");
        if(admins!=null) for(var user:admins) if(user!=null && user.getId()!=null && user.isActive())
            result.put(user.getId(),new Recipient(user.getId(),null,valid(user.getTelegramChatId())));
        for(Long id:new LinkedHashSet<>(managerIds)) if(id!=null) managers.findById(id).ifPresent(manager -> {
            var user=manager.getUser();
            if(user!=null && user.getId()!=null && user.isActive()) {
                Long chat=valid(manager.getAuditTelegramGroupChatId());
                if(chat==null) chat=valid(user.getTelegramChatId());
                result.putIfAbsent(user.getId(),new Recipient(user.getId(),id,chat));
            }
        });
        return List.copyOf(result.values());
    }
    @Transactional(readOnly=true)
    public Long currentChat(long userId,Long managerId) {
        var user=userRepository.findById(userId).orElse(null);
        if(user==null || !user.isActive()) return null;
        if(managerId==null) {
            if(user.getRoles()==null || user.getRoles().stream().noneMatch(role -> "ROLE_ADMIN".equals(role.getName()))) return null;
            return valid(user.getTelegramChatId());
        }
        var manager=managers.findById(managerId).orElse(null);
        if(manager==null || manager.getUser()==null || !Objects.equals(manager.getUser().getId(),userId)) return null;
        Long chat=valid(manager.getAuditTelegramGroupChatId());
        return chat==null?valid(user.getTelegramChatId()):chat;
    }
    private Long valid(Long chat) {return chat==null || chat==0?null:chat;}
}
