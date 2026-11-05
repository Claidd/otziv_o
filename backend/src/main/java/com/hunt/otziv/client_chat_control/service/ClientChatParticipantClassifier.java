package com.hunt.otziv.client_chat_control.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_chat_control.model.ClientChatDirection;
import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import com.hunt.otziv.client_chat_control.model.ClientChatSenderRole;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ClientChatParticipantClassifier {

    private static final String CLIENT_ROLE = "ROLE_CLIENT";

    private final UserRepository userRepository;
    private final AppSettingService appSettingService;
    private final ClientChatIdentityService identityService;

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
        return classify(platform, direction, null, senderExternalId, senderName, company);
    }

    public ClientChatSenderRole classify(
            ClientChatPlatform platform,
            ClientChatDirection direction,
            String chatId,
            String senderExternalId,
            String senderName,
            Company company
    ) {
        if (platform == null) {
            return ClientChatSenderRole.CLIENT;
        }
        var knownRole = identityService.knownRole(platform, chatId, senderExternalId, senderName);
        if (knownRole.isPresent()) {
            return knownRole.get();
        }
        if (platform == ClientChatPlatform.TELEGRAM && isAnonymousTelegramAdmin(senderExternalId, senderName)) {
            return ClientChatSenderRole.STAFF;
        }
        if (isKnownGlobalControlStaffName(senderName)) {
            return ClientChatSenderRole.STAFF;
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

    public Optional<User> resolveStaffUser(
            ClientChatPlatform platform,
            String chatId,
            String senderExternalId,
            String senderName,
            Company company
    ) {
        if (platform == null) {
            return Optional.empty();
        }
        Optional<User> linkedUser = identityService
                .knownUser(platform, chatId, senderExternalId, senderName)
                .filter(User::isActive);
        if (linkedUser.isPresent()) {
            return linkedUser;
        }
        if (platform == ClientChatPlatform.TELEGRAM) {
            Optional<User> telegramUser = telegramUser(senderExternalId);
            if (telegramUser.isPresent()) {
                return telegramUser;
            }
        }
        if (platform == ClientChatPlatform.WHATSAPP) {
            Optional<User> phoneUser = phoneUser(senderExternalId);
            if (phoneUser.isPresent()) {
                return phoneUser;
            }
        }
        return namedStaffUser(senderName, company);
    }

    private boolean isAnonymousTelegramAdmin(String senderExternalId, String senderName) {
        String external = senderExternalId == null ? "" : senderExternalId.trim();
        String normalized = normalizedName(senderName);
        return "1087968824".equals(external) || normalized.contains("groupanonymousbot");
    }

    private boolean isKnownTelegramUser(String senderExternalId) {
        return telegramUser(senderExternalId).isPresent();
    }

    private Optional<User> telegramUser(String senderExternalId) {
        if (senderExternalId == null || senderExternalId.isBlank()) {
            return Optional.empty();
        }
        try {
            return userRepository.findByTelegramChatId(Long.parseLong(senderExternalId.trim()))
                    .filter(User::isActive);
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private boolean isKnownPhone(String senderExternalId) {
        return phoneUser(senderExternalId).isPresent();
    }

    private Optional<User> phoneUser(String senderExternalId) {
        String senderPhone = normalizedPhone(senderExternalId);
        if (senderPhone.length() < 7) {
            return Optional.empty();
        }
        List<User> managementMatches = matchingPhoneUsers(
                userRepository.findAllActiveManagerControlStaff(),
                senderPhone
        );
        Optional<User> managementUser = uniqueUser(managementMatches);
        if (managementUser.isPresent() || managementMatches.size() > 1) {
            return managementUser;
        }
        return uniqueUser(matchingPhoneUsers(
                userRepository.findAllActiveUsersWithPhoneNumbers(),
                senderPhone
        ).stream().filter(user -> !isClientAccount(user)).toList());
    }

    /** A client's cabinet account carries the client's own phone; it never makes the client staff. */
    private static boolean isClientAccount(User user) {
        Collection<? extends GrantedAuthority> roles = user == null ? null : user.getRoles();
        if (roles == null || roles.isEmpty()) {
            return false;
        }
        boolean client = false;
        for (GrantedAuthority role : roles) {
            String name = role == null || role.getAuthority() == null ? "" : role.getAuthority().trim();
            if (CLIENT_ROLE.equalsIgnoreCase(name)) {
                client = true;
            } else if (!name.isEmpty()) {
                return false;
            }
        }
        return client;
    }

    private List<User> matchingPhoneUsers(List<User> users, String senderPhone) {
        return (users == null ? List.<User>of() : users).stream()
                .filter(Objects::nonNull)
                .filter(user -> {
                    String phone = normalizedPhone(user.getPhoneNumber());
                    return phone.length() >= 7
                            && (phone.endsWith(senderPhone) || senderPhone.endsWith(phone));
                })
                .toList();
    }

    /**
     * Owners, administrators and managers are recognised everywhere only by a full name or a configured
     * alias: a bare first name or a one-word login (for example a client "Иван" against the owner
     * "Иван Хант", or "Alex ..." against the login "alex") is shared by too many clients.
     */
    private boolean isKnownGlobalControlStaffName(String senderName) {
        String sender = normalizedName(senderName);
        if (sender.isBlank()) {
            return false;
        }
        List<User> staff = userRepository.findAllActiveManagerControlStaff();
        for (User user : staff) {
            if (matchesStaffFullName(sender, user)) {
                return true;
            }
        }
        return matchesConfiguredStaffAlias(sender, staff);
    }

    /**
     * Company staff are recognised by a full name or a configured alias. A bare first name identifies only
     * the company's own manager, who is expected in this chat; a specialist needs a linked id (identity,
     * Telegram id, phone) or a full name, otherwise a client who shares the specialist's first name would
     * be counted as staff and her questions would never open a card.
     */
    private boolean isKnownCompanyStaffName(String senderName, Company company) {
        String sender = normalizedName(senderName);
        if (sender.isBlank() || company == null) {
            return false;
        }

        List<User> staff = companyStaff(company);
        for (User user : staff) {
            if (matchesStaffFullName(sender, user)) {
                return true;
            }
        }
        if (matchesConfiguredStaffAlias(sender, staff)) {
            return true;
        }
        return responsibleFirstNameUser(sender, company).isPresent();
    }

    private Optional<User> responsibleFirstNameUser(String sender, Company company) {
        String senderFirstName = firstToken(sender);
        if (senderFirstName.length() < 3) {
            return Optional.empty();
        }
        return uniqueUser(responsibleStaff(company).stream()
                .filter(User::isActive)
                .filter(user -> senderFirstName.equals(firstStaffNameToken(user))
                        || senderFirstName.equals(normalizedName(user.getUsername())))
                .toList());
    }

    private static boolean matchesStaffFullName(String sender, User user) {
        return user != null
                && (isFullNameOf(sender, normalizedName(user.getFio()))
                || isFullNameOf(sender, normalizedName(user.getUsername())));
    }

    /** Both sides carry at least two words (a first name plus a surname or an initial). */
    private static boolean isFullNameOf(String sender, String staffName) {
        if (!sender.contains(" ") || !staffName.contains(" ")) {
            return false;
        }
        return matchesFullName(sender, staffName);
    }

    private Optional<User> namedStaffUser(String senderName, Company company) {
        String sender = normalizedName(senderName);
        if (sender.isBlank()) {
            return Optional.empty();
        }
        List<User> candidates = new ArrayList<>(userRepository.findAllActiveManagerControlStaff());
        candidates.addAll(companyStaff(company));
        candidates = candidates.stream()
                .filter(Objects::nonNull)
                .filter(User::isActive)
                .distinct()
                .toList();

        Optional<User> direct = uniqueUser(candidates.stream()
                .filter(user -> matchesFullName(sender, normalizedName(user.getFio()))
                        || matchesFullName(sender, normalizedName(user.getUsername())))
                .toList());
        if (direct.isPresent()) {
            return direct;
        }

        Optional<User> alias = configuredAliasUser(sender, candidates);
        if (alias.isPresent()) {
            return alias;
        }

        String senderFirstName = firstToken(sender);
        if (senderFirstName.length() < 3) {
            return Optional.empty();
        }
        return uniqueUser(companyStaff(company).stream()
                .filter(Objects::nonNull)
                .filter(User::isActive)
                .filter(user -> senderFirstName.equals(firstStaffNameToken(user)))
                .toList());
    }

    private Optional<User> configuredAliasUser(String sender, List<User> staff) {
        String rawAliases = appSettingService.getString(
                AppSettingService.MANAGER_CONTROL_UNANSWERED_STAFF_NAME_ALIASES,
                ""
        );
        if (rawAliases == null || rawAliases.isBlank()) {
            return Optional.empty();
        }
        List<User> matches = new ArrayList<>();
        for (String rule : rawAliases.split("[;\\r\\n]+")) {
            String[] parts = rule.split("=", 2);
            if (parts.length != 2) {
                continue;
            }
            boolean senderMatchesAlias = false;
            for (String alias : parts[1].split("[,|]+")) {
                if (matchesFullName(sender, normalizedName(alias))) {
                    senderMatchesAlias = true;
                    break;
                }
            }
            if (!senderMatchesAlias) {
                continue;
            }
            String staffName = normalizedName(parts[0]);
            staff.stream()
                    .filter(user -> matchesFullName(staffName, normalizedName(user.getFio()))
                            || matchesFullName(staffName, normalizedName(user.getUsername())))
                    .forEach(matches::add);
        }
        return uniqueUser(matches);
    }

    private Optional<User> uniqueUser(List<User> users) {
        List<User> distinct = users == null
                ? List.of()
                : users.stream().filter(Objects::nonNull).distinct().toList();
        return distinct.size() == 1 ? Optional.of(distinct.getFirst()) : Optional.empty();
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

    /** The company's own manager (the company's user and its manager account). */
    private List<User> responsibleStaff(Company company) {
        if (company == null) {
            return List.of();
        }
        List<User> users = new ArrayList<>();
        if (company.getUser() != null) {
            users.add(company.getUser());
        }
        Manager manager = company.getManager();
        if (manager != null && manager.getUser() != null) {
            users.add(manager.getUser());
        }
        return users.stream().distinct().toList();
    }

    private List<User> companyStaff(Company company) {
        if (company == null) {
            return List.of();
        }
        List<User> users = new ArrayList<>(responsibleStaff(company));
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

    private static String normalizedPhone(String value) {
        String phone = digits(value);
        if (phone.length() == 11 && phone.startsWith("8")) {
            return "7" + phone.substring(1);
        }
        return phone;
    }
}
