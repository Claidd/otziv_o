package com.hunt.otziv.whatsapp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;


import java.util.List;

@Data
@ConfigurationProperties(prefix = "whatsapp")
public class WhatsAppProperties {

    private List<ClientConfig> clients;

    @Data
    public static class ClientConfig {
        private String id;
        private String url;
    }

    public int getClientCount() {
        return clients != null ? clients.size() : 0;
    }

}
