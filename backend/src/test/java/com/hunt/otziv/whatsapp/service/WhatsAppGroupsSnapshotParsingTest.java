package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.whatsapp.api.WhatsAppBusinessOperations;
import com.hunt.otziv.whatsapp.config.WhatsAppProperties;
import com.hunt.otziv.whatsapp.dto.WhatsAppGroupInfo;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The gateway's invite status of each group reaches the invite check instead of a bare missing link. */
class WhatsAppGroupsSnapshotParsingTest {

    private final RestTemplate rest = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();

    @Test
    void eachGroupKeepsTheGatewaysInviteStatus() {
        server.expect(requestTo("http://whatsapp_lika:3000/groups")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"status":"ok","clientId":"whatsapp_lika","groupsCachedAt":"2026-11-06T10:00:00.000Z",
                         "groupCount":4,"inviteCount":1,
                         "inviteStatusCounts":{"ok":1,"not_admin":1,"not_member":1},
                         "groups":[
                          {"groupId":"1@g.us","name":"Admin group","inviteCode":"LcXNWVfU4RpHayV7wJOFZw",
                           "inviteLink":"https://chat.whatsapp.com/LcXNWVfU4RpHayV7wJOFZw","inviteStatus":"ok","inviteError":null},
                          {"groupId":"2@g.us","name":"Member group","inviteCode":null,"inviteLink":null,
                           "inviteStatus":"not_admin","inviteError":"participant without admin rights; t status 403: t"},
                          {"groupId":"3@g.us","name":"Left group","inviteCode":null,"inviteLink":null,
                           "inviteStatus":"not_member","inviteError":"the account is not among 5 participants of the group"},
                          {"groupId":"4@g.us","name":"Older gateway","inviteCode":null,"inviteLink":null}
                         ]}""", MediaType.APPLICATION_JSON));

        WhatsAppService.GroupsSnapshot snapshot = service().groupsSnapshot("whatsapp_lika");

        assertTrue(snapshot.available());
        assertEquals("2026-11-06T10:00:00.000Z", snapshot.cachedAt());
        assertEquals(List.of(
                new WhatsAppGroupInfo("1@g.us", "Admin group", "https://chat.whatsapp.com/LcXNWVfU4RpHayV7wJOFZw", "ok"),
                new WhatsAppGroupInfo("2@g.us", "Member group", "", "not_admin"),
                new WhatsAppGroupInfo("3@g.us", "Left group", "", "not_member"),
                new WhatsAppGroupInfo("4@g.us", "Older gateway", "", "")), snapshot.groups());
        server.verify();
    }

    private WhatsAppServiceImpl service() {
        var client = new WhatsAppProperties.ClientConfig();
        client.setId("whatsapp_lika");
        client.setUrl("http://whatsapp_lika:3000");
        var properties = new WhatsAppProperties();
        properties.setClients(List.of(client));
        return new WhatsAppServiceImpl(properties, rest, mock(WhatsAppBusinessOperations.class), null);
    }
}
