package com.hunt.otziv.maxbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MaxChatAccessServiceTest {
    @ParameterizedTest
    @CsvSource({"closed,UNAVAILABLE,max_chat_closed", "removed,UNAVAILABLE,bot_not_member",
            "left,UNAVAILABLE,bot_not_member", "active,AVAILABLE,member", "unexpected,UNKNOWN,access_unconfirmed"})
    void interpretsOnlyExplicitProviderStateWithoutSending(String state,String status,String reason) throws Exception {
        var client=mock(MaxBotClient.class);
        when(client.getChat(-123L)).thenReturn(new ObjectMapper().readTree("{\"chat_id\":-123,\"status\":\""+state+"\"}"));
        var result=new MaxChatAccessService(client).check(-123);
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.reason()).isEqualTo(reason);
        verify(client).getChat(-123L); verifyNoMoreInteractions(client);
    }
    @Test void absentWrongChatAndNetworkErrorNeverProveUnavailability() throws Exception {
        var client=mock(MaxBotClient.class);var service=new MaxChatAccessService(client);
        assertThat(service.check(-123).status()).isEqualTo("UNKNOWN");
        when(client.getChat(-123L)).thenReturn(new ObjectMapper().readTree("{\"chat_id\":-124,\"status\":\"closed\"}"));
        assertThat(service.check(-123).status()).isEqualTo("UNKNOWN");
        when(client.getChat(-123L)).thenThrow(new IllegalStateException("network"));
        assertThat(service.check(-123).status()).isEqualTo("UNKNOWN");
    }
}
