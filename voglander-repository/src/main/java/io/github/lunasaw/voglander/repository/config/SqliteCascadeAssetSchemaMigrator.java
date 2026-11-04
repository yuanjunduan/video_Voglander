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
 * SQLite 级联「本地资产层」schema 迁移：设备与通道的本地启停、通道的组织归属（幂等）。
 *
 * <p><b>为什么设备和通道要自己的 {@code enabled}</b>：1.10.1 起组织有本地启停（{@code enabled}），
 * 但设备与通道只有在线状态 {@code status}。两者语义完全不同——{@code status=0} 是"掉线"，
 * 它照样该出现在上级目录里（只是 {@code Status} 字段报 OFF）；而"不想让它上报了"是业务决定，
 * 必须能和掉线区分开。没有本地启停，这个动作只能落到每个平台各自的发布行上，
 * 于是"本地下线一个通道"变成"逐个平台关一遍"，正是发布层要消灭的按平台重复。
 *
 * <p><b>为什么通道要有自己的 {@code org_id}</b>：设备归属组织后，通道默认跟随设备即可满足多数场景，
 * 但 GB28181 的业务分组/虚拟组织允许把摄像机直接挂在组织结构上而不经过设备层。通道自带归属
 * 才能表达"设备在 A 组织、它的某一路通道归 B 业务分组"，也让通道能在不同上级挂不同父节点时有本地落点。
 * 为空表示跟随设备归属，为空与"未归组织"是同一件事。
 *
 * <p><b>为什么单独一支迁移器</b>：1.10.1 的迁移器（{@link SqliteCascadeSchemaMigrator}）已在大量库上
 * 登记过迁移键，再往里加列会导致那些库永远补不上新列——迁移键是"跑过就跳过"。一次演进一个 key，
 * 本次演进只做三列 + 一个索引，一个事务。
 *
 * <p>幂等策略与 {@link SqliteCascadeChannelMetaSchemaMigrator} 一致：加列前查 {@code pragma_table_info}
 * （SQLite 的 {@code ADD COLUMN} 不支持 {@code IF NOT EXISTS}），成功后登记 {@code schema_migration_history}。
 * 建表脚本的权威源仍是 {@code sql/voglander-sqlite.sql}；非 SQLite 数据源直接跳过。
 *
 * @author luna
 */
@Slf4j
@Component
public class SqliteCascadeAssetSchemaMigrator {

    /** 迁移键：只用于人工排查"这个库升级到哪一步了" */
    public static final String MIGRATION_KEY = "cascade-local-asset-device-channel";

    private static final String DEVICE_TABLE  = "tb_device";
    private static final String CHANNEL_TABLE = "tb_device_channel";

    /** 列定义：{表, 列, 定义}；顺序即加列顺序 */
    private static final String[][] COLUMNS = {
        {DEVICE_TABLE, "enabled", "INTEGER NOT NULL DEFAULT 1"},
        {CHANNEL_TABLE, "org_id", "INTEGER DEFAULT NULL"},
        {CHANNEL_TABLE, "enabled", "INTEGER NOT NULL DEFAULT 1"}};

    @Autowired(required = false)
    private DataSource dataSource;

    void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 执行迁移。非 SQLite 或数据源缺失时静默跳过。
     *
     * @throws IllegalStateException 迁移或校验失败（上抛阻断启动，不留"列缺失"的隐性坏库）
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
                if (!tableExists(connection, DEVICE_TABLE) || !tableExists(connection, CHANNEL_TABLE)) {
                    log.debug("SQLite 尚无 {} / {}，跳过本地资产迁移", DEVICE_TABLE, CHANNEL_TABLE);
                    return;
                }
                for (String[] column : COLUMNS) {
                    addColumnIfMissing(connection, column[0], column[1], column[2]);
                }
                /* 按组织统计通道数、删除组织前校验占用都走这条索引；
                   缺了也不报错，只是退化成全表扫，这里尽力建 */
                execute(connection, "CREATE INDEX IF NOT EXISTS idx_channel_org"
                    + " ON tb_device_channel (org_id)");
                verify(connection);
                recordApplied(connection);
                connection.commit();
            } catch (Exception e) {
                rollbackQuietly(connection, e);
                throw new IllegalStateException("SQLite 级联本地资产 schema 迁移失败", e);
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 级联本地资产 schema 迁移失败", e);
        }
    }

    /** 校验：三列都必须就位、索引必须建成，否则宁可启动失败也不带病运行。 */
    private void verify(Connection connection) throws SQLException {
        for (String[] column : COLUMNS) {
            requireColumn(connection, column[0], column[1]);
        }
        requireIndex(connection, "idx_channel_org");
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

    private void requireColumn(Connection connection, String table, String column) throws SQLException {
        if (!columnExists(connection, table, column)) {
            throw new IllegalStateException("SQLite 表 " + table + " 缺少列 " + column);
        }
    }

    private void requireIndex(Connection connection, String index) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type='index' AND name=?")) {
            ps.setString(1, index);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("SQLite 缺少索引 " + index);
                }
            }
        }
    }

    private void addColumnIfMissing(Connection connection, String table, String column, String definition)
        throws SQLException {
        if (columnExists(connection, table, column)) {
            return;
        }
        execute(connection, "ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        log.info("SQLite 迁移补列: {}.{}", table, column);
    }

    private boolean columnExists(Connection connection, String table, String column) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT 1 FROM pragma_table_info(?) WHERE name=?")) {
            ps.setString(1, table);
            ps.setString(2, column);
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
