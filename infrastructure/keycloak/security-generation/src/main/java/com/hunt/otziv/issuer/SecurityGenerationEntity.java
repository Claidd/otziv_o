package com.hunt.otziv.issuer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Issuer-owned tombstone; never a user-editable profile attribute. */
@Entity
@Table(name = "OTZIV_SEC_GENERATION")
public class SecurityGenerationEntity {
    @Id @Column(name = "ID", length = 64) private String id;
    @Column(name = "REALM_ID", nullable = false, length = 255) private String realmId;
    @Column(name = "SUBJECT_ID", nullable = false, length = 255) private String subjectId;
    @Column(name = "GENERATION", nullable = false) private long generation;
    public SecurityGenerationEntity() {}
}
