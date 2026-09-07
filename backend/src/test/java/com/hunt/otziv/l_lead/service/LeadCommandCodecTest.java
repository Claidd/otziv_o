package com.hunt.otziv.l_lead.service;

import com.hunt.otziv.config.jwt.service.JwtService;
import com.hunt.otziv.l_lead.dto.LeadDtoTransfer;
import jakarta.validation.Validation;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LeadCommandCodecTest {
    @Test
    void roundTripPreservesDatesOptionalFieldsAndSignedBusinessContent() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var codec = new LeadCommandCodec(factory.getValidator());
            var dto = LeadDtoTransfer.builder().telephoneLead("79990000000").cityLead("Иркутск")
                    .companyName("Тест").createDate(LocalDate.of(2026,9,7))
                    .updateStatus(LocalDateTime.of(2026,9,7,12,30,0,123456000)).build();
            String json = codec.encode(dto);
            var restored = (LeadDtoTransfer) codec.decode("SYNC", 1, json);
            assertThat(restored).isEqualTo(dto);
            assertThat(json).contains("2026-09-07T12:30:00.123456").doesNotContain("command");
            // Measured with the immutable pre-protocol compiled DTO/JwtService snapshot.
            assertThat(new JwtService().generateChecksum(dto))
                    .isEqualTo("5c375b7cbc6419927904ca7aa58ecaef20d08373be1f8ed5e919ff5818016137");
            assertThat(new JwtService().generateChecksum(restored)).isEqualTo(new JwtService().generateChecksum(dto));
            assertThat(codec.decode("SYNC", 0, json)).isEqualTo(dto);
        }
    }

    @Test
    void corruptEmptyAndUnsupportedPayloadsCannotBecomeCommands() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var codec = new LeadCommandCodec(factory.getValidator());
            for (String json : new String[]{"{}", "null", "{bad", ""}) {
                assertThatThrownBy(() -> codec.decode("SYNC",1,json)).isInstanceOf(IllegalArgumentException.class);
            }
            assertThatThrownBy(() -> codec.decode("SYNC",2,"{}")).hasMessage("LEAD_PAYLOAD_VERSION_UNSUPPORTED");
            assertThatThrownBy(() -> codec.encode(new LeadDtoTransfer())).hasMessage("LEAD_PAYLOAD_INVALID");
        }
    }
}
