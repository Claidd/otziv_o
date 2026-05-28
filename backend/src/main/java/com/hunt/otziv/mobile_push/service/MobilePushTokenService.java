package com.hunt.otziv.mobile_push.service;

import com.hunt.otziv.mobile_push.dto.MobilePushTokenRequest;
import com.hunt.otziv.mobile_push.dto.MobilePushSendResponse;
import com.hunt.otziv.mobile_push.dto.MobilePushTestRequest;
import com.hunt.otziv.mobile_push.model.MobilePushToken;
import com.hunt.otziv.mobile_push.repository.MobilePushTokenRepository;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class MobilePushTokenService {

    private final MobilePushTokenRepository tokenRepository;
    private final UserService userService;
    private final MobilePushSenderService senderService;

    @Transactional
    public void register(Principal principal, MobilePushTokenRequest request) {
        User user = currentUser(principal);
        Instant now = Instant.now();
        MobilePushToken token = tokenRepository.findByToken(request.token())
                .orElseGet(MobilePushToken::new);

        token.setUser(user);
        token.setToken(request.token());
        token.setPlatform(trimToNull(request.platform()));
        token.setDeviceId(trimToNull(request.deviceId()));
        token.setAppVersion(trimToNull(request.appVersion()));
        token.setActive(true);
        token.setLastSeenAt(now);

        tokenRepository.save(token);
    }

    @Transactional
    public MobilePushSendResponse sendTest(Principal principal, MobilePushTestRequest request) {
        User user = currentUser(principal);
        String title = request == null ? null : request.title();
        String body = request == null ? null : request.body();
        String route = request == null ? "/tabs/home" : request.route();
        return senderService.sendToUser(user, title, body, route);
    }

    private User currentUser(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Пользователь не авторизован");
        }

        return userService.findByUserName(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Пользователь не найден"));
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
