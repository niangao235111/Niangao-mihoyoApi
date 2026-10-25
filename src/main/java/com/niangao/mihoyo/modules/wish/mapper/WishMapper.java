package com.niangao.mihoyo.modules.wish.mapper;

import com.niangao.mihoyo.modules.wish.dto.WishRecordsRequest;
import com.niangao.mihoyo.modules.wish.dto.WishRoleRequest;
import com.niangao.mihoyo.modules.wish.entity.WishRecord;
import com.niangao.mihoyo.modules.wish.util.WishCodec;
import com.niangao.mihoyo.modules.wish.vo.WishPage;
import com.niangao.mihoyo.modules.wish.vo.WishTask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.niangao.mihoyo.common.exception.BusinessException;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;
import java.sql.*;

@Repository
public class WishMapper {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    public WishMapper(JdbcTemplate db,PlatformTransactionManager manager,ObjectMapper json) {
        this.db=db; this.tx=new TransactionTemplate(manager); this.json=json;
    }
    public long account(String owner,WishRoleRequest role) {
        db.update("INSERT INTO ng_genshin_account(owner_stuid,game_uid,region) VALUES(?,?,?) ON DUPLICATE KEY UPDATE id=id",owner,role.gameUid(),role.region());
        return db.queryForObject("SELECT id FROM ng_genshin_account WHERE owner_stuid=? AND game_uid=? AND region=?",Long.class,owner,role.gameUid(),role.region());
    }
    public void begin(long account,String task,String kind) {
        db.update("INSERT INTO ng_genshin_wish_sync_run(task_id,account_id,kind) VALUES(?,?,?)",task,account,kind);
    }
    public void cancel(long account,String task) {
        db.update("UPDATE ng_genshin_wish_sync_run SET cancel_requested=1 WHERE task_id=? AND account_id=? AND status='RUNNING'",task,account);
    }
    public boolean cancelled(String task) {
        return db.queryForObject("SELECT cancel_requested FROM ng_genshin_wish_sync_run WHERE task_id=?",Integer.class,task)!=0;
    }
    public int save(long account,String task,List<WishRecord> records,Object progress) {
        return tx.execute(s -> {
            // 与取消和同账号导入串行，网络读取不占用数据库事务。
            db.queryForObject("SELECT id FROM ng_genshin_account WHERE id=? FOR UPDATE",Long.class,account);
            var state=db.queryForMap("SELECT status,cancel_requested FROM ng_genshin_wish_sync_run WHERE task_id=? AND account_id=? FOR UPDATE",task,account);
            if(!"RUNNING".equals(state.get("status")) || ((Number)state.get("cancel_requested")).intValue()!=0) throw new BusinessException(409,"祈愿任务已停止",Map.of("reason","wish_cancelled"));
            int inserted=0;
            for(WishRecord record:records) {
                List<WishRecord> existing=db.query("SELECT * FROM ng_genshin_wish_record WHERE account_id=? AND record_id=?",recordMapper,account,record.id());
                if(!existing.isEmpty()) {
                    WishRecord old=existing.get(0);
                    if(!same(old,record)) throw new BusinessException(409,"相同祈愿记录 ID 的内容冲突，旧记录未覆盖",Map.of("reason","wish_record_conflict"));
                    continue;
                }
                inserted+=db.update("INSERT INTO ng_genshin_wish_record(account_id,record_id,gacha_type,pool_group,item_id,name,item_type,rank_type,quantity,wish_time,source) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                        account,record.id(),record.gachaType(),record.poolGroup(),record.itemId(),record.name(),record.itemType(),record.rankType(),record.count(),record.time(),record.source());
            }
            db.update("UPDATE ng_genshin_wish_sync_run SET fetched_count=fetched_count+?,inserted_count=inserted_count+?,duplicate_count=duplicate_count+?,progress_json=?,updated_at=CURRENT_TIMESTAMP(3) WHERE task_id=?",
                    records.size(),inserted,records.size()-inserted,encode(progress),task);
            return inserted;
        });
    }
    private boolean same(WishRecord a,WishRecord b) {
        // 部分旧记录 item_id 为空，后续同一条的非空 ID 不视为冲突；保持原数据不静默覆盖。
        return a.id().equals(b.id()) && a.gachaType().equals(b.gachaType()) && a.name().equals(b.name()) && a.itemType().equals(b.itemType())
                && a.rankType()==b.rankType() && a.count()==b.count() && a.time().equals(b.time())
                && (a.itemId().isEmpty()||b.itemId().isEmpty()||a.itemId().equals(b.itemId()));
    }
    public void finish(String task,String status,String stage,String reason,Integer code) {
        db.update("UPDATE ng_genshin_wish_sync_run SET status=CASE WHEN cancel_requested=1 THEN 'CANCELLED' ELSE ? END,error_stage=?,error_reason=?,upstream_retcode=?,finished_at=CURRENT_TIMESTAMP(3) WHERE task_id=? AND status='RUNNING'",
                status,stage,reason,code,task);
    }
    public void progress(String task,Object progress) {
        db.update("UPDATE ng_genshin_wish_sync_run SET progress_json=?,updated_at=CURRENT_TIMESTAMP(3) WHERE task_id=? AND status='RUNNING'",encode(progress),task);
    }
    public WishTask task(long account,String task) {
        var result=db.query("SELECT * FROM ng_genshin_wish_sync_run WHERE account_id=? AND task_id=?",taskMapper,account,task);
        if(result.isEmpty()) throw new BusinessException(404,"祈愿任务不存在");
        return result.get(0);
    }
    public WishTask latest(long account) {
        var result=db.query("SELECT * FROM ng_genshin_wish_sync_run WHERE account_id=? ORDER BY created_at DESC,task_id DESC LIMIT 1",taskMapper,account);
        return result.isEmpty()?null:result.get(0);
    }
    public WishPage records(long account,WishRecordsRequest query) {
        List<Object> args=new ArrayList<>(); args.add(account);
        String sql="SELECT * FROM ng_genshin_wish_record WHERE account_id=?";
        if(query.pool()!=null) {sql+=" AND pool_group=?";args.add(query.pool());}
        if(query.rank()!=null) {sql+=" AND rank_type=?";args.add(query.rank());}
        if(query.beforeId()!=null) {sql+=" AND record_id<?";args.add(query.beforeId());}
        if(query.fromDate()!=null) {sql+=" AND wish_time>=?";args.add(query.fromDate()+" 00:00:00");}
        if(query.toDate()!=null) {sql+=" AND wish_time<=?";args.add(query.toDate()+" 23:59:59");}
        int limit=query.limit()==null?50:query.limit(); args.add(limit+1);
        List<WishRecord> values=db.query(sql+" ORDER BY record_id DESC LIMIT ?",recordMapper,args.toArray());
        boolean more=values.size()>limit;
        List<WishRecord> page=List.copyOf(values.subList(0,Math.min(values.size(),limit)));
        return new WishPage(page,page.isEmpty()?null:page.get(page.size()-1).id(),more);
    }
    public List<WishRecord> all(long account) {
        var result=db.query("SELECT * FROM ng_genshin_wish_record WHERE account_id=? ORDER BY record_id ASC LIMIT 100001",recordMapper,account);
        if(result.size()>100000) throw new BusinessException(413,"记录超过单次分析或导出的 100000 条上限，请使用分页明细");
        return result;
    }
    private final RowMapper<WishRecord> recordMapper=(r,n)->new WishRecord(r.getBigDecimal("record_id").toPlainString(),r.getString("gacha_type"),r.getString("pool_group"),r.getString("item_id"),r.getString("name"),r.getString("item_type"),r.getInt("rank_type"),r.getInt("quantity"),r.getObject("wish_time",java.time.LocalDateTime.class).format(WishCodec.TIME),r.getString("source"));
    private final RowMapper<WishTask> taskMapper=(r,n)->new WishTask(r.getString("task_id"),r.getString("kind"),r.getString("status"),r.getBoolean("cancel_requested"),r.getInt("fetched_count"),r.getInt("inserted_count"),r.getInt("duplicate_count"),decode(r.getString("progress_json")),r.getString("error_stage"),r.getString("error_reason"),(Integer)r.getObject("upstream_retcode"),stamp(r,"created_at"),stamp(r,"updated_at"),stamp(r,"finished_at"));
    private String stamp(ResultSet r,String field) throws SQLException { return r.getTimestamp(field)==null?null:r.getTimestamp(field).toInstant().toString(); }
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception ex){throw new BusinessException(500,"祈愿进度保存失败");}}
    private Object decode(String value) {try{return value==null?Map.of():json.readValue(value,Object.class);}catch(Exception ex){return Map.of();}}
}
