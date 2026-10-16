package com.niangao.mihoyo.modules.genshin.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.niangao.mihoyo.common.exception.BusinessException;
import com.niangao.mihoyo.modules.genshin.dto.GenshinRecordRequest;
import com.niangao.mihoyo.modules.genshin.vo.GenshinArchive.*;
import com.niangao.mihoyo.modules.genshin.vo.GenshinArchive.Character;
import com.niangao.mihoyo.modules.genshin.vo.SavedArchive;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** 所有档案修改按账号行锁串行提交；上游网络请求在事务外。 */
@Component
public class GenshinArchiveMapper {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    public GenshinArchiveMapper(JdbcTemplate db, PlatformTransactionManager manager, ObjectMapper json) {
        this.db = db; this.tx = new TransactionTemplate(manager); this.json = json;
    }
    public record Run(long accountId, long id) {}
    private Timestamp time(Instant value) { return Timestamp.from(value); }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception ex) { throw new BusinessException(502, "档案数据无法保存"); }
    }
    private <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (Exception ex) { throw new BusinessException(502, "已保存档案格式异常，请刷新档案"); }
    }
    private long insertId(String sql, Object... values) {
        var key = new GeneratedKeyHolder();
        db.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    private void lock(long account) { db.queryForObject("SELECT id FROM ng_genshin_account WHERE id=? FOR UPDATE", Long.class, account); }
    public Run begin(GenshinRecordRequest request, boolean details) {
        return tx.execute(status -> {
            db.update("INSERT INTO ng_genshin_account(owner_stuid,game_uid,region) VALUES(?,?,?) ON DUPLICATE KEY UPDATE id=id",
                    request.stuid(), request.gameUid(), request.region());
            long account = db.queryForObject("SELECT id FROM ng_genshin_account WHERE owner_stuid=? AND game_uid=? AND region=? FOR UPDATE",
                    Long.class, request.stuid(), request.gameUid(), request.region());
            long id = insertId("INSERT INTO ng_genshin_sync_run(account_id,expected_detail_count) VALUES(?,?)",
                    account, details ? request.characterIds().stream().distinct().count() : null);
            return new Run(account, id);
        });
    }
    public void overview(Run run, Overview value) {
        tx.executeWithoutResult(status -> {
            lock(run.accountId());
            var previous = db.queryForList("SELECT source_sync_id FROM ng_genshin_overview WHERE account_id=?", Long.class, run.accountId());
            if (previous.isEmpty() || previous.get(0) <= run.id()) {
                db.update("INSERT INTO ng_genshin_overview(account_id,achievements,active_days,spiral_abyss,theater_max_act,theater_unlocked,theater_has_data,overview_json,synced_at,source_sync_id) VALUES(?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE achievements=VALUES(achievements),active_days=VALUES(active_days),spiral_abyss=VALUES(spiral_abyss),theater_max_act=VALUES(theater_max_act),theater_unlocked=VALUES(theater_unlocked),theater_has_data=VALUES(theater_has_data),overview_json=VALUES(overview_json),synced_at=VALUES(synced_at),source_sync_id=VALUES(source_sync_id)",
                        run.accountId(), value.achievements(), value.activeDays(), value.spiralAbyss(), value.theater().maxAct(), value.theater().unlocked(), value.theater().hasData(), encode(value), time(value.updatedAt()), run.id());
                db.update("UPDATE ng_genshin_account SET nickname=?,adventure_level=?,avatar_url=? WHERE id=?", value.nickname(), value.level(), value.avatar(), run.accountId());
            }
            finish(run, "SUCCESS", true, false, 0, 0, null, null, null);
        });
    }
    private Character basic(Character value) {
        return new Character(value.id(), value.name(), value.icon(), value.element(), value.rarity(), value.level(),
                value.constellation(), value.friendship(), value.weapon(), List.of(), List.of(), List.of(), false);
    }
    public void characters(Run run, Characters value, boolean details, int expected) {
        tx.executeWithoutResult(status -> {
            lock(run.accountId());
            Long roster = db.queryForObject("SELECT roster_sync_id FROM ng_genshin_account WHERE id=?", Long.class, run.accountId());
            boolean apply = details || roster == null || roster <= run.id();
            if (apply && !details) db.update("UPDATE ng_genshin_character SET in_roster=0 WHERE account_id=?", run.accountId());
            if (apply) for (Character character : value.list()) {
                var rows = db.queryForList("SELECT basic_sync_id,detail_sync_id,in_roster FROM ng_genshin_character WHERE account_id=? AND character_id=?", run.accountId(), character.id());
                if (details && roster != null && (rows.isEmpty() || ((Number) rows.get(0).get("in_roster")).intValue() == 0)) continue;
                long previous = rows.isEmpty() ? 0 : ((Number) rows.get(0).get("basic_sync_id")).longValue();
                if (previous <= run.id()) db.update("INSERT INTO ng_genshin_character(account_id,character_id,name,element,rarity,level,constellation,friendship,basic_json,basic_synced_at,basic_sync_id,in_roster) VALUES(?,?,?,?,?,?,?,?,?,?,?,1) ON DUPLICATE KEY UPDATE name=VALUES(name),element=VALUES(element),rarity=VALUES(rarity),level=VALUES(level),constellation=VALUES(constellation),friendship=VALUES(friendship),basic_json=VALUES(basic_json),basic_synced_at=VALUES(basic_synced_at),basic_sync_id=VALUES(basic_sync_id),in_roster=1",
                        run.accountId(), character.id(), character.name(), character.element(), character.rarity(), character.level(), character.constellation(), character.friendship(), encode(basic(character)), time(value.updatedAt()), run.id());
                else if (!details) db.update("UPDATE ng_genshin_character SET in_roster=1 WHERE account_id=? AND character_id=?", run.accountId(), character.id());
                if (details && character.detailed() && previous <= run.id()) db.update("UPDATE ng_genshin_character SET detail_json=?,detail_synced_at=?,detail_sync_id=? WHERE account_id=? AND character_id=? AND (detail_sync_id IS NULL OR detail_sync_id<=?)",
                        encode(character), time(value.updatedAt()), run.id(), run.accountId(), character.id(), run.id());
            }
            if (apply && !details) db.update("UPDATE ng_genshin_account SET roster_synced_at=?,roster_sync_id=? WHERE id=?", time(value.updatedAt()), run.id(), run.accountId());
            int succeeded = details ? (int) value.list().stream().filter(Character::detailed).count() : 0;
            finish(run, details && succeeded < expected ? "PARTIAL" : "SUCCESS", false, !details, succeeded,
                    details ? Math.max(0, expected - succeeded) : 0, details && succeeded < expected ? "genshin_equipment" : null,
                    null, details && succeeded < expected ? "partial_details" : null);
        });
    }
    public void failed(Run run, String stage, Integer code, String reason, int expected) {
        finish(run, "FAILED", false, false, 0, expected, stage, code, reason);
    }
    private void finish(Run run, String status, boolean overview, boolean roster, int succeeded, int failed, String stage, Integer code, String reason) {
        db.update("UPDATE ng_genshin_sync_run SET status=?,overview_succeeded=?,roster_succeeded=?,succeeded_detail_count=?,failed_detail_count=?,error_stage=?,upstream_retcode=?,error_reason=?,finished_at=UTC_TIMESTAMP(3) WHERE id=? AND account_id=? AND status='RUNNING'",
                status, overview, roster, succeeded, failed, stage, code, reason, run.id(), run.accountId());
    }
    public SavedArchive read(GenshinRecordRequest request) {
        return tx.execute(status -> {
            var ids = db.queryForList("SELECT id FROM ng_genshin_account WHERE owner_stuid=? AND game_uid=? AND region=? FOR UPDATE", Long.class, request.stuid(), request.gameUid(), request.region());
            if (ids.isEmpty()) return new SavedArchive(request.gameUid(), null, null, Map.of(), List.of());
            long account = ids.get(0);
            var overviews = db.queryForList("SELECT overview_json FROM ng_genshin_overview WHERE account_id=?", String.class, account);
            Overview overview = overviews.isEmpty() ? null : decode(overviews.get(0), Overview.class);
            Instant rosterTime = db.queryForObject("SELECT roster_synced_at FROM ng_genshin_account WHERE id=?", (rs, row) -> instant(rs, "roster_synced_at"), account);
            var detailsAt = new HashMap<String, Instant>();
            List<Character> characters = db.query("SELECT basic_json,detail_json,detail_synced_at FROM ng_genshin_character WHERE account_id=? AND in_roster=1 ORDER BY level DESC,character_id", (rs, row) -> {
                Character base = decode(rs.getString("basic_json"), Character.class);
                if (rs.getString("detail_json") == null) return base;
                Character detail = decode(rs.getString("detail_json"), Character.class);
                detailsAt.put(base.id(), instant(rs, "detail_synced_at"));
                return new Character(base.id(), base.name(), base.icon(), base.element(), base.rarity(), base.level(), base.constellation(), base.friendship(),
                        detail.weapon(), detail.skills(), detail.artifacts(), detail.properties(), true);
            }, account);
            var queries = db.query("SELECT * FROM ng_genshin_sync_run WHERE account_id=? ORDER BY id DESC LIMIT 10", (rs, row) -> new SavedArchive.QueryRecord(
                    rs.getLong("id"), rs.getString("status"), rs.getBoolean("overview_succeeded"), rs.getBoolean("roster_succeeded"),
                    integer(rs, "expected_detail_count"), rs.getInt("succeeded_detail_count"), rs.getInt("failed_detail_count"),
                    rs.getString("error_stage"), integer(rs, "upstream_retcode"), rs.getString("error_reason"), instant(rs, "started_at"), instant(rs, "finished_at")), account);
            return new SavedArchive(request.gameUid(), overview, rosterTime == null ? null : new Characters(request.gameUid(), characters.size(), characters, rosterTime), detailsAt, queries);
        });
    }
    private Instant instant(ResultSet rs, String field) throws SQLException {
        Timestamp value = rs.getTimestamp(field); return value == null ? null : value.toInstant();
    }
    private Integer integer(ResultSet rs, String field) throws SQLException {
        Number value = (Number) rs.getObject(field); return value == null ? null : value.intValue();
    }
}
