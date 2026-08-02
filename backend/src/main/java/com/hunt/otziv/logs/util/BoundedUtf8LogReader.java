package com.hunt.otziv.logs.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Reads a logical log line without allowing one malformed or very large line to
 * allocate unbounded memory. The returned text never ends in a split UTF-8 code
 * point.
 */
public final class BoundedUtf8LogReader {

    private static final String TRUNCATED_SUFFIX = " … [truncated]";
    private static final int TRUNCATED_SUFFIX_BYTES = TRUNCATED_SUFFIX.getBytes(StandardCharsets.UTF_8).length;

    private BoundedUtf8LogReader() {
    }

    public static Line readLine(RandomAccessFile file, int maxValueBytes, long endExclusive) throws IOException {
        if (maxValueBytes <= 0) {
            throw new IllegalArgumentException("maxValueBytes must be positive");
        }

        long boundedEnd = Math.max(0L, endExclusive);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(maxValueBytes, 4_096));
        boolean readAnyByte = false;
        boolean terminated = false;
        boolean truncated = false;

        while (file.getFilePointer() < boundedEnd) {
            int next = file.read();
            if (next == -1) {
                break;
            }
            readAnyByte = true;

            if (next == '\n') {
                terminated = true;
                break;
            }
            if (next == '\r') {
                terminated = true;
                if (file.getFilePointer() < boundedEnd) {
                    long afterCarriageReturn = file.getFilePointer();
                    int possibleLineFeed = file.read();
                    if (possibleLineFeed != '\n' && possibleLineFeed != -1) {
                        file.seek(afterCarriageReturn);
                    }
                }
                break;
            }

            if (buffer.size() < maxValueBytes) {
                buffer.write(next);
            } else {
                truncated = true;
            }
        }

        if (!readAnyByte) {
            return null;
        }

        return toLine(buffer, maxValueBytes, truncated, terminated);
    }

    private static Line toLine(
            ByteArrayOutputStream buffer,
            int maxValueBytes,
            boolean truncated,
            boolean terminated
    ) {
        byte[] bytes = buffer.toByteArray();
        int prefixLimit = truncated && maxValueBytes >= TRUNCATED_SUFFIX_BYTES
                ? maxValueBytes - TRUNCATED_SUFFIX_BYTES
                : maxValueBytes;
        int completeLength = completeUtf8PrefixLength(bytes, Math.min(bytes.length, prefixLimit));
        if (completeLength != bytes.length) {
            truncated = true;
        }
        String value = new String(bytes, 0, completeLength, StandardCharsets.UTF_8);
        if (truncated && maxValueBytes >= TRUNCATED_SUFFIX_BYTES) {
            value += TRUNCATED_SUFFIX;
        }
        return new Line(value, value.getBytes(StandardCharsets.UTF_8).length, terminated);
    }

    /**
     * Stateful bounded scanner for a followed file. It retains only the bounded
     * prefix of an unfinished logical line and resumes from the caller's current
     * file position, so a growing line is never rescanned from its beginning.
     */
    public static final class IncrementalReader {

        private final int maxValueBytes;
        private final ByteArrayOutputStream buffer;
        private boolean truncated;
        private boolean skipLineFeed;

        public IncrementalReader(int maxValueBytes) {
            if (maxValueBytes <= 0) {
                throw new IllegalArgumentException("maxValueBytes must be positive");
            }
            this.maxValueBytes = maxValueBytes;
            this.buffer = new ByteArrayOutputStream(Math.min(maxValueBytes, 4_096));
        }

        /**
         * Scans at most {@code maxScanBytes} from the current file position.
         * A line is returned only after its terminator is consumed; otherwise
         * the bounded partial state is retained for the next invocation.
         */
        public ScanResult readNext(
                RandomAccessFile file,
                long endExclusive,
                int maxScanBytes
        ) throws IOException {
            if (maxScanBytes <= 0) {
                throw new IllegalArgumentException("maxScanBytes must be positive");
            }

            long boundedEnd = Math.max(0L, endExclusive);
            int scannedBytes = 0;
            while (file.getFilePointer() < boundedEnd && scannedBytes < maxScanBytes) {
                int next = file.read();
                if (next == -1) {
                    break;
                }
                scannedBytes++;

                if (skipLineFeed) {
                    skipLineFeed = false;
                    if (next == '\n') {
                        continue;
                    }
                }

                if (next == '\n') {
                    return new ScanResult(completeLine(), scannedBytes);
                }
                if (next == '\r') {
                    // Consume a possible LF on the next invocation. This also
                    // works when CR and LF arrive in different file snapshots.
                    skipLineFeed = true;
                    return new ScanResult(completeLine(), scannedBytes);
                }

                if (buffer.size() < maxValueBytes) {
                    buffer.write(next);
                } else {
                    truncated = true;
                }
            }
            return new ScanResult(null, scannedBytes);
        }

        public void reset() {
            buffer.reset();
            truncated = false;
            skipLineFeed = false;
        }

        private Line completeLine() {
            Line line = toLine(buffer, maxValueBytes, truncated, true);
            buffer.reset();
            truncated = false;
            return line;
        }
    }

    private static int completeUtf8PrefixLength(byte[] bytes, int length) {
        if (length == 0 || (bytes[length - 1] & 0x80) == 0) {
            return length;
        }

        int lead = length - 1;
        while (lead >= 0 && (bytes[lead] & 0xC0) == 0x80) {
            lead--;
        }
        if (lead < 0) {
            return 0;
        }

        int first = bytes[lead] & 0xFF;
        int expectedLength;
        if ((first & 0xE0) == 0xC0) {
            expectedLength = 2;
        } else if ((first & 0xF0) == 0xE0) {
            expectedLength = 3;
        } else if ((first & 0xF8) == 0xF0) {
            expectedLength = 4;
        } else {
            return length;
        }

        return length - lead < expectedLength ? lead : length;
    }

    public record Line(String value, int storedBytes, boolean terminated) {
    }

    public record ScanResult(Line line, int scannedBytes) {
    }
}
