package io.github.lunasaw.voglander.repository.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.annotation.DbType;

import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishKeys;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishObjectType;
import lombok.extern.slf4j.Slf4j;

/**
 * SQLite 级联「通道进入发布层」数据迁移：{@code tb_cascade_channel} → {@code tb_cascade_publish}。
 *
 * <p><b>为什么是数据迁移而不是建表迁移</b>：老表的每一行都是「某个平台看到的某条通道」，
 * 与新模型里 {@code object_type='CHANNEL'} 的发布行一一对应，字段也是逐一可翻译的。
 * 不迁这一下，目录组装切到发布集的那一刻，存量部署的上级目录会瞬间清空。
 *
 * <p><b>翻译规则</b>（与方案文档 §8 一致）：
 * <pre>
 * object_key    local_device_id ':' local_channel_id
 * publish_code  cascade_channel_id
 * publish_name  cascade_name
 * enabled       enabled
 * parent_key    cascade_org_id（见下）
 * 元数据五列      原样搬
 * revoke_policy 只属于组织，通道留空
 * </pre>
 *
 * <p><b>{@code parent_key} 为什么不是无脑搬 {@code cascade_org_id}</b>：老表的归属是<b>按平台</b>的，
 * 而 {@code tb_device_channel.org_id} 只能存一份。当该通道在所有平台的归属一致时，回填本地表、
 * {@code parent_key} 留空——这样以后在本地把通道换个组织，所有平台的目录都会跟着动；
 * 归属不一致时本地表不动，差异逐行写进 {@code parent_key}，行为与迁移前完全一致。
 *
 * <p><b>孤儿行丢弃</b>：老表里指向已删通道的行翻不成业务键，留着只会让组装器每次告警，
 * 与 {@link SqliteCascadePublishKeyMigrator} 的处理口径一致。
 *
 * <p>幂等靠 {@code schema_migration_history} 的迁移键，一次演进一个 key、一个事务。
 * 建表脚本的权威源仍是 {@code sql/voglander-sqlite.sql}；非 SQLite 数据源直接跳过，
 * MySQL/PostgreSQL 由 DBA 执行 {@code sql/migration/*}{@code /1.10.2-cascade-channel-into-publish.sql}。
 *
 * @author luna
 */
@Slf4j
@Component
public class SqliteCascadeChannelPublishMigrator {

    /** 迁移键：只用于人工排查"这个库升级到哪一步了" */
    public static final String MIGRATION_KEY = "cascade-channel-into-publish";

    private static final String CHANNEL_TABLE = "tb_cascade_channel";
    private static final String LOCAL_TABLE   = "tb_device_channel";
    private static final String ORG_TABLE     = "tb_cascade_org";
    private static final String PUBLISH_TABLE = "tb_cascade_publish";

    private static final String OBJECT_TYPE = PublishObjectType.CHANNEL.name();

    /** 老表全量读取：行数在千级，一次读进内存比逐行 join 更好排查（不一致的归属要整体判断） */
    private static final String SELECT_LEGACY = "SELECT platform_id, local_device_id, local_channel_id,"
        + " cascade_channel_id, cascade_name, cascade_org_id, enabled,"
        + " manufacturer, model, address, ip_address, ptz_type"
        + " FROM " + CHANNEL_TABLE + " ORDER BY platform_id, local_channel_id";

    /** 新表写入：{@code OR IGNORE} 兜住「已经通过新接口配过同一通道」的重复行，不覆盖用户后来的配置 */
    private static final String INSERT_PUBLISH = "INSERT OR IGNORE INTO " + PUBLISH_TABLE
        + " (platform_id, object_type, object_key, parent_key, publish_code, publish_name, enabled,"
        + " manufacturer, model, address, ip_address, ptz_type)"
        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    @Autowired(required = false)
    private DataSource dataSource;

