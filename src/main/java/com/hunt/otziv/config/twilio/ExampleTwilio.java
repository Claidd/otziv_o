package com.hunt.otziv.config.twilio;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ExampleTwilio  {
    // Find your Account Sid and Token at twilio.com/console
    @Value("${spring.twilio.account-sid}")
    private String ACCOUNT_SID;
    @Value("spring.twilio.auth_token")
    private String AUTH_TOKEN;

    public void sendWhatsAppMessage() {
        Twilio.init(ACCOUNT_SID, AUTH_TOKEN);
        Message message = Message.creator(
                new com.twilio.type.PhoneNumber("whatsapp:+79041256288"),
                new com.twilio.type.PhoneNumber("whatsapp:+14155238886"),
                "Привет) Как дела? Джо!)").create();

        System.out.println(message.getSid());
    }
}