package com.hunt.otziv.client_chat_control.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClientChatContentClassifierTest {

    @Test
    void recognizesTelegramMarkersAndDocumentFilenamesAsAttachments() {
        assertThat(ClientChatContentClassifier.attachmentOnly("[Вложение: file]")).isTrue();
        assertThat(ClientChatContentClassifier.attachmentOnly("[image]")).isTrue();
        assertThat(ClientChatContentClassifier.attachmentOnly("Документ-2026-07-26 180739.pdf")).isTrue();
        assertThat(ClientChatContentClassifier.attachmentOnly("Счёт на оплату.xlsx")).isTrue();
    }

    @Test
    void doesNotHideQuestionThatOnlyMentionsAFile() {
        assertThat(ClientChatContentClassifier.attachmentOnly(
                "Проверьте документ.pdf и скажите, всё ли правильно?"
        )).isFalse();
    }
}
