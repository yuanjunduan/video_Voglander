package io.github.lunasaw.voglander.repository.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.annotation.DbType;

import lombok.extern.slf4j.Slf4j;

/**
 * SQLite 级联「通道老表退场」：{@code DROP TABLE tb_cascade_channel}。
 *
 * <p><b>为什么单独一支迁移</b>：数据搬迁（{@link SqliteCascadeChannelPublishMigrator}）与删表必须
 * 分两次、两个迁移键。合在一起的话，一旦搬迁成功而删表失败，事务回滚会把数据也一起退回去；
 * 分开之后老表哪怕留在库里也只是一张死表，不影响任何读写。
 *
 * <p><b>删表前的守护</b>：老表还有行、而「进入发布层」的迁移键没登记，说明数据还没搬完——
 * 此时删表就是静默丢配置。这种情况宁可阻断启动交给人工处理，也不猜。
 *
 * <p>必须在 {@link SqliteCascadeChannelPublishMigrator} 之后调用。幂等靠
 * {@code schema_migration_history}，非 SQLite 数据源直接跳过（MySQL/PostgreSQL 由 DBA 执行
 * {@code sql/migration/*}{@code /1.10.2-cascade-channel-drop.sql}）。
 *
 * @author luna
 */
@Slf4j
@Component
public class SqliteCascadeChannelDropMigrator {

    /** 迁移键：只用于人工排查"这个库升级到哪一步了" */
    public static final String MIGRATION_KEY = "cascade-channel-drop";

    private static final String CHANNEL_TABLE = "tb_cascade_channel";
    private static final String PUBLISH_TABLE = "tb_cascade_publish";

    @Autowired(required = false)
    private DataSource dataSource;

    void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 执行退场。非 SQLite 或数据源缺失时静默跳过。
     *
     * @throws IllegalStateException 删表失败，或老表尚有数据而数据迁移未登记（上抛阻断启动）
     */
    public void migrate() {
        if (dataSource == null || DatabaseTypeDetector.detectDbType(dataSource) != DbType.SQLITE) {
            return;
        }
        ensureHistoryTable();
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (applied(connection, MIGRATION_KEY)) {
                    log.debug("SQLite 迁移 {} 已登记，跳过通道老表退场", MIGRATION_KEY);
                    return;
                }
                if (!tableExists(connection, CHANNEL_TABLE)) {
                    /* 新库建表脚本已不再建这张表，直接登记即可，不必每次启动都查一遍 */
                    recordApplied(connection);
                    connection.commit();
                    return;
                }
                guardPublishMigrated(connection);
                execute(connection, "DROP TABLE IF EXISTS " + CHANNEL_TABLE);
                recordApplied(connection);
                connection.commit();
                log.info("SQLite 迁移：{} 已退场，通道目录的唯一来源是 {}", CHANNEL_TABLE, PUBLISH_TABLE);
            } catch (Exception e) {
                rollbackQuietly(connection, e);
                /* 护栏拒绝直接往外抛：包成「退场失败」会让现场以为是 SQL 出错而去查数据库 */
                if (e instanceof IllegalStateException) {
                    throw (IllegalStateException) e;
                }
                throw new IllegalStateException("SQLite 级联通道老表退场失败", e);
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 级联通道老表退场失败", e);
        }
    }

    /**
     * 删表守护：老表还有行但数据迁移没登记，说明配置还没搬进发布层，删掉就再也找不回来。
     *
     * <p>空表放行——没有任何配置可丢，迁移器跳过的正是这种库。
     */
    private void guardPublishMigrated(Connection connection) throws SQLException {
        if (applied(connection, SqliteCascadeChannelPublishMigrator.MIGRATION_KEY)) {
            return;
        }
        if (countRows(connection, CHANNEL_TABLE) == 0) {
            return;
        }
        throw new IllegalStateException("老表 " + CHANNEL_TABLE + " 尚有数据而迁移键 "
            + SqliteCascadeChannelPublishMigrator.MIGRATION_KEY + " 未登记，拒绝删表以免丢失上报配置");
    }

    // ------------------------------------------------------------------ 基础

    private long countRows(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
            ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private boolean applied(Connection connection, String key) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT 1 FROM schema_migration_history WHERE migration_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void ensureHistoryTable() {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS schema_migration_history ("
                + "migration_key VARCHAR(128) PRIMARY KEY, applied_at DATETIME NOT NULL)");
        } catch (SQLException e) {
            throw new IllegalStateException("无法初始化 SQLite 迁移历史表", e);
        }
    }

    private void recordApplied(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "INSERT OR IGNORE INTO schema_migration_history(migration_key, applied_at) VALUES(?, CURRENT_TIMESTAMP)")) {
            ps.setString(1, MIGRATION_KEY);
            ps.executeUpdate();
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void rollbackQuietly(Connection connection, Exception cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackError) {
            cause.addSuppressed(rollbackError);
        }
    }
}
