package com.hunt.otziv.logs.controller;

import com.hunt.otziv.logs.conf.LogPathResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 В результате:
 /logs/clear/all — очищает все основные логи
 access.log — хранит входящие HTTP-запросы
 debug.log, error.log, app.log — по уровню
 **/

@RestController
@RequestMapping("/logs")
public class LogController {

    private final Path logPath;
    private final LogPathResolver resolver;


    public LogController(LogPathResolver resolver) {
        this.resolver = resolver;
        this.logPath = resolver.getLogPath();
    }

    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> viewLogs(
            @RequestParam(required = false) String level,
            @RequestParam(required = false, defaultValue = "1000") int limit,
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "false") boolean autoRefresh,
            @RequestParam(required = false) String date,
            @RequestParam(required = false, defaultValue = "dark") String theme
    ) throws IOException {

        String today = LocalDate.now().toString();
        List<String> availableDates = Files.list(Path.of("/app/logs"))
                .map(Path::getFileName)
                .map(Path::toString)
                .filter(name -> name.matches("app\\.\\d{4}-\\d{2}-\\d{2}\\.log"))
                .map(name -> name.substring(4, 14))
                .sorted(Comparator.reverseOrder())
                .toList();

        if ((date == null || date.isBlank()) && availableDates.contains(today)) {
            date = today;
        }

        Path selectedPath = resolver.getLogPathForDate(date);



        if (!Files.exists(selectedPath)) {
            return ResponseEntity.ok("<p>Файл логов не найден: " + selectedPath + "</p>");
        }

        List<String> lines = Files.readAllLines(selectedPath);

        if (level != null) {
            String upperLevel = level.toUpperCase();
            lines = lines.stream().filter(line -> line.contains(upperLevel)).collect(Collectors.toList());
        }

        if (search != null && !search.isBlank()) {
            lines = lines.stream().filter(line -> line.toLowerCase().contains(search.toLowerCase())).collect(Collectors.toList());
        }

        if (limit > 0 && lines.size() > limit) {
            lines = lines.subList(lines.size() - limit, lines.size());
        }

        StringBuilder html = new StringBuilder();
        html.append(String.format("""
<html>
<head>
    <meta charset="UTF-8">
    <title>Логи</title>
    <style id="theme-style"></style>
</head>
<body>
<script>
    function setTheme(mode) {
        document.getElementById('theme-style').innerHTML = mode === 'light' ? `
            body { background: #fff; color: #000; font-family: monospace; padding: 20px; }
            pre { background: #eee; color: #000; padding: 10px; border: 1px solid #ccc; white-space: pre-wrap; }
            input, select, button { background: #eee; color: #000; }
        ` : `
            body { background: #1e1e1e; color: #ddd; font-family: monospace; padding: 20px; }
            pre { background: #000; color: #ccc; padding: 10px; border: 1px solid #444; white-space: pre-wrap; }
            input, select, button { background: #2e2e2e; color: #eee; }
        `;
        localStorage.setItem("theme", mode);
    }

    function toggleTheme() {
        const current = localStorage.getItem("theme") || 'dark';
        const next = current === 'dark' ? 'light' : 'dark';
        setTheme(next);
    }

    const savedTheme = localStorage.getItem("theme") || "%s";
    setTheme(savedTheme);
</script>
<form method='get' style='margin-bottom:10px'>
    Дата: <select name='date'><option value=''>Текущий</option>
""", theme));

        for (String d : availableDates) {
            html.append("<option value='").append(d).append("'");
            if (d.equals(date)) html.append(" selected");
            html.append(">").append(d).append("</option>");
        }

        html.append("</select> Уровень: <select name='level'>")
                .append("<option value=''>Все</option>")
                .append("<option ").append("ERROR".equalsIgnoreCase(level) ? "selected" : "").append(">ERROR</option>")
                .append("<option ").append("INFO".equalsIgnoreCase(level) ? "selected" : "").append(">INFO</option>")
                .append("<option ").append("DEBUG".equalsIgnoreCase(level) ? "selected" : "").append(">DEBUG</option>")
                .append("</select> Строк: <input type='number' name='limit' value='").append(limit).append("'/> ")
                .append("Поиск: <input type='text' name='search' value='").append(search != null ? search : "").append("'/> ")
                .append("<label><input type='checkbox' id='autoRefresh' name='autoRefresh' value='true' ")
                .append(autoRefresh ? "checked" : "").append("> Автообновление</label> ")
                .append("<button type='submit'>Показать</button> ")
                .append("<button type='button' onclick='location.reload()'>↻ Вернуть</button> ")
                .append("<button type='button' onclick='clearLogView()'>🧹 Очистить</button> ")
                .append("<button type='button' onclick='toggleTheme()'>🌗 Тема</button> ")
                .append("Интервал: <select id='intervalSelector'>")
                .append("<option value='3000'>3с</option>")
                .append("<option value='5000' selected>5с</option>")
                .append("<option value='10000'>10с</option>")
                .append("</select>")
                .append("</form>");

        html.append("<div>Показано: ").append(lines.size()).append(" строк из файла: <b>")
                .append(selectedPath.getFileName()).append("</b></div><br>");
        html.append("<pre id='log'>");
        for (String line : lines) {
            html.append(escapeHtml(line)).append("\n");
        }
        html.append("</pre>");

        html.append("""
<script>
function clearLogView() {
    const pre = document.getElementById("log");
    if (pre) pre.innerText = "";
    localStorage.setItem("logOffset", "0");

    // 💥 Останавливаем автообновление
    if (intervalId) {
        clearInterval(intervalId);
        intervalId = null;
    }
}
let fetchInterval = 5000;
let intervalId = null;
function updateInterval() {
    if (intervalId) clearInterval(intervalId);
    fetchInterval = parseInt(document.getElementById("intervalSelector").value);
    intervalId = setInterval(fetchNewLogs, fetchInterval);
}
async function fetchNewLogs() {
    const offset = localStorage.getItem("logOffset") || "0";
    const res = await fetch("/logs/tail?offset=" + offset);
    const data = await res.json();
    const pre = document.getElementById("log");
    if (data.lines.length > 0) {
        data.lines.forEach(line => pre.innerText += line + "\\n");
        pre.scrollTop = pre.scrollHeight;
    }
    localStorage.setItem("logOffset", data.newOffset);
}
window.onload = function() {
    if (document.getElementById("autoRefresh").checked) {
        document.getElementById("intervalSelector").addEventListener("change", updateInterval);
        updateInterval();
    }
};
</script>
</body>
</html>
""");

        return ResponseEntity.ok(html.toString());
    }


    @GetMapping("/tail")
    public ResponseEntity<Map<String, Object>> tailLog(@RequestParam(defaultValue = "0") long offset) throws IOException {
        Map<String, Object> result = new HashMap<>();
        List<String> lines = new ArrayList<>();

        try (RandomAccessFile file = new RandomAccessFile(logPath.toFile(), "r")) {
            file.seek(offset);
            String line;
            while ((line = file.readLine()) != null) {
                lines.add(line);
            }
            long newOffset = file.getFilePointer();
            result.put("lines", lines);
            result.put("newOffset", newOffset);
        }
        return ResponseEntity.ok(result);
    }

    private String escapeHtml(String line) {
        return line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @PostMapping("/clear")
    public ResponseEntity<String> clearLog() throws IOException {
        if (Files.exists(logPath)) Files.write(logPath, new byte[0], StandardOpenOption.TRUNCATE_EXISTING);
        return ResponseEntity.ok("Файл логов очищен.");
    }

    @PostMapping("/clear/all")
    public ResponseEntity<String> clearAllLogs() {
        String[] logFiles = {
                "/app/logs/app.log",
                "/app/logs/error.log",
                "/app/logs/debug.log"
        };

        int cleared = 0;

        for (String filePath : logFiles) {
            try {
                Path path = Path.of(filePath);
                if (Files.exists(path)) {
                    Files.write(path, new byte[0], StandardOpenOption.TRUNCATE_EXISTING);
                    cleared++;
                }
            } catch (IOException e) {
                return ResponseEntity.status(500).body("Ошибка при очистке " + filePath + ": " + e.getMessage());
            }
        }

        return ResponseEntity.ok("Очищено логов: " + cleared);
    }

    @GetMapping("/ui")
    public ResponseEntity<String> logsUI() {
        String html = """
        <!DOCTYPE html>
        <html lang="ru">
        <head>
            <meta charset="UTF-8">
            <title>Live Logs</title>
            <style>
                body { font-family: monospace; background: #111; color: #ccc; padding: 10px; }
                #log { white-space: pre-wrap; max-height: 80vh; overflow-y: auto; border: 1px solid #444; padding: 10px; background: #000; }
                .controls { margin-bottom: 10px; color: #ccc; }
                label { margin-right: 15px; }
                .line-error { color: #f55; }
                .line-info { color: #ccc; }
                .line-debug { color: #5af; }
            </style>
        </head>
        <body>
        <h2 style="color:#0f0">Live Логи (WebSocket)</h2>
        <div class="controls">
            <label><input type="checkbox" class="level" value="ERROR" checked> ERROR</label>
            <label><input type="checkbox" class="level" value="INFO" checked> INFO</label>
            <label><input type="checkbox" class="level" value="DEBUG" checked> DEBUG</label>
            <button onclick="clearLog()">🧼 Очистить</button>
            <button onclick="togglePause()" id="pauseBtn">⏸ Пауза</button>
            <button onclick="downloadLog()">💾 Скачать</button>
        </div>
        <div id="log"></div>

        <script>
            const logDiv = document.getElementById("log");
            const pauseBtn = document.getElementById("pauseBtn");
            const levels = new Set(["ERROR", "INFO", "DEBUG"]);
            let paused = false;

            document.querySelectorAll('.level').forEach(cb => {
                cb.addEventListener('change', () => {
                    cb.checked ? levels.add(cb.value) : levels.delete(cb.value);
                });
            });

            function clearLog() {
                logDiv.innerHTML = "";
            }

            function togglePause() {
                paused = !paused;
                pauseBtn.innerText = paused ? "▶️ Пуск" : "⏸ Пауза";
            }

            function downloadLog() {
                const a = document.createElement("a");
                a.href = "/logs/download";
                a.download = "app.log";
                a.click();
            }

            const ws = new WebSocket("ws://" + window.location.host + "/ws/logs");

            ws.onmessage = function(event) {
                if (paused) return;
                const line = event.data;
                if ([...levels].some(level => line.includes(level))) {
                    const div = document.createElement("div");
                    if (line.includes("ERROR")) div.className = "line-error";
                    else if (line.includes("DEBUG")) div.className = "line-debug";
                    else div.className = "line-info";
                    div.textContent = line;
                    logDiv.appendChild(div);
                    logDiv.scrollTop = logDiv.scrollHeight;
                }
            };
        </script>
        </body>
        </html>
    """;

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }


}
