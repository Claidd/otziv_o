package com.hunt.otziv.client_chat_control.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_chat_control.model.ClientChatDirection;
import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import com.hunt.otziv.client_chat_control.model.ClientChatSenderRole;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClientChatParticipantClassifierTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private AppSettingService appSettingService;
    @Mock
    private ClientChatIdentityService identityService;

    private ClientChatParticipantClassifier classifier;
    private long nextUserId = 1;

    @BeforeEach
    void setUp() {
        classifier = new ClientChatParticipantClassifier(userRepository, appSettingService, identityService);
        lenient().when(identityService.knownRole(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class)
        )).thenReturn(Optional.empty());
        lenient().when(identityService.knownUser(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class)
        )).thenReturn(Optional.empty());
        lenient().when(userRepository.findAllActiveUsersWithPhoneNumbers()).thenReturn(List.of());
        lenient().when(userRepository.findAllActiveManagerControlStaff()).thenReturn(List.of());
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
    void whatsappCanonicalCountryCodeMatchesRussianTrunkPrefixStoredForOwner() {
        User owner = user("Владелец", "owner", "8 (999) 111-22-33");
        when(userRepository.findAllActiveManagerControlStaff()).thenReturn(List.of(owner));

        ClientChatSenderRole role = classifier.classify(
                ClientChatPlatform.WHATSAPP,
                ClientChatDirection.INCOMING,
                "79991112233@c.us",
                "Другое имя WhatsApp",
                companyWithManager(user("Менеджер", "manager", ""))
        );

        assertEquals(ClientChatSenderRole.STAFF, role);
    }

    @Test
    void managementPhoneWinsWhenAnotherActiveAccountUsesTheSameNumber() {
        User admin = user("Администратор", "admin", "8 (999) 111-22-33");
        User duplicateAccount = user("Другой аккаунт", "duplicate", "+7 999 111-22-33");
        when(userRepository.findAllActiveManagerControlStaff()).thenReturn(List.of(admin));
        lenient().when(userRepository.findAllActiveUsersWithPhoneNumbers())
                .thenReturn(List.of(admin, duplicateAccount));

        Optional<User> actual = classifier.resolveStaffUser(
                ClientChatPlatform.WHATSAPP,
                "12001@g.us",
                "79991112233@c.us",
                "Другое имя WhatsApp",
                companyWithManager(user("Менеджер", "manager", ""))
        );

        assertSame(admin, actual.orElseThrow());
    }

    @Test
    void resolvesActualWhatsappUserByPhone() {
        User managerUser = user("Вика Ц.", "vika", "+7 999 111-22-33");
        when(userRepository.findAllActiveUsersWithPhoneNumbers()).thenReturn(List.of(managerUser));

        Optional<User> actual = classifier.resolveStaffUser(
                ClientChatPlatform.WHATSAPP,
                "12001@g.us",
                "79991112233@c.us",
                "Вика",
                companyWithManager(managerUser)
        );

        assertSame(managerUser, actual.orElseThrow());
    }

    @Test
    void resolvesLinkedIdentityBeforeHeuristics() {
        User managerUser = user("Мия Ригз", "mia", "");
        when(identityService.knownUser(
                ClientChatPlatform.MAX,
                "-75516171875013",
                "260174587",
                "Мия О"
        )).thenReturn(Optional.of(managerUser));

        Optional<User> actual = classifier.resolveStaffUser(
                ClientChatPlatform.MAX,
                "-75516171875013",
                "260174587",
                "Мия О",
                new Company()
        );

        assertSame(managerUser, actual.orElseThrow());
    }

    @Test
    void telegramAnonymousAdministratorIsStaff() {
        ClientChatSenderRole role = classifier.classify(
                ClientChatPlatform.TELEGRAM,
                ClientChatDirection.INCOMING,
                "1087968824",
                "@GroupAnonymousBot",
                new Company()
        );

        assertEquals(ClientChatSenderRole.STAFF, role);
    }

    @Test
    void verifiedIdentityOverridesHeuristics() {
        when(identityService.knownRole(
                ClientChatPlatform.MAX,
                null,
                "employee-1",
                "Мия О"
        )).thenReturn(Optional.of(ClientChatSenderRole.STAFF));

        ClientChatSenderRole role = classifier.classify(
                ClientChatPlatform.MAX,
                ClientChatDirection.INCOMING,
                "employee-1",
                "Мия О",
                new Company()
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

    @Test
    void maxOwnerAliasIsStaffAcrossAllCompanyChats() {
        User owner = user("Мия Ригз", "mia", "");
        when(userRepository.findAllActiveManagerControlStaff()).thenReturn(List.of(owner));
        when(appSettingService.getString(
                AppSettingService.MANAGER_CONTROL_UNANSWERED_STAFF_NAME_ALIASES,
                ""
        )).thenReturn("Мия Ригз=Мия О|Мия О!");

        ClientChatSenderRole role = classifier.classify(
                ClientChatPlatform.MAX,
                ClientChatDirection.INCOMING,
                "-75516171875013",
                "260174587",
                "Мия О",
                companyWithManager(user("Вика Ц.", "vika", ""))
        );

        assertEquals(ClientChatSenderRole.STAFF, role);
    }

    @Test
    void maxClientSharingTheSpecialistsFirstNameStaysClient() {
        // Розмарин / Кристалл, 05.11: the client «Татьяна» was taken for the specialist «Татьяна У.».
        User manager = user("Анжелика Б.", "lika", "+79834636568");
        User specialist = user("Татьяна У.", "tatu", "+79130184696");
        Company company = companyWithManagerAndWorker(manager, specialist);

        assertEquals(ClientChatSenderRole.CLIENT, classifier.classify(ClientChatPlatform.MAX,
                ClientChatDirection.INCOMING, "-74761808437879", "182858506", "Татьяна", company));
        assertEquals(ClientChatSenderRole.STAFF, classifier.classify(ClientChatPlatform.MAX,
                ClientChatDirection.INCOMING, "-74761808437879", "203090551", "Анжелика", company));
        assertEquals(ClientChatSenderRole.STAFF, classifier.classify(ClientChatPlatform.MAX,
                ClientChatDirection.INCOMING, "-74761808437879", "777", "Татьяна У.", company));
    }

    @Test
    void specialistIsStillRecognisedByLinkedIds() {
        User specialist = user("Татьяна У.", "tatu", "+79130184696");
        specialist.setTelegramChatId(588605220L);
        Company company = companyWithManagerAndWorker(user("Анжелика Б.", "lika", ""), specialist);
        when(userRepository.findByTelegramChatId(588605220L)).thenReturn(Optional.of(specialist));
        when(userRepository.findAllActiveUsersWithPhoneNumbers()).thenReturn(List.of(specialist));
        when(identityService.knownRole(ClientChatPlatform.MAX, "-74761808437879", "113046981", "Татьяна"))
                .thenReturn(Optional.of(ClientChatSenderRole.STAFF));

        assertEquals(ClientChatSenderRole.STAFF, classifier.classify(ClientChatPlatform.TELEGRAM,
                ClientChatDirection.INCOMING, "-5009169601", "588605220", "@Tanya84uskova", company));
        assertEquals(ClientChatSenderRole.STAFF, classifier.classify(ClientChatPlatform.WHATSAPP,
                ClientChatDirection.INCOMING, "1@g.us", "79130184696@c.us", "Татьяна", company));
        assertEquals(ClientChatSenderRole.STAFF, classifier.classify(ClientChatPlatform.MAX,
                ClientChatDirection.INCOMING, "-74761808437879", "113046981", "Татьяна", company));
        assertEquals(ClientChatSenderRole.CLIENT, classifier.classify(ClientChatPlatform.WHATSAPP,
                ClientChatDirection.INCOMING, "1@g.us", "79503371223@c.us", "Татьяна", company));
    }

    @Test
    void ownerFirstNameOrOneWordLoginDoesNotMakeAClientStaff() {
        User owner = user("Иван Хант", "hunt", "89016668881");
        User admin = user("Админ", "alex", "89086431055");
        when(userRepository.findAllActiveManagerControlStaff()).thenReturn(List.of(owner, admin));
        Company company = companyWithManager(user("Вика Ц.", "vika", ""));

        assertEquals(ClientChatSenderRole.CLIENT, classifier.classify(ClientChatPlatform.WHATSAPP,
                ClientChatDirection.INCOMING, "1@g.us", "79043834632@c.us", "Иван", company));
        assertEquals(ClientChatSenderRole.CLIENT, classifier.classify(ClientChatPlatform.TELEGRAM,
                ClientChatDirection.INCOMING, "-100", "1972144268", "Alex Frontmen", company));
        assertEquals(ClientChatSenderRole.STAFF, classifier.classify(ClientChatPlatform.MAX,
                ClientChatDirection.INCOMING, "-7", "1", "Иван Хант", company));
        assertEquals(ClientChatSenderRole.STAFF, classifier.classify(ClientChatPlatform.WHATSAPP,
                ClientChatDirection.INCOMING, "1@g.us", "79086431055@c.us", "Alex", company));
    }

    @Test
    void clientCabinetAccountPhoneDoesNotMakeTheClientStaff() {
        User client = user("Иванов Иван Иванович", "client", "+79138743220");
        client.setRoles(List.of(role("ROLE_CLIENT")));
        User specialist = user("Наталья С.", "nata", "+79526260913");
        specialist.setRoles(List.of(role("ROLE_WORKER")));
        when(userRepository.findAllActiveUsersWithPhoneNumbers()).thenReturn(List.of(client, specialist));
        Company company = companyWithManager(user("Анжелика Б.", "lika", ""));

        assertEquals(ClientChatSenderRole.CLIENT, classifier.classify(ClientChatPlatform.WHATSAPP,
                ClientChatDirection.INCOMING, "1@g.us", "79138743220@c.us", "Олег", company));
        assertEquals(Optional.empty(), classifier.resolveStaffUser(ClientChatPlatform.WHATSAPP,
                "1@g.us", "79138743220@c.us", "Олег", company));
        assertEquals(ClientChatSenderRole.STAFF, classifier.classify(ClientChatPlatform.WHATSAPP,
                ClientChatDirection.INCOMING, "1@g.us", "79526260913@c.us", "Наталья ❤️", company));
    }

    private Company companyWithManagerAndWorker(User managerUser, User workerUser) {
        Company company = companyWithManager(managerUser);
        company.setUser(managerUser);
        company.setWorkers(new java.util.HashSet<>(List.of(Worker.builder().user(workerUser).build())));
        return company;
    }

    private static Role role(String name) {
        Role role = new Role();
        role.setName(name);
        return role;
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
        user.setId(nextUserId++);
        user.setActive(true);
        user.setFio(fio);
        user.setUsername(username);
        user.setPhoneNumber(phone);
        return user;
    }
}
