package com.hunt.otziv.mobile_push.service;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import com.hunt.otziv.mobile_push.dto.MobilePushSendResponse;
import com.hunt.otziv.mobile_push.model.MobilePushToken;
import com.hunt.otziv.mobile_push.repository.MobilePushTokenRepository;
import com.hunt.otziv.u_users.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MobilePushSenderService {

    private final Optional<FirebaseMessaging> firebaseMessaging;
    private final MobilePushTokenRepository tokenRepository;

    @Transactional
    public MobilePushSendResponse sendToUser(User user, String title, String body, String route) {
        List<MobilePushToken> tokens = tokenRepository.findByUserIdAndActiveTrue(user.getId());
        if (firebaseMessaging.isEmpty()) {
            return new MobilePushSendResponse(false, tokens.size(), 0, 0);
        }

        int sent = 0;
        int failed = 0;
        FirebaseMessaging messaging = firebaseMessaging.get();

        for (MobilePushToken token : tokens) {
            try {
                messaging.send(buildMessage(token.getToken(), title, body, route));
                sent++;
            } catch (FirebaseMessagingException exception) {
                failed++;
                if (isDeadToken(exception)) {
                    deactivate(token);
                }
            }
        }

        return new MobilePushSendResponse(true, tokens.size(), sent, failed);
    }

    private Message buildMessage(String token, String title, String body, String route) {
        Message.Builder builder = Message.builder()
                .setToken(token)
                .setNotification(Notification.builder()
                        .setTitle(StringUtils.hasText(title) ? title.trim() : "Компания О!")
                        .setBody(StringUtils.hasText(body) ? body.trim() : "Проверка push-уведомлений")
                        .build());

        if (StringUtils.hasText(route)) {
            builder.putData("route", route.trim());
        }

        return builder.build();
    }

    private void deactivate(MobilePushToken token) {
        token.setActive(false);
        tokenRepository.save(token);
    }

    private boolean isDeadToken(FirebaseMessagingException exception) {
        MessagingErrorCode code = exception.getMessagingErrorCode();
        return code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT;
    }
}
