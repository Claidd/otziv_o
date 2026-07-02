package com.hunt.otziv.client_chat_control.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_chat_control.model.ClientChatDirection;
import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import com.hunt.otziv.client_chat_control.model.ClientChatSenderRole;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClientChatParticipantClassifierTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private AppSettingService appSettingService;

    private ClientChatParticipantClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new ClientChatParticipantClassifier(userRepository, appSettingService);
        lenient().when(userRepository.findAllActiveUsersWithPhoneNumbers()).thenReturn(List.of());
        lenient().when(appSettingService.getString(
                AppSettingService.MANAGER_CONTROL_UNANSWERED_STAFF_NAME_ALIASES,
                ""
        )).thenReturn("");
    }

    @Test
    void whatsappSenderWithKnownUserPhoneIsStaff() {
        User managerUser = user("Вика Ц.", "vika", "+7 999 111-22-33");
        when(userRepository.findAllActiveUsersWithPhoneNumbers()).thenReturn(List.of(managerUser));

        ClientChatSenderRole role = classifier.classify(
                ClientChatPlatform.WHATSAPP,
                ClientChatDirection.INCOMING,
                "79991112233@c.us",
                "Мария",
                companyWithManager(managerUser)
        );

        assertEquals(ClientChatSenderRole.STAFF, role);
    }

    @Test
    void whatsappSenderWithCompanyStaffNameIsStaff() {
        User managerUser = user("Мария Иванова", "maria", "");

        ClientChatSenderRole role = classifier.classify(
                ClientChatPlatform.WHATSAPP,
                ClientChatDirection.INCOMING,
                "unknown@c.us",
                "Мария",
                companyWithManager(managerUser)
        );

        assertEquals(ClientChatSenderRole.STAFF, role);
    }

    @Test
    void whatsappDifferentSenderNameWithoutAliasStaysClient() {
        User managerUser = user("Вика Ц.", "vika", "");

        ClientChatSenderRole role = classifier.classify(
                ClientChatPlatform.WHATSAPP,
                ClientChatDirection.INCOMING,
                "unknown@c.us",
                "Мария",
                companyWithManager(managerUser)
        );

        assertEquals(ClientChatSenderRole.CLIENT, role);
    }

    @Test
    void whatsappConfiguredStaffAliasIsStaffOnlyForCompanyStaff() {
        User managerUser = user("Вика Ц.", "vika", "");
        when(appSettingService.getString(
                AppSettingService.MANAGER_CONTROL_UNANSWERED_STAFF_NAME_ALIASES,
                ""
        )).thenReturn("Вика Ц.=Мария, Маша");

        ClientChatSenderRole role = classifier.classify(
                ClientChatPlatform.WHATSAPP,
                ClientChatDirection.INCOMING,
                "unknown@c.us",
                "Мария",
                companyWithManager(managerUser)
        );

        assertEquals(ClientChatSenderRole.STAFF, role);
    }

    private Company companyWithManager(User managerUser) {
        Manager manager = new Manager();
        manager.setUser(managerUser);

        Company company = new Company();
        company.setManager(manager);
        return company;
    }

    private User user(String fio, String username, String phone) {
        User user = new User();
        user.setActive(true);
        user.setFio(fio);
        user.setUsername(username);
        user.setPhoneNumber(phone);
        return user;
    }
}
