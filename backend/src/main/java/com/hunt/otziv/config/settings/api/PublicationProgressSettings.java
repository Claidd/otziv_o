package com.hunt.otziv.config.settings.api;

/** Configuration owned by settings; callers do not depend on storage keys or caches. */
public interface PublicationProgressSettings {
    String DEFAULT_TEMPLATE = "{companyAndFilial}. Опубликован новый текст {progress}.";
    boolean immediatePublicationMessagesEnabled();
    boolean publicationProgressReportsEnabled();
    String publicationProgressTemplate();
}