    void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 执行迁移。非 SQLite、数据源缺失或相关表尚未建出时静默跳过。
     *
     * @throws IllegalStateException 迁移失败（上抛阻断启动，不留「目录来源半迁移」的隐性坏库）
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
                if (applied(connection)) {
                    log.debug("SQLite 迁移 {} 已登记，跳过通道发布层迁移", MIGRATION_KEY);
                    return;
                }
                if (!tableExists(connection, CHANNEL_TABLE) || !tableExists(connection, PUBLISH_TABLE)) {
                    log.debug("SQLite 尚无 {} 或 {}，跳过通道发布层迁移", CHANNEL_TABLE, PUBLISH_TABLE);
                    return;
                }
                List<LegacyRow> rows = loadLegacyRows(connection);
                if (rows.isEmpty()) {
                    recordApplied(connection);
                    connection.commit();
                    return;
                }
                Map<String, Long> localOrgIdByChannel = localOrgIdByChannel(connection);
                Map<String, Long> orgIdByCode = orgIdByCode(connection);
                Map<String, Set<String>> orgCodesByChannel = groupOrgCodes(rows);
                Set<String> backfilled = backfillLocalOrg(connection, orgCodesByChannel, localOrgIdByChannel,
                    orgIdByCode);

                Set<String> existingKeys = existingChannelKeys(connection);
                Set<String> existingCodes = existingPublishCodes(connection);
                int inserted = insertPublishRows(connection, rows, localOrgIdByChannel.keySet(), backfilled,
                    existingKeys, existingCodes);

