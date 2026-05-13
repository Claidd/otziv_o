package com.hunt.otziv.reputationai.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "reputation_ai_prompt_history")
public class ReputationAiPromptHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "history_id")
    private Long id;

    @Column(name = "prompt_key", nullable = false, length = 128)
    private String promptKey;

    @Column(name = "action", nullable = false, length = 32)
    private String action;

    @Column(name = "actor", length = 160)
    private String actor;

    @Lob
    @Column(name = "previous_content", columnDefinition = "LONGTEXT")
    private String previousContent;

    @Lob
    @Column(name = "content", nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getPromptKey() {
        return promptKey;
    }

    public void setPromptKey(String promptKey) {
        this.promptKey = promptKey;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public String getPreviousContent() {
        return previousContent;
    }

    public void setPreviousContent(String previousContent) {
        this.previousContent = previousContent;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
