package com.hunt.otziv.logs;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedUtf8LogReaderTest {

    @TempDir
    Path tempDirectory;

    @Test
    void resumesOversizedUnterminatedLineWithinThePerCallScanBudget() throws Exception {
        Path log = tempDirectory.resolve("app.log");
        Files.writeString(log, "аб🙂".repeat(64), StandardCharsets.UTF_8);

        int maxValueBytes = 31;
        int scanBudget = 13;
        BoundedUtf8LogReader.IncrementalReader reader =
                new BoundedUtf8LogReader.IncrementalReader(maxValueBytes);

        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "rw")) {
            long unterminatedEnd = file.length();
            while (file.getFilePointer() < unterminatedEnd) {
                long before = file.getFilePointer();
                BoundedUtf8LogReader.ScanResult scan = reader.readNext(file, unterminatedEnd, scanBudget);

                assertThat(scan.line()).isNull();
                assertThat(scan.scannedBytes()).isEqualTo(file.getFilePointer() - before);
                assertThat(scan.scannedBytes()).isBetween(1, scanBudget);
            }

            BoundedUtf8LogReader.ScanResult atSnapshotEnd =
                    reader.readNext(file, unterminatedEnd, scanBudget);
            assertThat(atSnapshotEnd.line()).isNull();
            assertThat(atSnapshotEnd.scannedBytes()).isZero();

            long resumeOffset = file.getFilePointer();
            file.seek(file.length());
            file.write("\nпосле\n".getBytes(StandardCharsets.UTF_8));
            long completedEnd = file.length();
            file.seek(resumeOffset);

            List<BoundedUtf8LogReader.Line> completedLines = new ArrayList<>();
            while (file.getFilePointer() < completedEnd) {
                long before = file.getFilePointer();
                BoundedUtf8LogReader.ScanResult scan = reader.readNext(file, completedEnd, scanBudget);

                assertThat(scan.scannedBytes()).isEqualTo(file.getFilePointer() - before);
                assertThat(scan.scannedBytes()).isBetween(1, scanBudget);
                if (scan.line() != null) {
                    completedLines.add(scan.line());
                }
            }

            assertThat(completedLines).hasSize(2);
            assertThat(completedLines.get(0).value())
                    .endsWith(" … [truncated]")
                    .doesNotContain("�");
            assertThat(completedLines.get(0).storedBytes()).isLessThanOrEqualTo(maxValueBytes);
            assertThat(completedLines.get(1).value()).isEqualTo("после");
        }
    }

    @Test
    void consumesCrLfAcrossScanCallsWithoutProducingAnEmptyLine() throws Exception {
        Path log = tempDirectory.resolve("crlf.log");
        Files.writeString(log, "one\r\ntwo\n", StandardCharsets.UTF_8);
        BoundedUtf8LogReader.IncrementalReader reader = new BoundedUtf8LogReader.IncrementalReader(32);

        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
            long end = file.length();

            BoundedUtf8LogReader.ScanResult first = reader.readNext(file, end, 4);
            BoundedUtf8LogReader.ScanResult lineFeedOnly = reader.readNext(file, end, 1);
            BoundedUtf8LogReader.ScanResult second = reader.readNext(file, end, 32);

            assertThat(first.line().value()).isEqualTo("one");
            assertThat(first.scannedBytes()).isEqualTo(4);
            assertThat(lineFeedOnly.line()).isNull();
            assertThat(lineFeedOnly.scannedBytes()).isEqualTo(1);
            assertThat(second.line().value()).isEqualTo("two");
        }
    }
}
