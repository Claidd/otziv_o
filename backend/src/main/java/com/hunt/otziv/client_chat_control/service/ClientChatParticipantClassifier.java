package com.hunt.otziv.client_chat_control.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_chat_control.model.ClientChatDirection;
import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import com.hunt.otziv.client_chat_control.model.ClientChatSenderRole;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ClientChatParticipantClassifier {

    private final UserRepository userRepository;
    private final AppSettingService appSettingService;

    public ClientChatSenderRole classify(
            ClientChatPlatform platform,
            ClientChatDirection direction,
            String senderExternalId
    ) {
        return classify(platform, direction, senderExternalId, null, null);
    }

    public ClientChatSenderRole classify(
            ClientChatPlatform platform,
            ClientChatDirection direction,
            String senderExternalId,
            String senderName,
            Company company
    ) {
        if (platform == null) {
            return ClientChatSenderRole.CLIENT;
        }
        return switch (platform) {
            case TELEGRAM -> isKnownTelegramUser(senderExternalId) || isKnownCompanyStaffName(senderName, company)
                    ? ClientChatSenderRole.STAFF
                    : ClientChatSenderRole.CLIENT;
            case WHATSAPP -> isKnownPhone(senderExternalId) || isKnownCompanyStaffName(senderName, company)
                    ? ClientChatSenderRole.STAFF
                    : ClientChatSenderRole.CLIENT;
            case MAX -> isKnownCompanyStaffName(senderName, company)
                    ? ClientChatSenderRole.STAFF
                    : ClientChatSenderRole.CLIENT;
        };
    }

    private boolean isKnownTelegramUser(String senderExternalId) {
        if (senderExternalId == null || senderExternalId.isBlank()) {
            return false;
        }
        try {
            return userRepository.findByTelegramChatId(Long.parseLong(senderExternalId.trim())).isPresent();
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private boolean isKnownPhone(String senderExternalId) {
        String senderPhone = digits(senderExternalId);
        if (senderPhone.length() < 7) {
            return false;
        }
        List<User> users = userRepository.findAllActiveUsersWithPhoneNumbers();
        return users.stream()
                .map(User::getPhoneNumber)
                .map(ClientChatParticipantClassifier::digits)
                .filter(phone -> phone.length() >= 7)
                .anyMatch(phone -> phone.endsWith(senderPhone) || senderPhone.endsWith(phone));
    }

    private boolean isKnownCompanyStaffName(String senderName, Company company) {
        String sender = normalizedName(senderName);
        if (sender.isBlank() || company == null) {
            return false;
        }

        List<User> staff = companyStaff(company);
        for (User user : staff) {
            if (matchesFullName(sender, normalizedName(user == null ? null : user.getFio()))
                    || matchesFullName(sender, normalizedName(user == null ? null : user.getUsername()))) {
                return true;
            }
        }
        if (matchesConfiguredStaffAlias(sender, staff)) {
            return true;
        }

        String senderFirstName = firstToken(sender);
        if (senderFirstName.length() < 3) {
            return false;
        }
        long sameFirstNameStaff = staff.stream()
                .map(ClientChatParticipantClassifier::firstStaffNameToken)
                .filter(senderFirstName::equals)
                .count();
        return sameFirstNameStaff == 1;
    }

    private boolean matchesConfiguredStaffAlias(String sender, List<User> staff) {
        String rawAliases = appSettingService.getString(
                AppSettingService.MANAGER_CONTROL_UNANSWERED_STAFF_NAME_ALIASES,
                ""
        );
        if (rawAliases == null || rawAliases.isBlank()) {
            return false;
        }
        for (String rule : rawAliases.split("[;\\r\\n]+")) {
            String[] parts = rule.split("=", 2);
            if (parts.length != 2) {
                continue;
            }
            String staffName = normalizedName(parts[0]);
            if (staffName.isBlank() || !matchesAnyStaff(staffName, staff)) {
                continue;
            }
            for (String alias : parts[1].split("[,|]+")) {
                String normalizedAlias = normalizedName(alias);
                if (matchesFullName(sender, normalizedAlias)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesAnyStaff(String staffName, List<User> staff) {
        for (User user : staff) {
            if (matchesFullName(staffName, normalizedName(user == null ? null : user.getFio()))
                    || matchesFullName(staffName, normalizedName(user == null ? null : user.getUsername()))) {
                return true;
            }
        }
        return false;
    }

    private List<User> companyStaff(Company company) {
        List<User> users = new ArrayList<>();
        if (company.getUser() != null) {
            users.add(company.getUser());
        }
        Manager manager = company.getManager();
        if (manager != null && manager.getUser() != null) {
            users.add(manager.getUser());
        }
        if (company.getWorkers() != null) {
            company.getWorkers().stream()
                    .map(Worker::getUser)
                    .filter(Objects::nonNull)
                    .forEach(users::add);
        }
        return users.stream().distinct().toList();
    }

    private static boolean matchesFullName(String sender, String staffName) {
        if (sender.isBlank() || staffName.isBlank()) {
            return false;
        }
        return sender.equals(staffName)
                || sender.startsWith(staffName + " ")
                || staffName.startsWith(sender + " ");
    }

    private static String firstStaffNameToken(User user) {
        if (user == null) {
            return "";
        }
        String fioToken = firstToken(normalizedName(user.getFio()));
        return fioToken.isBlank() ? firstToken(normalizedName(user.getUsername())) : fioToken;
    }

    private static String firstToken(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int space = value.indexOf(' ');
        return space < 0 ? value : value.substring(0, space);
    }

    private static String normalizedName(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .replaceFirst("^@", "")
                .replace('ё', 'е')
                .replace('Ё', 'Е')
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static String digits(String value) {
        return value == null ? "" : value.replaceAll("\\D+", "");
    }
}
