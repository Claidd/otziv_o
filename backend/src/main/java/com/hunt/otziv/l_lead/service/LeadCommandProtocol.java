package com.hunt.otziv.l_lead.service;

import com.hunt.otziv.l_lead.dto.LeadCommandIdentity;
import com.hunt.otziv.l_lead.dto.LeadDtoTransfer;
import com.hunt.otziv.l_lead.dto.LeadUpdateDto;

public final class LeadCommandProtocol {
    private LeadCommandProtocol() {}

    public static LeadCommandIdentity identity(Object payload) {
        if (payload instanceof LeadDtoTransfer dto) return dto.getCommand();
        if (payload instanceof LeadUpdateDto dto) return dto.getCommand();
        throw new IllegalArgumentException("LEAD_PAYLOAD_TYPE_INVALID");
    }

    public static String phone(Object payload) {
        if (payload instanceof LeadDtoTransfer dto) return dto.getTelephoneLead();
        if (payload instanceof LeadUpdateDto dto) return dto.getTelephoneLead();
        throw new IllegalArgumentException("LEAD_PAYLOAD_TYPE_INVALID");
    }

    public static String phoneKey(String phone) {
        String digits = phone == null ? "" : phone.replaceAll("\\D+", "");
        if (digits.length() == 11 && digits.startsWith("8")) digits = "7" + digits.substring(1);
        if (digits.length() < 5 || digits.length() > 20) throw new IllegalArgumentException("LEAD_PHONE_INVALID");
        return digits;
    }
}
