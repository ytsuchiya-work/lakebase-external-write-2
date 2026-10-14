package com.example.lakebase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import javax.sql.DataSource;

/** 方式1・2 共通: JDBC で CREATE TABLE → INSERT → UPDATE → DELETE を実行する。 */
final class JdbcCrud {
    private JdbcCrud() {}

    static void run(DataSource pool, String table) throws SQLException {
        try (Connection conn = pool.getConnection()) {
            conn.setAutoCommit(false);

            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT current_user, current_database()")) {
                rs.next();
                System.out.println("connected as: " + rs.getString(1) + " / " + rs.getString(2));
            }

            // CREATE TABLE（再実行できるよう作り直す）
            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS " + table);
                st.execute("CREATE TABLE " + table + " ("
                        + " id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,"
                        + " name       TEXT        NOT NULL,"
                        + " quantity   INTEGER     NOT NULL,"
                        + " updated_at TIMESTAMPTZ NOT NULL DEFAULT now())");
            }
            conn.commit(); // ステップごとに commit する
            System.out.println("created: " + table);

            // INSERT（バッチ）
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + table + " (name, quantity) VALUES (?, ?)")) {
                String[] names = {"apple", "banana", "cherry"};
                int[] qty = {10, 20, 30};
                for (int i = 0; i < names.length; i++) {
                    ps.setString(1, names[i]);
                    ps.setInt(2, qty[i]);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
            show(conn, table, "after INSERT");

            // UPDATE
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE " + table + " SET quantity = ?, updated_at = now() WHERE name = ?")) {
                ps.setInt(1, 99);
                ps.setString(2, "banana");
                System.out.println("updated rows: " + ps.executeUpdate());
            }
            conn.commit();
            show(conn, table, "after UPDATE");

            // DELETE
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + table + " WHERE name = ?")) {
                ps.setString(1, "cherry");
                System.out.println("deleted rows: " + ps.executeUpdate());
            }
            conn.commit();
            show(conn, table, "after DELETE");
        }
    }

    private static void show(Connection conn, String table, String label) throws SQLException {
        System.out.println("--- " + label);
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, name, quantity, updated_at FROM " + table + " ORDER BY id")) {
            while (rs.next()) {
                System.out.printf("    (%d, %s, %d, %s)%n",
                        rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getObject(4, OffsetDateTime.class));
            }
        }
    }
}
