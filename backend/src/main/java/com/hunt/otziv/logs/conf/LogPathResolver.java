package com.hunt.otziv.logs.conf;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class LogPathResolver {

    private static final Pattern ROTATED_LOG_NAME = Pattern.compile(
            "app\\.(\\d{4}-\\d{2}-\\d{2})\\.(0|[1-9]\\d{0,8})\\.log"
    );
    private static final Pattern ROTATED_LOG_SELECTOR = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2})\\.(0|[1-9]\\d{0,8})"
    );
    private static final Pattern LEGACY_DATE_SELECTOR = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    public Path getLogPath() {
        Path dockerPath = Path.of("/app/logs/app.log");
        Path localPath = Path.of("logs/app.log");
        return Files.exists(dockerPath) ? dockerPath : localPath;
    }

    /**
     * Resolves the current log (blank selector) or one exact rotated segment.
     * A date-only selector is retained for old bookmarks and resolves to the
     * highest segment index for that date.
     */
    public Optional<LogSelection> resolveLogSelection(String selector) throws java.io.IOException {
        if (selector == null || selector.isBlank()) {
            return Optional.of(new LogSelection("", getLogPath()));
        }

        Matcher exactSelector = ROTATED_LOG_SELECTOR.matcher(selector);
        if (exactSelector.matches() && isValidDate(exactSelector.group(1))) {
            Path candidate = archivePath(selector);
            return isRegularLogFile(candidate)
                    ? Optional.of(new LogSelection(selector, candidate))
                    : Optional.empty();
        }

        if (LEGACY_DATE_SELECTOR.matcher(selector).matches() && isValidDate(selector)) {
            return archives()
                    .filter(archive -> archive.date().equals(selector))
                    .max(Comparator.comparingInt(ArchiveLog::index))
                    .map(archive -> new LogSelection(archive.selector(), archive.path()));
        }

        return Optional.empty();
    }

    /**
     * Compatibility helper for existing callers. New request handling should
     * use {@link #resolveLogSelection(String)} so an invalid archive selector
     * cannot silently switch to the current log.
     */
    public Path getLogPathForDate(String selector) {
        try {
            return resolveLogSelection(selector)
                    .map(LogSelection::path)
                    .orElseGet(this::getLogPath);
        } catch (java.io.IOException ignored) {
            return getLogPath();
        }
    }

    public List<String> getAvailableDates() throws java.io.IOException {
        return archives()
                .sorted(Comparator.comparing(ArchiveLog::date).reversed()
                        .thenComparing(Comparator.comparingInt(ArchiveLog::index).reversed()))
                .map(ArchiveLog::selector)
                .toList();
    }

    Path getLogDirectory() {
        Path parent = getLogPath().getParent();
        return parent == null ? Path.of(".") : parent;
    }

    private Stream<ArchiveLog> archives() throws java.io.IOException {
        Path directory = getLogDirectory();
        if (!Files.isDirectory(directory)) {
            return Stream.empty();
        }

        try (Stream<Path> entries = Files.list(directory)) {
            return entries
                    .filter(LogPathResolver::isRegularLogFile)
                    .map(this::parseArchive)
                    .flatMap(Optional::stream)
                    .toList()
                    .stream();
        }
    }

    private Optional<ArchiveLog> parseArchive(Path path) {
        String name = path.getFileName().toString();
        Matcher matcher = ROTATED_LOG_NAME.matcher(name);
        if (!matcher.matches() || !isValidDate(matcher.group(1))) {
            return Optional.empty();
        }
        return Optional.of(new ArchiveLog(
                matcher.group(1),
                Integer.parseInt(matcher.group(2)),
                matcher.group(1) + "." + matcher.group(2),
                path
        ));
    }

    private Path archivePath(String selector) {
        return getLogDirectory().resolve("app." + selector + ".log");
    }

    private static boolean isRegularLogFile(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean isValidDate(String value) {
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    public record LogSelection(String selector, Path path) {
    }

    private record ArchiveLog(String date, int index, String selector, Path path) {
    }
}
