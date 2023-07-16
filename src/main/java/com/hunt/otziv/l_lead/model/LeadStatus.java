package com.hunt.otziv.l_lead.model;

public enum LeadStatus {

    SUNDAY ("Воскресенье"),
    MONDAY ("Понедельник"),
    TUESDAY ("Вторник"),
    WEDNESDAY ("Среда"),
    THURSDAY ("Четверг"),
    FRIDAY ("Пятница"),
    SATURDAY ("Суббота");

    public String title;

    LeadStatus(String title) {
        this.title = title;
    }

}
