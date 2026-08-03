package com.hunt.otziv.l_lead.services;


import com.hunt.otziv.l_lead.dto.TextPhoneDTO;
import com.hunt.otziv.l_lead.model.Telephone;
import com.hunt.otziv.l_lead.repository.DeviceTokenRepository;
import com.hunt.otziv.l_lead.repository.TelephoneRepository;
import com.hunt.otziv.l_lead.model.DeviceToken;
import com.hunt.otziv.l_lead.dto.TelephoneIDAndTimeDTO;
import com.hunt.otziv.l_lead.services.serv.DeviceTokenService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceTokenServiceImpl implements DeviceTokenService {

    private static final int RANDOM_TOKEN_BYTES = 32;
    private static final int MAX_ACCEPTED_TOKEN_LENGTH = 512;
    private static final int DEFAULT_TTL_DAYS = 30;
    private static final int MAX_TTL_DAYS = 365;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Pattern LEGACY_UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
    );

    private final DeviceTokenRepository deviceTokenRepository;
    private final TelephoneRepository telephoneRepository;

    @Value("${lead.device-token.cookie-secure:false}")
    private boolean secureCookie;

    @Value("${lead.device-token.ttl-days:30}")
    private int tokenTtlDays = DEFAULT_TTL_DAYS;

    @Transactional
    public String createDeviceToken(Long telephoneId, HttpServletResponse response) {
        Telephone tel = telephoneRepository.findByIdWithOperator(telephoneId)
                .orElseThrow(() -> new EntityNotFoundException("Телефон не найден"));

        if (tel.getTelephoneOperator() == null) {
            throw new IllegalStateException("Телефон не назначен оператору");
        }

        LocalDateTime now = LocalDateTime.now();
        deviceTokenRepository.deleteExpiredOrInactiveByTelephoneId(telephoneId, now);
        if (deviceTokenRepository.existsByTelephone_Id(telephoneId)) {
            throw new IllegalStateException("Токен уже есть в системе");
        }

        String bearerToken = newBearerToken();
        int ttlDays = effectiveTtlDays();

        DeviceToken deviceToken = DeviceToken.builder()
                .token(hashToken(bearerToken))
                .telephone(tel)
                .createdAt(now)
                .expiresAt(now.plusDays(ttlDays))
                .active(true)
                .build();

        try {
            deviceTokenRepository.save(deviceToken);
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalStateException("Токен уже есть в системе", ex);
        }

        Cookie cookie = new Cookie("device_token", bearerToken);
        cookie.setHttpOnly(true);
        cookie.setSecure(secureCookie);
        cookie.setAttribute("SameSite", "Strict");
        cookie.setPath("/");
        cookie.setMaxAge(Math.toIntExact(Duration.ofDays(ttlDays).toSeconds()));
        response.addCookie(cookie);

        return bearerToken;
    }

    @Transactional
    public TelephoneIDAndTimeDTO getTelephoneIdByToken(String token) {
        DeviceToken deviceToken = resolveDeviceToken(token);
        return deviceToken == null ? null : toDto(deviceToken.getTelephone());
    }

    @Override
    @Transactional
    public TextPhoneDTO getText(String token) {
        DeviceToken deviceToken = resolveDeviceToken(token);
        Telephone telephone = deviceToken == null ? null : deviceToken.getTelephone();
        if (telephone != null) {
            return TextPhoneDTO.builder()
                    .beginText(telephone.getBeginText())
                    .offerText(telephone.getOfferText())
                    .offer2Text(telephone.getOffer2Text())
                    .startText(telephone.getStartText())
                    .build();
        } else {
            return TextPhoneDTO.builder()
                    .beginText("Пишем хорошие отзывы. Можем прислать Вам подробную информацию сюда?)")
                    .offerText("""
                        Отлично) Вот наше предложение)

                        - Стоимость 200 рублей за 1 отзыв. 300р. с фото (от 5 на 1 филиал).
                        - Все тексты согласовываются с вами перед публикацией.
                        - Оплата по факту, после выполнения всего заказа, чтобы вы убедились, что все прошло модерацию и действительно опубликовано.
                        - Отзывы будут публиковаться в течении месяца, а не все разом.
                        - Все отзывы у аккаунтов будут из города вашей компании, а не со всей России. У нас аккаунты закреплены именно за вашим городом и если кто-то зайдет на профиль написавшего Вам, то не увидит, что человек телепортируется по всей России и пишет в разные фирмы, где не могли быть физически. Это подмечается недовольными клиентами, если кто-то вдруг захочет написать Вам плохой отзыв. Люди будут видеть это, заходить, проверять, лайкать, поднимать такой отзыв в самый верх и каждый зашедший будет понимать, что вы накручиваете отзывы. С нами такого не будет.

                        Для того, чтобы начать Вам нужно:
                        1.  Прислать сюда ссылку на вашу карточку в 2ГИС.)
                        2.  Написать необходимое кол-во отзывов в месяц (5,10,15,30 или более)
                        """)
                    .offer2Text("Здравствуйте, скажите Вы рассмотрели наше предложение?)")
                    .startText("Отлично, Мы создадим группу в ватсапп, подготовим тексты и пришлем вам на согласование)")
                    .build();
        }
    }

    private DeviceToken resolveDeviceToken(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank() || bearerToken.length() > MAX_ACCEPTED_TOKEN_LENGTH) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now();
        String hashedToken = hashToken(bearerToken);
        Optional<DeviceToken> current = deviceTokenRepository
                .findActiveUnexpiredByStoredToken(hashedToken, now);
        if (current.isPresent()) {
            return current.get();
        }

        // Pre-migration tokens were canonical, lower-case UUID values. Restricting the
        // compatibility path to that exact shape prevents the stored digest itself from
        // becoming a usable bearer token through the admin API.
        if (!LEGACY_UUID.matcher(bearerToken).matches()) {
            return null;
        }

        Optional<DeviceToken> legacy = deviceTokenRepository.findActiveLegacyByStoredToken(bearerToken);
        if (legacy.isEmpty()) {
            return null;
        }

        LocalDateTime legacyExpiry = legacy.get().getExpiresAt();
        if (legacyExpiry != null && !legacyExpiry.isAfter(now)) {
            return null;
        }

        LocalDateTime effectiveExpiry = legacyExpiry == null
                ? now.plusDays(effectiveTtlDays())
                : legacyExpiry;
        deviceTokenRepository.rotateLegacyToken(
                bearerToken,
                hashedToken,
                effectiveExpiry,
                now
        );

        // A concurrent request may have completed the same rotation first; in either
        // case reload only by the digest and fail closed if no valid row remains.
        return deviceTokenRepository.findActiveUnexpiredByStoredToken(hashedToken, now)
                .orElse(null);
    }

    private int effectiveTtlDays() {
        return Math.max(1, Math.min(tokenTtlDays, MAX_TTL_DAYS));
    }

    private static String newBearerToken() {
        byte[] bytes = new byte[RANDOM_TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hashToken(String bearerToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(bearerToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private TelephoneIDAndTimeDTO toDto(Telephone telephone) {
        return TelephoneIDAndTimeDTO.builder()
                .telephoneID(telephone.getId())
                .time(telephone.getTimer())
                .operatorID(telephone.getTelephoneOperator() == null ? null : telephone.getTelephoneOperator().getId())
                .build();
    }

}
