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
 * SQLite 级联「平台画像」schema 迁移：目录是否带设备层（幂等）。
 *
 * <p><b>为什么这是平台维度而不是本地维度</b>：组织与通道的层级是本地结构，只有一份；
 * 但「通道直挂组织」与「域/组织/设备/通道」是两种都合规的目录形态，取决于上级怎么建树
 * （海康 iSecure 能接受设备节点，部分平台把每个通道当独立节点）。把它挂在本地组织树上，
 * 就等于为一棵树分叉；挂在平台配置上，同一套本地资产就能组装成两种目录。
 *
 * <p><b>为什么单独一支迁移器</b>：迁移键是「跑过就跳过」，1.10.1 的级联迁移器已在大量库上
 * 登记过自己的键，往里加列会导致那些库永远补不上。一次演进一个 key，本次只有一列。
 *
 * <p>幂等策略与 {@link SqliteCascadeAssetSchemaMigrator} 一致：加列前查 {@code pragma_table_info}
 * （SQLite 的 {@code ADD COLUMN} 不支持 {@code IF NOT EXISTS}），成功后登记
 * {@code schema_migration_history}。建表脚本的权威源仍是 {@code sql/voglander-sqlite.sql}；
 * 非 SQLite 数据源直接跳过。
 *
 * @author luna
 */
@Slf4j
@Component
public class SqliteCascadePlatformLayerMigrator {

    /** 迁移键：只用于人工排查「这个库升级到哪一步了」 */
    public static final String MIGRATION_KEY = "cascade-platform-device-layer";

    private static final String PLATFORM_TABLE = "tb_cascade_platform";
    private static final String COLUMN         = "catalog_with_device_layer";

    /**
     * 存量平台一律补成 0（虚拟组织）：设备层会让上级目录整体多出一层，
     * 升级不能替用户改结构，只能等他在平台配置里显式打开。
     */
    private static final String COLUMN_DEFINITION = "INTEGER NOT NULL DEFAULT 0";

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
                    log.debug("SQLite 尚无 {}，跳过平台画像迁移", PLATFORM_TABLE);
                    return;
                }
                addColumnIfMissing(connection);
                requireColumn(connection);
                recordApplied(connection);
                connection.commit();
            } catch (Exception e) {
                rollbackQuietly(connection, e);
                throw new IllegalStateException("SQLite 级联平台画像 schema 迁移失败", e);
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 级联平台画像 schema 迁移失败", e);
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
