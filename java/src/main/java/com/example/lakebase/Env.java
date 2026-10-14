package com.example.lakebase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** ../.env を読み込み、無ければ OS の環境変数を使う（OS 側が優先）。 */
final class Env {
    private static final Map<String, String> FILE = load(Path.of("..", ".env"));

    private Env() {}

    static String get(String key) {
        String v = System.getenv(key);
        if (v == null) v = FILE.get(key);
        if (v == null || v.isBlank()) throw new IllegalStateException(key + " is not set");
        return v;
    }

    static String get(String key, String defaultValue) {
        String v = System.getenv().getOrDefault(key, FILE.get(key));
        return (v == null || v.isBlank()) ? defaultValue : v;
    }

    private static Map<String, String> load(Path path) {
        Map<String, String> map = new HashMap<>();
        if (!Files.exists(path)) return map;
        try {
            for (String line : Files.readAllLines(path)) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) continue;
                int i = line.indexOf('=');
                map.put(line.substring(0, i).strip(), line.substring(i + 1).strip());
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to read " + path, e);
        }
        return map;
    }
}
