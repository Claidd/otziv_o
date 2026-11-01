package com.hunt.otziv.paper_billing;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.ResourcePropertySource;

class PaperBillingSettingsTest {
    @Test void productionPropertiesBindThePrivateSupplierSignature() throws Exception {
        var environment=new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("release-settings",Map.of(
                "PAPER_INVOICES_SIGNATURE_PATH","/app/paper-invoices/signature.png",
                "PAPER_INVOICES_SIGNATURE_SUPPLIER_INN","7707083893")));
        environment.getPropertySources().addLast(new ResourcePropertySource("classpath:application.properties"));
        var settings=Binder.get(environment).bind("paper-invoices",Bindable.of(PaperBillingSettings.class)).get();
        assertEquals("/app/paper-invoices/signature.png",settings.getSignaturePath());
        assertEquals("7707083893",settings.getSignatureSupplierInn());
    }
}
