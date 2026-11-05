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
 * SQLite 级联平台「平台名称」补列迁移（幂等）。
 *
 * <p><b>为什么要有这个名字列</b>：列表里只有 20 位平台ID，多个上级平台并排时人认不出谁是谁。
 * 名称只用于本地辨识——GB28181 报文里用的始终是 platform_id，改名称不影响注册与上报。
 *
 * <p><b>为什么单独一支迁移器</b>：迁移键是「跑过就跳过」，{@code cascade-platform-device-layer}
 * 已在大量库上登记过，往里加列会导致那些库永远补不上。一次演进一个 key，本次只有一列。
 *
 * <p>幂等策略与 {@link SqliteCascadePlatformLayerMigrator} 一致：加列前查 {@code pragma_table_info}
 * （SQLite 的 {@code ADD COLUMN} 不支持 {@code IF NOT EXISTS}），成功后登记
 * {@code schema_migration_history}。建表脚本的权威源仍是 {@code sql/voglander-sqlite.sql}；
 * 非 SQLite 数据源直接跳过。
 *
 * @author luna
 */
@Slf4j
@Component
public class SqliteCascadePlatformNameMigrator {

    /** 迁移键：只用于人工排查「这个库升级到哪一步了」 */
    public static final String MIGRATION_KEY = "cascade-platform-name";

    private static final String PLATFORM_TABLE = "tb_cascade_platform";
    private static final String COLUMN         = "platform_name";

    /**
     * 存量平台一律补成空串（未命名）：升级不能替用户编名字，
     * 前端在空值时回落显示平台ID。
     */
    private static final String COLUMN_DEFINITION = "VARCHAR(128) NOT NULL DEFAULT ''";

    @Autowired(required = false)
    private DataSource dataSource;

    void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 执行迁移。非 SQLite 或数据源缺失时静默跳过。
     *
     * @throws IllegalStateException 迁移或校验失败（上抛阻断启动，不留「列缺失」的隐性坏库）
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
                /* 表不存在说明尚未建库：全量脚本负责，这里不建表 */
                if (!tableExists(connection, PLATFORM_TABLE)) {
                    log.debug("SQLite 尚无 {}，跳过平台名称迁移", PLATFORM_TABLE);
                    return;
                }
                addColumnIfMissing(connection);
                requireColumn(connection);
                recordApplied(connection);
                connection.commit();
            } catch (Exception e) {
                rollbackQuietly(connection, e);
                throw new IllegalStateException("SQLite 级联平台名称 schema 迁移失败", e);
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 级联平台名称 schema 迁移失败", e);
        }
    }

    private void addColumnIfMissing(Connection connection) throws SQLException {
        if (columnExists(connection)) {
            return;
        }
        execute(connection, "ALTER TABLE " + PLATFORM_TABLE + " ADD COLUMN " + COLUMN + " " + COLUMN_DEFINITION);
        log.info("SQLite 迁移补列: {}.{}", PLATFORM_TABLE, COLUMN);
    }

    private void requireColumn(Connection connection) throws SQLException {
        if (!columnExists(connection)) {
            throw new IllegalStateException("SQLite 表 " + PLATFORM_TABLE + " 缺少列 " + COLUMN);
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

    private boolean columnExists(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT 1 FROM pragma_table_info(?) WHERE name=?")) {
            ps.setString(1, PLATFORM_TABLE);
            ps.setString(2, COLUMN);
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
