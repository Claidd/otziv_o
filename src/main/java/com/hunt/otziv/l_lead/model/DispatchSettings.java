package com.hunt.otziv.l_lead.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "dispatch_settings")
public class DispatchSettings {

    @Id
    private Long id;

    @Column(name = "cron_expression")
    private String cronExpression;

    @Column(name = "last_run")
    private Date lastRun;

    // геттеры/сеттеры
}
