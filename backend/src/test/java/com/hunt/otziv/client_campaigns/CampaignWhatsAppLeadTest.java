package com.hunt.otziv.client_campaigns;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.hunt.otziv.whatsapp.api.WhatsAppBusinessOperations;
import com.hunt.otziv.whatsapp.config.WhatsAppProperties;
import com.hunt.otziv.whatsapp.dto.WhatsAppOperationEnvelope;
import com.hunt.otziv.whatsapp.service.WhatsAppServiceImpl;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class CampaignWhatsAppLeadTest {
    final RestTemplate rest=new RestTemplate();
    final MockRestServiceServer server=MockRestServiceServer.bindTo(rest).build();
    final WhatsAppBusinessOperations operations=mock(WhatsAppBusinessOperations.class);
    WhatsAppServiceImpl service() {
        var config=new WhatsAppProperties.ClientConfig(); config.setId("manager"); config.setUrl("https://wa.fixture");
        var properties=new WhatsAppProperties(); properties.setClients(List.of(config));
        return new WhatsAppServiceImpl(properties,rest,operations,null);
    }
    String receipt(String hash) {
        return "{\"status\":\"ok\",\"operationId\":\"offer:lead:1\",\"state\":\"SUCCEEDED\",\"messageId\":\"mid\",\"envelopeHash\":\""+hash+"\"}";
    }
    @Test void personalFileEnvelopeMatchesGatewayAndUsesPhoneEndpoint() {
        byte[] bytes="fixture".getBytes(StandardCharsets.UTF_8);
        String payload=WhatsAppOperationEnvelope.documentPayload("Новая услуга 😀","предложение.txt","text/plain",bytes);
        String hash=WhatsAppOperationEnvelope.phoneDocumentHash("manager","79991111111",payload);
        assertThat(hash).isEqualTo("f5d37a8c4e9505d049b069b69c942ffe519e659d08d6df48f500c0c4a1b70ab1");
        server.expect(requestTo("https://wa.fixture/send-file")).andExpect(jsonPath("$.phone").value("79991111111"))
                .andExpect(jsonPath("$.groupId").doesNotExist()).andRespond(withSuccess(receipt(hash),MediaType.APPLICATION_JSON));
        assertThat(service().sendDocumentOnce("manager","79991111111","Новая услуга 😀",bytes,"предложение.txt","text/plain","offer:lead:1").sent()).isTrue();
        server.verify();
    }
    @Test void personalTextFreezesBeforeDispatchAndRequiresMatchingReceipt() {
        String hash=WhatsAppOperationEnvelope.phoneHash("manager","79991111111","Body");
        server.expect(requestTo("https://wa.fixture/send")).andRespond(withSuccess(receipt(hash),MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://wa.fixture/send")).andRespond(withSuccess(receipt("bad"),MediaType.APPLICATION_JSON));
        var service=service();
        assertThat(service.sendMessageOnce("manager","79991111111","Body","offer:lead:1").sent()).isTrue();
        assertThat(service.sendMessageOnce("manager","79991111111","Body","offer:lead:1").errorCode()).isEqualTo("operation_unknown");
        verify(operations,times(2)).freezeForDispatch("offer:lead:1","manager","send","79991111111","Body");
        verify(operations,times(4)).requireMatches("offer:lead:1","manager","send","79991111111","Body");
        server.verify();
    }
    @Test void frozenPayloadConflictStopsBeforeNetwork() {
        doThrow(new IllegalArgumentException("operation_payload_conflict")).when(operations)
                .requireMatches("offer:lead:1","manager","send","79991111111","Body");
        assertThat(service().sendMessageOnce("manager","79991111111","Body","offer:lead:1").sent()).isFalse();
        server.verify();
    }
}
