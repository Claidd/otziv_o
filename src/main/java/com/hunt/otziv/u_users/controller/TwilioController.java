//package com.hunt.otziv.u_users.controller;
//
//
//import com.hunt.otziv.config.twilio.ExampleTwilio;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.web.bind.annotation.GetMapping;
//
//import org.springframework.web.bind.annotation.RestController;
//
//@RestController
//public class TwilioController {
//
//    @Autowired
//    private ExampleTwilio twilioService;
//
//    @GetMapping("/send-message")
//    public String sendMessage() {
//        twilioService.sendWhatsAppMessage();
//        return "Message sent successfully!";
//    }
//}
