package com.hunt.otziv.p_products.status.service;

import com.hunt.otziv.config.settings.api.PublicationProgressSettings;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicationProgressMessageTest {
    final PublicationProgressSettings settings = mock(PublicationProgressSettings.class);
    final PublicationProgressMessage messages = new PublicationProgressMessage(settings);
    final AtomicInteger reads = new AtomicInteger();

    PublicationProgressMessage.Context context() {
        reads.incrementAndGet();
        return new PublicationProgressMessage.Context(true, " Компания ", " Филиал ");
    }

    @Test void disabledMessagesDoNotLoadCompanyFilialOrTemplate() {
        assertThat(messages.prepare(10, 2, this::context)).isNull();
        assertThat(reads).hasValue(0);
        verify(settings).immediatePublicationMessagesEnabled();
        verifyNoMoreInteractions(settings);
    }

    @Test void finalReviewDoesNotRequestOptionalProgressSettingsOrLazyRelations() {
        when(settings.immediatePublicationMessagesEnabled()).thenReturn(true);
        assertThat(messages.prepare(2, 2, this::context)).isNull();
        assertThat(reads).hasValue(0);
        verify(settings, never()).publicationProgressReportsEnabled();
        verify(settings, never()).publicationProgressTemplate();
    }

    @Test void disabledCompanySkipsTemplateAndGlobalOffSkipsCompany() {
        when(settings.immediatePublicationMessagesEnabled()).thenReturn(true);
        assertThat(messages.prepare(10, 2, this::context)).isNull();
        assertThat(reads).hasValue(0);
        when(settings.publicationProgressReportsEnabled()).thenReturn(true);
        assertThat(messages.prepare(10, 2, () -> new PublicationProgressMessage.Context(false, "Company", ""))).isNull();
        verify(settings, never()).publicationProgressTemplate();
    }

    @Test void allowedProgressLoadsContextOnceAndPreservesTemplateVariables() {
        when(settings.immediatePublicationMessagesEnabled()).thenReturn(true);
        when(settings.publicationProgressReportsEnabled()).thenReturn(true);
        when(settings.publicationProgressTemplate()).thenReturn("{companyAndFilial}: {progress}; {company}/{filial}; {published}/{total}");
        assertThat(messages.prepare(10, 2, this::context)).isEqualTo("Компания - Филиал: 2 / 10; Компания/Филиал; 2/10");
        assertThat(reads).hasValue(1);
        verify(settings, times(1)).immediatePublicationMessagesEnabled();
        verify(settings, times(1)).publicationProgressReportsEnabled();
        verify(settings, times(1)).publicationProgressTemplate();
    }

    @Test void missingNamesAndTemplateUseExistingFallbackWithoutDroppingProgress() {
        when(settings.immediatePublicationMessagesEnabled()).thenReturn(true);
        when(settings.publicationProgressReportsEnabled()).thenReturn(true);
        assertThat(messages.prepare(0, 1, () -> new PublicationProgressMessage.Context(true, null, " ")))
                .isEqualTo("Компания. Опубликован новый отзыв 1 / 1.");
    }

    @Test void failedFreshSettingsReadAbortsPreparationBeforeLoadingRelations() {
        when(settings.immediatePublicationMessagesEnabled()).thenThrow(new IllegalStateException("settings unavailable"));
        assertThatThrownBy(() -> messages.prepare(10, 2, this::context)).isInstanceOf(IllegalStateException.class);
        assertThat(reads).hasValue(0);
    }
}
