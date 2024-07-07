package com.hunt.otziv.u_users.config;

import com.twilio.Twilio;
import com.twilio.converter.Promoter;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.math.BigDecimal;
@Service
public class ExampleTwilio  {
    // Find your Account Sid and Token at twilio.com/console
    public static final String ACCOUNT_SID = "AC9d36f1b347025306fd1cb1e84c222cd0";
    public static final String AUTH_TOKEN = "28bad7b8f7bc5502aba1a0fb91f44256";

    public void sendWhatsAppMessage() {
        Twilio.init(ACCOUNT_SID, AUTH_TOKEN);
        Message message = Message.creator(
                new com.twilio.type.PhoneNumber("whatsapp:+79041256288"),
                new com.twilio.type.PhoneNumber("whatsapp:+14155238886"),
                "Привет) Как дела? Джо!)").create();

        System.out.println(message.getSid());
    }
}