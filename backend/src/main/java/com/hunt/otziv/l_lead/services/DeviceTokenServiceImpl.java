package com.hunt.otziv.l_lead.services;


import com.hunt.otziv.l_lead.dto.TextPhoneDTO;
import com.hunt.otziv.l_lead.model.Telephone;
import com.hunt.otziv.l_lead.repository.DeviceTokenRepository;
import com.hunt.otziv.l_lead.repository.TelephoneRepository;
import com.hunt.otziv.l_lead.model.DeviceToken;
import com.hunt.otziv.l_lead.dto.TelephoneIDAndTimeDTO;
import com.hunt.otziv.l_lead.services.serv.DeviceTokenService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceTokenServiceImpl implements DeviceTokenService {

    private final DeviceTokenRepository deviceTokenRepository;
    private final TelephoneRepository telephoneRepository;


    public String createDeviceToken(Long telephoneId, HttpServletResponse response) {
        Telephone tel = telephoneRepository.findById(telephoneId)
                .orElseThrow(() -> new RuntimeException("Телефон не найден"));

        String token = UUID.randomUUID().toString();

        DeviceToken deviceToken = DeviceToken.builder()
                .token(token)
                .telephone(tel)
                .createdAt(LocalDateTime.now())
                .active(true)
                .build();

        deviceTokenRepository.save(deviceToken);

        Cookie cookie = new Cookie("device_token", token);
        cookie.setHttpOnly(true); // ← временно, для JS-доступа
        cookie.setPath("/");
        cookie.setMaxAge(365 * 24 * 60 * 60); // 1 год
        response.addCookie(cookie);

        return token;
    }

    public TelephoneIDAndTimeDTO getTelephoneIdByToken(String token) {
        return deviceTokenRepository.findByToken(token)
                .map(deviceToken -> toDto(deviceToken.getTelephone()))
                .orElse(null);
    }

    @Override
    public TextPhoneDTO getText(String token) {
        Telephone telephone = deviceTokenRepository.findTelephoneByToken(token).orElse(null);
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


    private TelephoneIDAndTimeDTO toDto(Telephone telephone) {
        return TelephoneIDAndTimeDTO.builder()
                .telephoneID(telephone.getId())
                .time(telephone.getTimer())
                .operatorID(telephone.getTelephoneOperator().getId())
                .build();
    }

}
