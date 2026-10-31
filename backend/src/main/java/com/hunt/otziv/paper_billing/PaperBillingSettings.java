package com.hunt.otziv.paper_billing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Getter @Setter
@ConfigurationProperties(prefix="paper-invoices")
public class PaperBillingSettings {
    private boolean enabled;
    private boolean bankOperationsEnabled;
    private boolean deliveryEnabled;
    private String accountLast4 = "";
    private String dadataKey = "";
    private String fixtureUrl = "";
}
