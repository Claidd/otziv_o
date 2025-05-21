package com.hunt.otziv.l_lead.model;

public enum LeadStatus {

    NEW ("Новый"),
    SEND ("Отправленный"),
    RESEND ("Напоминание"),

    ARCHIVE ("К рассылке"),
    INWORK ("В работе"),
    FAIL ("Ошибка");

    public String title;

    LeadStatus(String title) {
        this.title = title;
    }

}
