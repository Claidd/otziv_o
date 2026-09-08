package com.hunt.otziv.l_lead.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hunt.otziv.l_lead.dto.LeadDtoTransfer;
import com.hunt.otziv.l_lead.dto.LeadUpdateDto;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/** Versioned immutable business payload; signing timestamps are transport metadata. */
@Component
public class LeadCommandCodec {
    public static final int VERSION = 1;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Validator validator;

    public LeadCommandCodec(Validator validator) { this.validator = validator; }

    public String encode(Object payload) {
        validate(payload);
        try {
            String json = mapper.writeValueAsString(payload);
            if (json.getBytes(StandardCharsets.UTF_8).length > 1_000_000) {
                throw new IllegalArgumentException("LEAD_PAYLOAD_TOO_LARGE");
            }
            return json;
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalArgumentException("LEAD_PAYLOAD_SERIALIZATION_FAILED", error);
        }
    }

    public Object decode(String kind, int version, String json) {
        if (version != VERSION && version != 0) throw new IllegalArgumentException("LEAD_PAYLOAD_VERSION_UNSUPPORTED");
        Class<?> type = switch (kind) {
            case "SYNC", "IMPORT" -> LeadDtoTransfer.class;
            case "UPDATE" -> LeadUpdateDto.class;
            default -> throw new IllegalArgumentException("LEAD_COMMAND_UNSUPPORTED");
        };
        if (json == null || json.isBlank() || json.getBytes(StandardCharsets.UTF_8).length > 1_000_000) {
            throw new IllegalArgumentException("LEAD_PAYLOAD_MISSING_OR_TOO_LARGE");
        }
        try {
            Object value = mapper.readValue(json, type);
            validate(value);
            return value;
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalArgumentException("LEAD_PAYLOAD_CORRUPT", error);
        }
    }

    public com.hunt.otziv.l_lead.dto.LeadCommandReceipt receipt(String json) {
        try { return mapper.readValue(json,com.hunt.otziv.l_lead.dto.LeadCommandReceipt.class); }
        catch (java.io.IOException | IllegalArgumentException error) { throw new IllegalArgumentException("LEAD_RECEIPT_INVALID",error); }
    }

    private void validate(Object value) {
        if (!(value instanceof LeadDtoTransfer) && !(value instanceof LeadUpdateDto)) {
            throw new IllegalArgumentException("LEAD_PAYLOAD_TYPE_INVALID");
        }
        if (!validator.validate(value).isEmpty()) throw new IllegalArgumentException("LEAD_PAYLOAD_INVALID");
    }
}
