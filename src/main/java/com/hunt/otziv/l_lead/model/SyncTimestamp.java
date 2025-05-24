package com.hunt.otziv.l_lead.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "sync_metadata")
public class SyncTimestamp {

    @Id
    private String id;

    private LocalDateTime lastSync;
}
