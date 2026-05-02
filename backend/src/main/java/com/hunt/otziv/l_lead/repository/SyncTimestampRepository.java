package com.hunt.otziv.l_lead.repository;

import com.hunt.otziv.l_lead.model.SyncTimestamp;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncTimestampRepository extends JpaRepository<SyncTimestamp, String> {}

