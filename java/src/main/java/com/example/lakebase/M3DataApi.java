package com.example.lakebase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 方式3: Lakebase Data API（PostgREST 互換の HTTP API）。JDBC は使わない。
 * Bearer に渡すのはワークスペース OAuth トークン（データベース資格情報ではない）。
 * Data API は DDL を受け付けないため、テーブル作成は事前に用意した関数を RPC で呼び出して行う。
 *
 * 実行: mvn -q -s settings.xml compile exec:java -Dexec.mainClass=com.example.lakebase.M3DataApi
 */
public class M3DataApi {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TABLE = "dataapi_java_items";

    private static String base;   // {REST_ENDPOINT}/{schema}
    private static String token;

    static String getWorkspaceToken() throws Exception {
        String basic = Base64.getEncoder().encodeToString(
                (Env.get("DATABRICKS_CLIENT_ID") + ":" + Env.get("DATABRICKS_CLIENT_SECRET"))
                        .getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create(Env.get("DATABRICKS_HOST") + "/oidc/v1/token"))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&scope=all-apis"))
                .build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException("token -> " + res.statusCode() + " " + res.body());
        return JSON.readTree(res.body()).get("access_token").asText();
    }

    /** path にはテーブル名や rpc/<関数名>、query にはフィルター（例 name=eq.banana）を渡す。 */
    static HttpResponse<String> send(String method, String path, String query, Object body) throws Exception {
        String url = base + "/" + path + (query == null ? "" : "?" + query);
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Prefer", "return=representation")
                .method(method, publisher)
                .build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    static JsonNode call(String method, String path, String query, Object body) throws Exception {
        HttpResponse<String> res = send(method, path, query, body);
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException(method + " " + path + " -> " + res.statusCode() + " " + res.body());
        }
        return JSON.readTree(res.body().isEmpty() ? "null" : res.body());
    }

    /**
     * 新しいテーブルは Data API のスキーマキャッシュに載るまで 404（PGRST205）になるので待つ。
     * キャッシュは自動では更新されず NOTIFY pgrst も効かないため、初回は UI での更新操作が要る。
     */
    static void waitUntilExposed(String table, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        boolean hinted = false;
        while (true) {
            HttpResponse<String> res = send("GET", table, "limit=1", null);
            if (res.statusCode() == 200) return;
            if (!hinted) {
                System.out.println("  スキーマキャッシュ未反映。Lakebase UI の Data API ページで"
                        + "「Refresh schema cache」を押してください（待機中）");
                hinted = true;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException(table + " が Data API に現れない: " + res.statusCode() + " " + res.body());
            }
            Thread.sleep(3000);
        }
    }

    static void show(String label) throws Exception {
        System.out.println("--- " + label);
        for (JsonNode row : call("GET", TABLE, "select=id,name,quantity,updated_at&order=id.asc", null)) {
            System.out.println("    " + row);
        }
    }

    static String eq(String column, String value) {
        return column + "=eq." + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        base = Env.get("REST_ENDPOINT").replaceAll("/+$", "") + "/" + Env.get("PGSCHEMA", "write_test");
        token = getWorkspaceToken();

        // CREATE TABLE: DDL 用関数を RPC で呼ぶ（POST {base}/rpc/<関数名>）
        System.out.println("created: " + call("POST", "rpc/dataapi_reset_table", null, Map.of("p_table", TABLE)).asText());
        Instant started = Instant.now();
        waitUntilExposed(TABLE, Duration.ofSeconds(300));
        System.out.printf("schema cache refreshed in %.1fs%n", Duration.between(started, Instant.now()).toMillis() / 1000.0);

        // INSERT: 配列で渡すと 1 リクエストで複数行
        JsonNode inserted = call("POST", TABLE, null, List.of(
                Map.of("name", "apple", "quantity", 10),
                Map.of("name", "banana", "quantity", 20),
                Map.of("name", "cherry", "quantity", 30)));
        System.out.println("inserted rows: " + inserted.size());
        show("after INSERT");

        // UPDATE: PATCH + フィルター（フィルターなしの PATCH は全行更新になるので必ず付ける）
        JsonNode updated = call("PATCH", TABLE, eq("name", "banana"),
                Map.of("quantity", 99, "updated_at", Instant.now().toString()));
        System.out.println("updated rows: " + updated.size());
        show("after UPDATE");

        // DELETE: DELETE + フィルター
        JsonNode deleted = call("DELETE", TABLE, eq("name", "cherry"), null);
        System.out.println("deleted rows: " + deleted.size());
        show("after DELETE");
    }
}
