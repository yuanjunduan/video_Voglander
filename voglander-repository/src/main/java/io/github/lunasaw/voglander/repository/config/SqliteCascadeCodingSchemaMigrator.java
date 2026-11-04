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
 * SQLite 级联「平台编码规则」schema 迁移（幂等，可重复执行）。
 *
 * <p><b>为什么需要它</b>：1.10.3 把编码规则提到平台维度——同一个本地组织/通道推给不同上级，
 * 前 14 位（省/市/县/行业/网络）可以完全不同。老库的 {@code tb_cascade_platform} 只有连接参数，
 * 不补这 8 列，平台配置一读写就 {@code no such column}。
 *
 * <p><b>为什么是平台维度</b>：组织表上的 {@code code_*} 是「这套目录长在哪个行政区」的本地事实，
 * 全局一份；而「推给 A 局用 340200、推给 B 局用 440300」是发布维度的选择，只能挂在平台上。
 * 平台规则留空（全部 NULL）时沿用组织规则与全局兜底，与升级前等价。
 *
 * <p><b>为什么默认关</b>：{@code code_rule_enabled} 与 {@code publish_ancestors} 都默认 0。
 * 打开前者会改变后续生成的编码，打开后者会一次性放出祖先链整批节点——升级不能替用户做这种决定。
 *
 * <p><b>为什么单独一支迁移器</b>：迁移键是「跑过就跳过」，1.10.2 的
 * {@link SqliteCascadePlatformLayerMigrator} 已在大量库上登记过自己的键，往里加列会导致那些库永远补不上。
 *
 * <p>幂等策略与 {@link SqliteCascadeChannelMetaSchemaMigrator} 一致：加列前查 {@code pragma_table_info}
 * （SQLite 的 {@code ADD COLUMN} 不支持 {@code IF NOT EXISTS}，重复执行会报 duplicate column），
 * 成功后登记 {@code schema_migration_history}。建表脚本的权威源仍是 {@code sql/voglander-sqlite.sql}；
 * 非 SQLite 数据源直接跳过，MySQL/PostgreSQL 由 DBA 执行 {@code sql/migration/*} 下的脚本。
 *
 * @author luna
 */
@Slf4j
@Component
public class SqliteCascadeCodingSchemaMigrator {

    /** 迁移键：只用于人工排查「这个库升级到哪一步了」 */
    public static final String MIGRATION_KEY = "cascade-platform-coding-rules";

    private static final String PLATFORM_TABLE = "tb_cascade_platform";

    /**
     * 列名 → 列定义。类型位（组织 215/216、设备 132、通道 131）不在此列：
     * 它由对象类型决定，允许平台填会把通道生成成 216 开头。
     */
    private static final String[][] COLUMNS = {
        {"code_province", "VARCHAR(2) DEFAULT NULL"},
        {"code_city", "VARCHAR(2) DEFAULT NULL"},
        {"code_county", "VARCHAR(4) DEFAULT NULL"},
        {"code_industry", "VARCHAR(2) DEFAULT NULL"},
        {"code_network", "VARCHAR(1) DEFAULT NULL"},
        {"code_start_seq", "INTEGER DEFAULT NULL"},
        {"code_rule_enabled", "INTEGER NOT NULL DEFAULT 0"},
        {"publish_ancestors", "INTEGER NOT NULL DEFAULT 0"}
    };

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
                /* 表不存在说明尚未建库：由 SqliteSchemaInitializer 的全量脚本负责，这里不做建表 */
                if (!tableExists(connection, PLATFORM_TABLE)) {
                    log.debug("SQLite 尚无 {}，跳过平台编码规则迁移", PLATFORM_TABLE);
                    return;
                }
                for (String[] column : COLUMNS) {
                    addColumnIfMissing(connection, PLATFORM_TABLE, column[0], column[1]);
                }
                verify(connection);
                recordApplied(connection);
                connection.commit();
            } catch (Exception e) {
                rollbackQuietly(connection, e);
                throw new IllegalStateException("SQLite 级联平台编码规则 schema 迁移失败", e);
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 级联平台编码规则 schema 迁移失败", e);
        }
    }

    /** 校验结果：8 列必须全部存在，否则宁可启动失败也不带病运行。 */
    private void verify(Connection connection) throws SQLException {
        for (String[] column : COLUMNS) {
            requireColumn(connection, PLATFORM_TABLE, column[0]);
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

    private void requireColumn(Connection connection, String table, String column) throws SQLException {
        if (!columnExists(connection, table, column)) {
            throw new IllegalStateException("SQLite 表 " + table + " 缺少列 " + column);
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
