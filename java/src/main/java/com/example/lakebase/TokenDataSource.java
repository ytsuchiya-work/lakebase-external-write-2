package com.example.lakebase;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.function.Supplier;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * getConnection() のたびに passwordSupplier からパスワード（データベース資格情報）を取り直す DataSource。
 * HikariCP に渡すと、プールが新しい物理接続を作るときだけ呼ばれる。
 */
final class TokenDataSource implements DataSource {
    private final String jdbcUrl;
    private final String user;
    private final Supplier<String> passwordSupplier;
    private int loginTimeout;
    private PrintWriter logWriter;

    TokenDataSource(String jdbcUrl, String user, Supplier<String> passwordSupplier) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.passwordSupplier = passwordSupplier;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, user, passwordSupplier.get());
    }

    @Override
    public Connection getConnection(String username, String password) {
        throw new UnsupportedOperationException();
    }

    @Override public PrintWriter getLogWriter() { return logWriter; }
    @Override public void setLogWriter(PrintWriter out) { this.logWriter = out; }
    @Override public void setLoginTimeout(int seconds) { this.loginTimeout = seconds; }
    @Override public int getLoginTimeout() { return loginTimeout; }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
    @Override public boolean isWrapperFor(Class<?> iface) { return false; }
}
