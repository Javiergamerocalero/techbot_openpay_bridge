package com.notariapaino.bridge.routes;

import com.notariapaino.bridge.config.AppConfig;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SDK log file access endpoints.
 *
 * <ul>
 *   <li>{@code GET /api/logs/files} — JSON listing of available daily log files.</li>
 *   <li>{@code GET /api/logs/{filename}} — download a specific file (streamed,
 *       so multi-MB logs don't load into memory).</li>
 * </ul>
 *
 * <p>The SDK writes its native log file at
 * {@code <rutaBase>/totalpos/logs/totalpos_sdk_java[_<afiliacion>]_<yyyy-MM-dd>.log}
 * (verified against the SDK's {@code Configuracion.setRutaBase} bytecode,
 * which appends an internal {@code /totalpos} segment, and against
 * {@code LogManager.updateLogFile} which appends {@code /logs/<file>}).
 *
 * <p>This controller resolves the directory once at construction and serves
 * files from it with strict path-traversal guards.
 */
public final class LogsController {

    private static final Logger log = LoggerFactory.getLogger(LogsController.class);

    /** Pattern of files the SDK creates (the date is captured for sorting/filtering). */
    private static final Pattern FILE_PATTERN = Pattern.compile("^(.+)_(\\d{4}-\\d{2}-\\d{2})\\.log$");

    private final File logsDir;

    public LogsController(AppConfig cfg) {
        // The SDK appends "/totalpos" to whatever rutaBase we pass (see SDK
        // Configuracion.setRutaBase bytecode — line 89-126 of the
        // decompiled class file), then LogManager appends "/logs".
        this.logsDir = Path.of(cfg.sdk.rutaBase, "totalpos", "logs").toFile();
        log.info("LogsController serving from {}", logsDir.getAbsolutePath());
    }

    public Handler list() {
        return new Handler() {
            @Override
            public void handle(@NotNull Context ctx) {
                List<Map<String, Object>> files = new ArrayList<>();
                if (logsDir.exists() && logsDir.isDirectory()) {
                    File[] children = logsDir.listFiles();
                    if (children != null) {
                        for (File f : children) {
                            if (!f.isFile() || !f.getName().endsWith(".log")) continue;
                            Matcher m = FILE_PATTERN.matcher(f.getName());
                            if (!m.matches()) continue;
                            Map<String, Object> info = new LinkedHashMap<>();
                            info.put("name", f.getName());
                            info.put("date", m.group(2));
                            info.put("sizeBytes", f.length());
                            info.put("lastModified", f.lastModified());
                            files.add(info);
                        }
                    }
                } else {
                    log.debug("logs dir does not exist yet: {}", logsDir.getAbsolutePath());
                }
                files.sort(Comparator.comparing(m -> (String) m.get("date")));

                Map<String, Object> body = new LinkedHashMap<>();
                body.put("success", true);
                body.put("basePath", logsDir.getAbsolutePath());
                body.put("count", files.size());
                body.put("files", files);
                ctx.status(200).json(body);
            }
        };
    }

    public Handler download() {
        return new Handler() {
            @Override
            public void handle(@NotNull Context ctx) {
                String name = ctx.pathParam("filename");
                // Path-traversal guards. The SDK's filenames are
                // `<prefix>_<date>.log` with no slashes; we refuse anything else.
                if (name == null || name.isBlank()
                        || name.contains("..")
                        || name.contains("/")
                        || name.contains("\\")) {
                    ctx.status(400).result("Invalid filename");
                    return;
                }
                File target = new File(logsDir, name);
                try {
                    File canonical = target.getCanonicalFile();
                    File baseCanonical = logsDir.getCanonicalFile();
                    if (!canonical.toPath().startsWith(baseCanonical.toPath())) {
                        ctx.status(400).result("Path escapes logs dir");
                        return;
                    }
                    if (!canonical.exists() || !canonical.isFile()) {
                        ctx.status(404).result("File not found: " + name);
                        return;
                    }
                    ctx.header("Content-Disposition", "attachment; filename=\"" + name + "\"");
                    ctx.contentType("text/plain; charset=utf-8");
                    ctx.header("Content-Length", String.valueOf(canonical.length()));
                    try (InputStream in = new FileInputStream(canonical)) {
                        ctx.result(in);  // Javalin streams the InputStream.
                    }
                    log.info("Served {} ({} bytes)", canonical.getName(), canonical.length());
                } catch (IOException e) {
                    log.error("download {} failed: {}", name, e.getMessage());
                    ctx.status(500).result("IO error: " + e.getMessage());
                }
            }
        };
    }
}
