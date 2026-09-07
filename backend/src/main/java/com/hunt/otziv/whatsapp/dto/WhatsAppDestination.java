package com.hunt.otziv.whatsapp.dto;

public final class WhatsAppDestination {
    private WhatsAppDestination() {}
    public static String normalize(String kind,String value) {
        if(value==null) throw new IllegalArgumentException("invalid_operation_envelope");
        if("send".equals(kind)) {
            String digits=value.replaceAll("\\D+","");
            if(digits.startsWith("8")&&digits.length()==11)digits="7"+digits.substring(1);
            if(digits.isEmpty())throw new IllegalArgumentException("invalid_operation_envelope");
            return digits;
        }
        String group=value.strip();
        if(group.matches("[0-9]+(?:-[0-9]+)?"))group+="@g.us";
        if(!group.matches("[0-9]+(?:-[0-9]+)?@g\\.us"))throw new IllegalArgumentException("invalid_operation_envelope");
        return group;
    }
}
