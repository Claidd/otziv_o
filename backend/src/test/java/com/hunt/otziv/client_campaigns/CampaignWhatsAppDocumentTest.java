package com.hunt.otziv.client_campaigns;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.hunt.otziv.whatsapp.config.WhatsAppProperties;
import com.hunt.otziv.whatsapp.dto.WhatsAppOperationEnvelope;
import com.hunt.otziv.whatsapp.service.WhatsAppServiceImpl;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class CampaignWhatsAppDocumentTest {
    static final String HASH="714ee0267e7041d61de2753bb30f1b91ba0544b63cc20f95fa37c235e6f17b75";
    @Test void unicodeAttachmentEnvelopeMatchesGatewayVector() {
        String payload=WhatsAppOperationEnvelope.documentPayload("Новая услуга 😀","предложение.txt","text/plain","fixture".getBytes(StandardCharsets.UTF_8));
        assertThat(WhatsAppOperationEnvelope.documentHash("manager","123456789@g.us",payload)).isEqualTo(HASH);
    }
    @Test void transportRequiresMatchingEnvelopeOperationAndTextualReceipt() {
        var rest=new RestTemplate(); var server=MockRestServiceServer.bindTo(rest).build();
        var config=new WhatsAppProperties.ClientConfig(); config.setId("manager"); config.setUrl("https://wa.fixture");
        var properties=new WhatsAppProperties(); properties.setClients(List.of(config));
        var service=new WhatsAppServiceImpl(properties,rest,null,null);
        for (String receipt : List.of(
                "{\"operationId\":\"offer:1\",\"state\":\"SUCCEEDED\",\"messageId\":true,\"envelopeHash\":\""+HASH+"\"}",
                "{\"operationId\":\"other\",\"state\":\"SUCCEEDED\",\"messageId\":\"mid\",\"envelopeHash\":\""+HASH+"\"}",
                "{\"operationId\":\"offer:1\",\"state\":\"SUCCEEDED\",\"messageId\":\"mid\",\"envelopeHash\":\"bad\"}")) {
            server.expect(requestTo("https://wa.fixture/send-group-file")).andRespond(withSuccess(receipt,MediaType.APPLICATION_JSON));
        }
        for (int i=0;i<3;i++) assertThat(service.sendDocumentToGroupOnce("manager","123456789@g.us","Новая услуга 😀",
                "fixture".getBytes(StandardCharsets.UTF_8),"предложение.txt","text/plain","offer:1").errorCode()).isEqualTo("operation_unknown");
        server.verify();
    }
}
