package com.hunt.otziv.l_lead.service;

import com.hunt.otziv.config.jwt.service.JwtService;
import com.hunt.otziv.l_lead.dto.LeadDtoTransfer;
import com.hunt.otziv.l_lead.dto.LeadCommandIdentity;
import com.hunt.otziv.l_lead.dto.LeadCommandReceipt;
import com.hunt.otziv.l_lead.repository.LeadCommandRepository;
import jakarta.validation.Validation;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.assertj.core.api.Assertions.*;

class LeadCommandWorkerTest {
    private static final String OP="11111111-1111-4111-8111-111111111111";
    private static final String SOURCE="22222222-2222-4222-8222-222222222222";
    @Test
    void uncertainTransportAndServerResultsNeverScheduleBlindRetries() throws Exception {
        for (int status : new int[]{0, 500, 503, 408}) {
            try (var validator = Validation.buildDefaultValidatorFactory()) {
                var codec = new LeadCommandCodec(validator.getValidator());
                var repository = mock(LeadCommandRepository.class);
                var jwt = mock(JwtService.class);
                when(jwt.generateSyncToken(anyString(), any())).thenReturn("fixture-token");
                when(jwt.generateChecksum(any())).thenAnswer(c->new JwtService().generateChecksum(c.getArgument(0)));
                var rest = new RestTemplate();
                var server = MockRestServiceServer.bindTo(rest).build();
                var worker = new LeadCommandWorker(repository, codec, jwt, rest);
                ReflectionTestUtils.setField(worker, "syncUrl", "http://fixture/sync");
                ReflectionTestUtils.setField(worker, "maxAttempts", 20);
                var claim = claim(codec,jwt);
                var expectation = server.expect(requestTo("http://fixture/sync"))
                        .andExpect(header("Idempotency-Key", OP));
                expectation.andRespond(status == 0 ? withException(new IOException("connection lost after request"))
                        : withStatus(HttpStatus.valueOf(status)));
                worker.deliver(claim);
                verify(repository).complete(eq(claim), eq("UNKNOWN"), anyString(), anyInt());
                verifyNoMoreInteractions(repository);
                server.verify();
            }
        }
    }

    @Test void onlyAnExactCommittedReceiptCanConfirmDeliveryAndLegacySuccessStaysUnknown() throws Exception {
        try(var validator=Validation.buildDefaultValidatorFactory()) {
            var codec=new LeadCommandCodec(validator.getValidator());var jwt=mock(JwtService.class);
            when(jwt.generateChecksum(any())).thenAnswer(c->new JwtService().generateChecksum(c.getArgument(0)));
            when(jwt.generateSyncToken(anyString(),any())).thenReturn(java.util.UUID.randomUUID().toString());
            var claim=claim(codec,jwt);var identity=LeadCommandProtocol.identity(codec.decode("SYNC",1,claim.json()));
            var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
            String exact=mapper.writeValueAsString(LeadCommandReceipt.of(identity,claim.hash(),"APPLIED",1));
            String stale=mapper.writeValueAsString(LeadCommandReceipt.of(identity,claim.hash(),"STALE",2));
            for(String response:java.util.List.of(exact,stale,"Лид создан",exact.replace(OP,java.util.UUID.randomUUID().toString()),"{}")) {
                var repository=mock(LeadCommandRepository.class);var rest=new RestTemplate();var server=MockRestServiceServer.bindTo(rest).build();
                var worker=new LeadCommandWorker(repository,codec,jwt,rest);
                ReflectionTestUtils.setField(worker,"syncUrl","http://fixture/sync");
                server.expect(requestTo("http://fixture/sync")).andRespond(withSuccess(response,org.springframework.http.MediaType.APPLICATION_JSON));
                worker.deliver(claim);
                boolean confirmed=response.equals(exact)||response.equals(stale);
                verify(repository).complete(eq(claim),eq(confirmed?"SUCCEEDED":"UNKNOWN"),confirmed?isNull():anyString(),eq(0));
                verifyNoMoreInteractions(repository);server.verify();
            }
        }
    }

    @Test void sourceActivationCannotBeInferredFromClonedDatabaseOrAnEmptyProperty() {
        var repository=mock(LeadCommandRepository.class);
        var worker=new LeadCommandWorker(repository,mock(LeadCommandCodec.class),mock(JwtService.class),new RestTemplate());
        ReflectionTestUtils.setField(worker,"enabled",true);
        assertThatThrownBy(worker::dispatch).hasMessageContaining("explicitly activated");
        verifyNoMoreInteractions(repository);
    }

    private LeadCommandRepository.Claim claim(LeadCommandCodec codec,JwtService jwt) {
        var dto=LeadDtoTransfer.builder().telephoneLead("79990000000").cityLead("Fixture").createDate(java.time.LocalDate.of(2026,9,7))
                .command(new LeadCommandIdentity(1,SOURCE,1,1,OP,"SYNC")).build();
        return new LeadCommandRepository.Claim(1L,OP,"SYNC",1,codec.encode(dto),1,"claim-token",SOURCE,1L,jwt.generateChecksum(dto));
    }

    @Test
    void corruptPayloadDoesNotReachTransport() {
        try (var validator = Validation.buildDefaultValidatorFactory()) {
            var repository = mock(LeadCommandRepository.class);
            var rest = new RestTemplate();
            var server = MockRestServiceServer.bindTo(rest).build();
            var worker = new LeadCommandWorker(repository, new LeadCommandCodec(validator.getValidator()), mock(JwtService.class), rest);
            var claim = new LeadCommandRepository.Claim(1L,"stable-command","SYNC",1,"{}",1,"claim-token");
            worker.deliver(claim);
            verify(repository).complete(eq(claim),eq("QUARANTINED"),anyString(),eq(0));
            server.verify();
        }
    }
}