                recordApplied(connection);
                connection.commit();
                log.info("SQLite 迁移：{} → {} 通道发布行完成（写入 {} 条，回填本地组织 {} 条）",
                    CHANNEL_TABLE, PUBLISH_TABLE, inserted, backfilled.size());
            } catch (Exception e) {
                rollbackQuietly(connection, e);
                throw new IllegalStateException("SQLite 级联通道发布层迁移失败", e);
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 级联通道发布层迁移失败", e);
        }
    }

    // ------------------------------------------------------------------ 读取

    private List<LegacyRow> loadLegacyRows(Connection connection) throws SQLException {
        List<LegacyRow> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
            ResultSet rs = statement.executeQuery(SELECT_LEGACY)) {
            while (rs.next()) {
                rows.add(new LegacyRow(
                    rs.getString("platform_id"),
                    rs.getString("local_device_id"),
                    rs.getString("local_channel_id"),
                    rs.getString("cascade_channel_id"),
                    rs.getString("cascade_name"),
                    StringUtils.trimToNull(rs.getString("cascade_org_id")),
                    rs.getInt("enabled"),
                    rs.getString("manufacturer"),
                    rs.getString("model"),
                    rs.getString("address"),
                    rs.getString("ip_address"),
                    rs.getObject("ptz_type") == null ? null : rs.getInt("ptz_type")));
            }
        }
        return rows;
    }

    /**
     * 每条通道在各平台出现过的归属组织编码。
     *
     * <p>只有「唯一值」才允许回填本地表——本地列只有一份，多值意味着必须靠 {@code parent_key} 保留差异。
     */
    private Map<String, Set<String>> groupOrgCodes(List<LegacyRow> rows) {
        Map<String, Set<String>> grouped = new LinkedHashMap<>();
        for (LegacyRow row : rows) {
            if (StringUtils.isBlank(row.deviceId()) || StringUtils.isBlank(row.channelId())) {
                continue;
            }
            grouped.computeIfAbsent(PublishKeys.channel(row.deviceId(), row.channelId()),
                key -> new LinkedHashSet<>());
            if (row.orgCode() != null) {
                grouped.get(PublishKeys.channel(row.deviceId(), row.channelId())).add(row.orgCode());
            }
        }
        return grouped;
    }

    /**
     * 本地通道表已有的组织归属：{@code 业务键 → org_id}（值可为 null，表示尚未配置归属）。
     *
     * <p>一次读出来同时用于两件事：判断通道是否存在（孤儿行）、判断回填会不会盖掉用户配置。
     */
    private Map<String, Long> localOrgIdByChannel(Connection connection) throws SQLException {
        Map<String, Long> index = new HashMap<>();
        try (Statement statement = connection.createStatement();
            ResultSet rs = statement.executeQuery("SELECT device_id, channel_id, org_id FROM " + LOCAL_TABLE)) {
            while (rs.next()) {
                /* 必须先把 org_id 取成对象再读其他列：wasNull() 看的是最后一次读取的列 */
                Object orgId = rs.getObject("org_id");
                index.put(PublishKeys.channel(rs.getString("device_id"), rs.getString("channel_id")),
                    orgId == null ? null : ((Number)orgId).longValue());
            }
        }
        return index;
    }

    private Map<String, Long> orgIdByCode(Connection connection) throws SQLException {
        Map<String, Long> index = new HashMap<>();
        try (Statement statement = connection.createStatement();
            ResultSet rs = statement.executeQuery("SELECT id, org_code FROM " + ORG_TABLE)) {
            while (rs.next()) {
                index.put(rs.getString("org_code"), rs.getLong("id"));
            }
        }
        return index;
    }

    /** 已存在的通道发布行（{@code platform_id + '\u0000' + object_key}）：重复启动 / 已手工配过则不再插入 */
    private Set<String> existingChannelKeys(Connection connection) throws SQLException {
        Set<String> keys = new HashSet<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT platform_id, object_key FROM "
            + PUBLISH_TABLE + " WHERE object_type = ?")) {
            ps.setString(1, OBJECT_TYPE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keys.add(scopedKey(rs.getString("platform_id"), rs.getString("object_key")));
                }
            }
        }
        return keys;
    }

    /**
     * 平台内已占用的发布编码。
     *
     * <p>取全类型而不只 CHANNEL：{@code uk_publish_code} 是跨类型防重码，
     * 通道编码撞上某个组织或设备编码时插入会静默失败，必须提前算出来才能在日志里说清楚。
     */
    private Set<String> existingPublishCodes(Connection connection) throws SQLException {
        Set<String> codes = new HashSet<>();
        try (Statement statement = connection.createStatement();
            ResultSet rs = statement.executeQuery(
                "SELECT platform_id, publish_code FROM " + PUBLISH_TABLE + " WHERE publish_code IS NOT NULL")) {
            while (rs.next()) {
                codes.add(scopedKey(rs.getString("platform_id"), rs.getString("publish_code")));
            }
        }
        return codes;
    }

    // ------------------------------------------------------------------ 写入

    /**
     * 把「所有平台归属一致」的通道归属回填进本地通道表。
     *
     * <p>只写 {@code org_id IS NULL} 的行：R1 之后用户可能已经手工配过归属，
     * 迁移不能把它改成老平台上的那个值。本地已有归属且与老值不同时，也<b>不</b>算回填，
     * 那几行继续靠 {@code parent_key} 维持迁移前的行为。
     *
     * @return 回填成功、或本地归属本已与之一致的通道业务键，这些通道的发布行 {@code parent_key} 可以留空
     */
    private Set<String> backfillLocalOrg(Connection connection, Map<String, Set<String>> orgCodesByChannel,
        Map<String, Long> localOrgIdByChannel, Map<String, Long> orgIdByCode) throws SQLException {
        Set<String> backfilled = new HashSet<>();
        String sql = "UPDATE " + LOCAL_TABLE + " SET org_id = ?"
            + " WHERE device_id = ? AND channel_id = ? AND org_id IS NULL";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (Map.Entry<String, Set<String>> entry : orgCodesByChannel.entrySet()) {
                if (entry.getValue().size() != 1) {
                    continue;
                }
                String orgCode = entry.getValue().iterator().next();
                Long orgId = orgIdByCode.get(orgCode);
                if (orgId == null) {
                    log.warn("迁移回填通道组织时组织已不存在，保留发布行 parent_key: channel={}, orgCode={}",
                        entry.getKey(), orgCode);
                    continue;
                }
                Long current = localOrgIdByChannel.get(entry.getKey());
                if (orgId.equals(current)) {
                    backfilled.add(entry.getKey());
                    continue;
                }
                if (current != null) {
                    /* 用户已配过另一份归属：以用户为准，老平台的归属留在 parent_key 里 */
                    continue;
                }
                ps.setLong(1, orgId);
                ps.setString(2, PublishKeys.channelDeviceId(entry.getKey()));
                ps.setString(3, PublishKeys.channelChannelId(entry.getKey()));
                ps.executeUpdate();
                backfilled.add(entry.getKey());
            }
        }
        return backfilled;
    }

    /**
     * 逐行写入通道发布行。
     *
     * @return 实际写入行数（已存在、孤儿、编码冲突的行都不算）
     */
    private int insertPublishRows(Connection connection, List<LegacyRow> rows, Set<String> localKeys,
        Set<String> backfilled, Set<String> existingKeys, Set<String> existingCodes) throws SQLException {
        int inserted = 0;
        int orphan = 0;
        int skipped = 0;
        int codeConflict = 0;
        try (PreparedStatement ps = connection.prepareStatement(INSERT_PUBLISH)) {
            for (LegacyRow row : rows) {
                if (StringUtils.isBlank(row.deviceId()) || StringUtils.isBlank(row.channelId())) {
                    orphan++;
                    continue;
                }
                String objectKey = PublishKeys.channel(row.deviceId(), row.channelId());
                if (!localKeys.contains(objectKey)) {
                    orphan++;
                    continue;
                }
                String platformId = row.platformId();
                if (existingKeys.contains(scopedKey(platformId, objectKey))) {
                    skipped++;
                    continue;
                }
                String code = StringUtils.trimToNull(row.code());
                if (code != null && existingCodes.contains(scopedKey(platformId, code))) {
                    /* 同一平台内编码已被别的对象占用：插进去会卡住 uk_publish_code，只能跳过并让人工处理 */
                    codeConflict++;
                    log.warn("迁移通道发布行时编码在该平台已被占用，跳过: platformId={}, objectKey={}, code={}",
                        platformId, objectKey, code);
                    continue;
                }
                /* 归属已回填本地表的通道不再写 parent_key：否则以后在本地换组织，上级目录不会跟着动 */
                String parentKey = backfilled.contains(objectKey) ? null : row.orgCode();
                ps.setString(1, platformId);
                ps.setString(2, OBJECT_TYPE);
                ps.setString(3, objectKey);
                ps.setString(4, parentKey);
                ps.setString(5, code);
                ps.setString(6, StringUtils.trimToNull(row.name()));
                ps.setInt(7, row.enabled());
                ps.setString(8, row.manufacturer());
                ps.setString(9, row.model());
                ps.setString(10, row.address());
                ps.setString(11, row.ipAddress());
                if (row.ptzType() == null) {
                    ps.setNull(12, java.sql.Types.INTEGER);
                } else {
                    ps.setInt(12, row.ptzType());
                }
                inserted += ps.executeUpdate();
                if (code != null) {
                    existingCodes.add(scopedKey(platformId, code));
                }
                existingKeys.add(scopedKey(platformId, objectKey));
            }
        }
        if (orphan + skipped + codeConflict > 0) {
            log.info("迁移通道发布行明细: 写入 {} / 孤儿丢弃 {} / 已存在跳过 {} / 编码冲突跳过 {}",
                inserted, orphan, skipped, codeConflict);
        }
        return inserted;
    }

    // ------------------------------------------------------------------ 基础

    /** 平台作用域键：{@code platform_id + '\u0000' + value}，避免跨平台值相同被误判 */
    private String scopedKey(String platformId, String value) {
        return platformId + '\u0000' + value;
    }

    private boolean applied(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT 1 FROM schema_migration_history WHERE migration_key = ?")) {
            ps.setString(1, MIGRATION_KEY);
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

    private void rollbackQuietly(Connection connection, Exception cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackError) {
            cause.addSuppressed(rollbackError);
        }
    }

    /** 老表的一行：某平台看到的某条通道及其上报配置。 */
    private record LegacyRow(String platformId, String deviceId, String channelId, String code, String name,
        String orgCode, int enabled, String manufacturer, String model, String address, String ipAddress,
        Integer ptzType) {
    }
}
