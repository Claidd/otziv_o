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
    void gatewayMarkerIsAnAttachmentWithAnyTypeAndWithOrWithoutCaption() {
        // A captioned file is a file (the reply rule needs a look at it), but not «only» a file: its caption is assessed.
        assertThat(ClientChatContentClassifier.hasAttachmentMarker("[Вложение: image] Спасибо 🙏")).isTrue();
        assertThat(ClientChatContentClassifier.attachmentOnly("[Вложение: image] Спасибо 🙏")).isFalse();
        assertThat(ClientChatContentClassifier.attachmentOnly("[Вложение: document]")).isTrue();
        assertThat(ClientChatContentClassifier.hasAttachmentMarker("[Вложение: photo] Оплатила")).isTrue();
        assertThat(ClientChatContentClassifier.attachmentOnly("[Вложение]")).isTrue();
        assertThat(ClientChatContentClassifier.hasAttachmentMarker("[Вложение] Счёт")).isTrue();
        assertThat(ClientChatContentClassifier.attachmentOnly("  [вложение:file]  ")).isTrue();
        assertThat(ClientChatContentClassifier.attachmentOnly("Фото.JPG")).isTrue();
        assertThat(ClientChatContentClassifier.attachmentOnly("архив.zip")).isTrue();
        assertThat(ClientChatContentClassifier.attachmentOnly("Договор.docx")).isTrue();
    }

    @Test
    void reactionAndPlainTextAreNotAttachments() {
        assertThat(ClientChatContentClassifier.attachmentOnly("[Вложение: reaction]")).isFalse();
        assertThat(ClientChatContentClassifier.hasAttachmentMarker("[Вложение: reaction]")).isFalse();
        assertThat(ClientChatContentClassifier.attachmentOnly("Спасибо 🙏")).isFalse();
        assertThat(ClientChatContentClassifier.attachmentOnly("Вложение пришлю завтра")).isFalse();
        assertThat(ClientChatContentClassifier.attachmentOnly("Спасибо [Вложение: image]")).isFalse();
        assertThat(ClientChatContentClassifier.attachmentOnly(null)).isFalse();
    }

    @Test
    void doesNotHideQuestionThatOnlyMentionsAFile() {
        assertThat(ClientChatContentClassifier.attachmentOnly(
                "Проверьте документ.pdf и скажите, всё ли правильно?"
        )).isFalse();
    }
}
