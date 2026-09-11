package com.hunt.otziv.l_lead.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.config.jwt.service.*;
import com.hunt.otziv.l_lead.controller.*;
import com.hunt.otziv.l_lead.dto.*;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real HTTP mapping and JWT checksum boundary; database semantics have a separate MySQL suite. */
class LeadCommandHttpContractTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final JwtService jwt=new JwtService();
    final LeadCommandReceiver receiver=mock(LeadCommandReceiver.class);
    final LeadCommandService producer=mock(LeadCommandService.class);
    MockMvc mvc;

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(jwt,"secret",UUID.randomUUID()+UUID.randomUUID().toString());
        var replay=mock(LeadTokenReplayGuard.class);when(replay.consume(anyString(),any())).thenReturn(true);
        mvc=MockMvcBuilders.standaloneSetup(new LeadSyncController(receiver),new LeadReceiverController(receiver),
                new LeadSenderController(producer)).addFilters(new JwtAuthFilter(json,jwt,replay)).build();
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test void signedMetadataSurvivesHttpMappingAndExactReceiptIsReturned() throws Exception {
        var dto=payload();String token=jwt.generateSyncToken("POST:/api/leads/sync",dto);
        var receipt=LeadCommandReceipt.of(dto.getCommand(),jwt.generateChecksum(dto),"APPLIED",1);
        when(receiver.receive(eq("SYNC"),eq(dto),eq(token),eq(dto.getCommand().operationId())))
                .thenReturn(new LeadInboundMutationService.Result(200,receipt,1L));
        var response=mvc.perform(post("/api/leads/sync").contentType("application/json").content(json.writeValueAsBytes(dto))
                .header(LeadIntegrationHeaders.TOKEN,token).header("Idempotency-Key",dto.getCommand().operationId()))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(json.readValue(response.getContentAsByteArray(),LeadCommandReceipt.class)).isEqualTo(receipt);
        verify(receiver).receive("SYNC",dto,token,dto.getCommand().operationId());
    }

    @Test void metadataTamperingIsRejectedByTheRealFilterBeforeReceiverInvocation() throws Exception {
        var dto=payload();String token=jwt.generateToken(dto);var id=dto.getCommand();
        dto.setCommand(new LeadCommandIdentity(1,id.sourceId(),id.entityId(),2,id.operationId(),"IMPORT"));
        mvc.perform(post("/api/leads/import").contentType("application/json").content(json.writeValueAsBytes(dto))
                .header(LeadIntegrationHeaders.TOKEN,token).header("Idempotency-Key",id.operationId()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(receiver);
    }

    @Test void legacyWithoutMetadataKeepsTheExistingSignedBodyAndResponse() throws Exception {
        var dto=payload();dto.setCommand(null);String token=jwt.generateToken(dto);
        when(receiver.receive("IMPORT",dto,token,null)).thenReturn(new LeadInboundMutationService.Result(200,"Р›РёРґ СѓСЃРїРµС€РЅРѕ РёРјРїРѕСЂС‚РёСЂРѕРІР°РЅ",1L));
        mvc.perform(post("/api/leads/import").contentType("application/json").content(json.writeValueAsBytes(dto))
                .header(LeadIntegrationHeaders.TOKEN,token)).andExpect(status().isOk());
        verify(receiver).receive("IMPORT",dto,token,null);
    }

    @Test void manualEndpointOnlyEnqueuesAndExposesThePersistentOperationIdentity() throws Exception {
        String key=UUID.randomUUID().toString(),operation=UUID.randomUUID().toString();
        when(producer.enqueueImport(7,key)).thenReturn(operation);
        mvc.perform(post("/api/leads/sendToServer").param("leadId","7").header("Idempotency-Key",key))
                .andExpect(status().isOk()).andExpect(header().string("X-Lead-Command-Id",operation))
                .andExpect(content().string("redirect:/dashboard"));
        verify(producer).enqueueImport(7,key);verifyNoInteractions(receiver);
    }

    private LeadDtoTransfer payload(){return LeadDtoTransfer.builder().telephoneLead("79990000000").cityLead("Fixture")
            .createDate(LocalDate.of(2026,9,7)).companyName("Fixture")
            .command(new LeadCommandIdentity(1,"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",1,1,UUID.randomUUID().toString(),"SYNC")).build();}
}
