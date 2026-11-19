package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.whatsapp.api.WhatsAppGroupDeliveryBlocks;
import com.hunt.otziv.whatsapp.dto.WhatsAppGroupInfo;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WhatsAppChatAccessServiceTest {
    WhatsAppService gateway=mock(WhatsAppService.class);
    WhatsAppGroupDeliveryBlocks blocks=mock(WhatsAppGroupDeliveryBlocks.class);
    WhatsAppChatAccessService access=new WhatsAppChatAccessService(gateway,blocks);
    @Test void missingOrStaleSnapshotIsNotEvidenceOfMissingChat() {
        when(gateway.groupsSnapshot("vika")).thenReturn(new WhatsAppService.GroupsSnapshot(List.of(),true,false,null));
        assertThat(access.check("vika","1@g.us").status()).isEqualTo("UNKNOWN");
        when(gateway.groupsSnapshot("vika")).thenReturn(new WhatsAppService.GroupsSnapshot(
                List.of(new WhatsAppGroupInfo("1@g.us","fixture",null,"not_member")),true,true,null));
        assertThat(access.check("vika","1@g.us").status()).isEqualTo("UNKNOWN");
    }
    @Test void invitationPermissionsAreDifferentFromMembership() {
        for(String reason:List.of("not_admin","ok","not_member","timeout","refused")) {
            when(gateway.groupsSnapshot("vika")).thenReturn(new WhatsAppService.GroupsSnapshot(
                    List.of(new WhatsAppGroupInfo("1@g.us","fixture",null,reason)),true,false,null));
            assertThat(access.check("vika","1@g.us").status()).isEqualTo(switch(reason){case "not_admin","ok"->"AVAILABLE";case "not_member"->"UNAVAILABLE";default->"UNKNOWN";});
        }
    }
    @Test void settledDeliveryRejectionIsPreservedUntilExplicitRecovery() {
        when(blocks.isBlocked("vika","1@g.us")).thenReturn(true);
        assertThat(access.check("vika","1@g.us").reason()).isEqualTo("delivery_rejected");
        verifyNoInteractions(gateway);verify(blocks,never()).clearGroup("1@g.us");
    }
}
