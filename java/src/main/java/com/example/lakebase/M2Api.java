package com.example.lakebase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 方式2: REST API で資格情報を取得 + JDBC（HikariCP）。SDK は使わない。
 *   (1) POST {host}/oidc/v1/token                SP シークレット -> ワークスペース OAuth トークン（60 分）
 *   (2) POST {host}/api/2.0/postgres/credentials  OAuth トークン -> データベース資格情報（60 分）
 * どちらも期限の 5 分前までキャッシュして使い回す。
 *
 * 実行: mvn -q -s settings.xml compile exec:java -Dexec.mainClass=com.example.lakebase.M2Api
 */
public class M2Api {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

    /** 期限の REFRESH_MARGIN 前まで同じトークンを返す。 */
    static final class CachedToken {
        record Token(String value, Instant expiresAt) {}

        private final Supplier<Token> fetcher;
        private Token current;

        CachedToken(Supplier<Token> fetcher) { this.fetcher = fetcher; }

        synchronized String get() {
            if (current == null || Instant.now().isAfter(current.expiresAt().minus(REFRESH_MARGIN))) {
                current = fetcher.get();
            }
            return current.value();
        }
    }

    static JsonNode post(HttpRequest.Builder req) {
        try {
            HttpResponse<String> res = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw new IllegalStateException(res.request().uri() + " -> " + res.statusCode() + " " + res.body());
            }
            return JSON.readTree(res.body());
        } catch (Exception e) {
            throw (e instanceof RuntimeException re) ? re : new IllegalStateException(e);
        }
    }

    // (1) client_credentials フロー（Basic 認証で client_id:client_secret を送る）
    static CachedToken.Token fetchWorkspaceToken() {
        String basic = Base64.getEncoder().encodeToString(
                (Env.get("DATABRICKS_CLIENT_ID") + ":" + Env.get("DATABRICKS_CLIENT_SECRET"))
                        .getBytes(StandardCharsets.UTF_8));
        String form = "grant_type=client_credentials&scope=" + URLEncoder.encode("all-apis", StandardCharsets.UTF_8);
        JsonNode body = post(HttpRequest.newBuilder(URI.create(Env.get("DATABRICKS_HOST") + "/oidc/v1/token"))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)));
        return new CachedToken.Token(body.get("access_token").asText(),
                Instant.now().plusSeconds(body.get("expires_in").asLong()));
    }

    static final CachedToken WORKSPACE_TOKEN = new CachedToken(M2Api::fetchWorkspaceToken);

    // (2) ワークスペース OAuth トークンをデータベース資格情報に交換する
    static CachedToken.Token fetchDatabaseCredential() {
        try {
            String json = JSON.writeValueAsString(Map.of("endpoint", Env.get("ENDPOINT_NAME")));
            JsonNode body = post(HttpRequest.newBuilder(
                            URI.create(Env.get("DATABRICKS_HOST") + "/api/2.0/postgres/credentials"))
                    .header("Authorization", "Bearer " + WORKSPACE_TOKEN.get())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)));
            return new CachedToken.Token(body.get("token").asText(), Instant.parse(body.get("expire_time").asText()));
        } catch (Exception e) {
            throw (e instanceof RuntimeException re) ? re : new IllegalStateException(e);
        }
    }

    static final CachedToken DATABASE_CREDENTIAL = new CachedToken(M2Api::fetchDatabaseCredential);

    public static void main(String[] args) throws Exception {
        String jdbcUrl = "jdbc:postgresql://" + Env.get("PGHOST") + ":" + Env.get("PGPORT", "5432")
                + "/" + Env.get("PGDATABASE") + "?sslmode=require";

        HikariConfig config = new HikariConfig();
        config.setDataSource(new TokenDataSource(jdbcUrl, Env.get("PGUSER"), DATABASE_CREDENTIAL::get));
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(1);
        config.setMaxLifetime(45 * 60 * 1000L);

        try (HikariDataSource pool = new HikariDataSource(config)) {
            JdbcCrud.run(pool, Env.get("PGSCHEMA", "write_test") + ".api_java_items");
        }
    }
}
