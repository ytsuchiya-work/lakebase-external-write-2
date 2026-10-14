package com.example.lakebase;

import com.databricks.sdk.WorkspaceClient;
import com.databricks.sdk.core.DatabricksConfig;
import com.databricks.sdk.service.postgres.DatabaseCredential;
import com.databricks.sdk.service.postgres.GenerateDatabaseCredentialRequest;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * 方式1: Databricks SDK for Java + JDBC（HikariCP）。
 * SDK の generateDatabaseCredential() でデータベース資格情報（60 分有効）を取り、JDBC のパスワードに使う。
 *
 * 実行: mvn -q -s settings.xml compile exec:java -Dexec.mainClass=com.example.lakebase.M1Sdk
 */
public class M1Sdk {

    public static void main(String[] args) throws Exception {
        WorkspaceClient w = new WorkspaceClient(new DatabricksConfig()
                .setHost(Env.get("DATABRICKS_HOST"))
                .setClientId(Env.get("DATABRICKS_CLIENT_ID"))
                .setClientSecret(Env.get("DATABRICKS_CLIENT_SECRET")));
        String endpointName = Env.get("ENDPOINT_NAME");

        String jdbcUrl = "jdbc:postgresql://" + Env.get("PGHOST") + ":" + Env.get("PGPORT", "5432")
                + "/" + Env.get("PGDATABASE") + "?sslmode=require";

        // 新しい物理接続を作るたびに資格情報を発行し直す
        TokenDataSource tokenDataSource = new TokenDataSource(jdbcUrl, Env.get("PGUSER"), () -> {
            DatabaseCredential cred = w.postgres().generateDatabaseCredential(
                    new GenerateDatabaseCredentialRequest().setEndpoint(endpointName));
            return cred.getToken();
        });

        HikariConfig config = new HikariConfig();
        config.setDataSource(tokenDataSource);
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(1);
        config.setMaxLifetime(45 * 60 * 1000L); // 60 分のトークン期限より前に接続を作り直す

        try (HikariDataSource pool = new HikariDataSource(config)) {
            JdbcCrud.run(pool, Env.get("PGSCHEMA", "write_test") + ".sdk_java_items");
        }
    }
}
