package com.hunt.otziv.config.metrics;

import java.lang.reflect.*;
import java.sql.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** Records execution counts/time only; SQL text, parameters and results are never retained. */
@Configuration(proxyBeanMethods = false)
public class SqlTimingConfiguration {
    @Bean static BeanPostProcessor sqlTimingDataSource() {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                if (!(bean instanceof DataSource source)) return bean;
                return new DelegatingDataSource(source) {
                    @Override public Connection getConnection() throws SQLException {
                        long started = System.nanoTime();
                        try { return wrap(super.getConnection()); }
                        finally { PerformanceMetrics.recordConnection(System.nanoTime() - started); }
                    }
                    @Override public Connection getConnection(String user, String password) throws SQLException {
                        long started = System.nanoTime();
                        try { return wrap(super.getConnection(user, password)); }
                        finally { PerformanceMetrics.recordConnection(System.nanoTime() - started); }
                    }
                };
            }
        };
    }
    static Connection wrap(Connection connection) {
        // Background jobs do not need proxies; scopes begin before obtaining connections.
        if (!PerformanceMetrics.collectingSql()) return connection;
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    Object value = invoke(method, connection, args);
                    if (value instanceof Statement statement) return wrapStatement(statement);
                    return value;
                });
    }
    static Statement wrapStatement(Statement statement) {
        Class<?> type = statement instanceof CallableStatement ? CallableStatement.class
                : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (!method.getName().startsWith("execute")) {
                        Object result = invoke(method, statement, args);
                        return result instanceof ResultSet rows ? wrapRows(rows) : result;
                    }
                    long started = System.nanoTime();
                    try {
                        Object result = invoke(method, statement, args);
                        return result instanceof ResultSet rows ? wrapRows(rows) : result;
                    }
                    finally { PerformanceMetrics.recordSql(System.nanoTime() - started); }
                });
    }
    static ResultSet wrapRows(ResultSet rows) {
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("next")) return invoke(method, rows, args);
                    long started = System.nanoTime();
                    boolean found = false;
                    try { found = (boolean) invoke(method, rows, args); return found; }
                    finally { PerformanceMetrics.recordFetch(System.nanoTime() - started, found); }
                });
    }
    private static Object invoke(Method method, Object target, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
