package com.hunt.otziv.config.poi;

import jakarta.annotation.PostConstruct;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.springframework.context.annotation.Configuration;

/**
 * Lead and bot imports build the whole XLSX DOM before their row limit is checked. A small file
 * can inflate into gigabytes of XML and take the whole backend down with OutOfMemoryError
 * (-Xmx768m). These process-wide Apache POI limits reject such files while parsing: a real
 * import of a few thousand rows is far below them.
 */
@Configuration
public class SpreadsheetImportLimits {

    /** Largest uncompressed part (sheet XML) accepted; the default inflate-ratio check stays on. */
    static final long MAX_ENTRY_SIZE_BYTES = 25L * 1024 * 1024;

    @PostConstruct
    void applyLimits() {
        ZipSecureFile.setMaxEntrySize(MAX_ENTRY_SIZE_BYTES);
    }
}
