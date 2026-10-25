package com.niangao.mihoyo.modules.wish.service.impl;

import com.niangao.mihoyo.modules.wish.client.WishUpstreamClient;
import com.niangao.mihoyo.modules.wish.dto.WishImportRequest;
import com.niangao.mihoyo.modules.wish.dto.WishRecordsRequest;
import com.niangao.mihoyo.modules.wish.dto.WishRoleRequest;
import com.niangao.mihoyo.modules.wish.dto.WishSyncRequest;
import com.niangao.mihoyo.modules.wish.dto.WishTaskRequest;
import com.niangao.mihoyo.modules.wish.entity.WishRecord;
import com.niangao.mihoyo.modules.wish.mapper.WishMapper;
import com.niangao.mihoyo.modules.wish.service.WishService;
import com.niangao.mihoyo.modules.wish.util.WishCodec;
import com.niangao.mihoyo.modules.wish.vo.WishFiveStar;
import com.niangao.mihoyo.modules.wish.vo.WishPage;
import com.niangao.mihoyo.modules.wish.vo.WishPoolSummary;
import com.niangao.mihoyo.modules.wish.vo.WishSummary;
import com.niangao.mihoyo.modules.wish.vo.WishTask;
import static com.niangao.mihoyo.modules.wish.util.WishPools.POOLS;

import com.niangao.mihoyo.common.exception.BusinessException;
import com.niangao.mihoyo.modules.genshin.util.GenshinChannels;
import com.niangao.mihoyo.modules.workbench.entity.WorkbenchSecret;
import com.niangao.mihoyo.modules.workbench.mapper.WorkbenchSessionMapper;
import com.niangao.mihoyo.modules.workbench.util.WorkbenchCipher;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

@Service
public class WishServiceImpl implements WishService {
    private final WishMapper store;
    private final WishUpstreamClient upstream;
    private final StringRedisTemplate redis;
    private final WorkbenchSessionMapper sessions;
    private final WorkbenchCipher cipher;
    private final long delay;
    private final String prefix;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{Thread t=new Thread(r,"wish-sync");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static final DefaultRedisScript<Long> RELEASE=new DefaultRedisScript<>("if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) end return 0",Long.class);
    private static final DefaultRedisScript<Long> RENEW=new DefaultRedisScript<>("if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('PEXPIRE',KEYS[1],120000) end return 0",Long.class);
    public WishServiceImpl(WishMapper store,WishUpstreamClient upstream,StringRedisTemplate redis,WorkbenchSessionMapper sessions,WorkbenchCipher cipher,
            @Value("${genshin.wishes.page-delay-ms:750}") long delay,@Value("${mihoyo.redis.key-prefix:niangao:mihoyo}") String prefix) {
        this.store=store;this.upstream=upstream;this.redis=redis;this.sessions=sessions;this.cipher=cipher;this.delay=Math.max(0,delay);this.prefix=prefix+":wishes:";
    }
    @PreDestroy void close(){workers.shutdownNow();}
    private long authorize(WorkbenchSecret secret,WishRoleRequest role) {
        if(!GenshinChannels.supports(role.gameBiz(),role.region()) || secret.roles().stream().noneMatch(r->role.gameUid().equals(r.gameUid()) && role.region().equals(r.region()) && GenshinChannels.supports(r.gameBiz(),r.region()))) throw new BusinessException(403,"所选国服角色未关联当前工作台账号");
        try {return store.account(secret.stuid(),role);} catch(DataAccessException ex){throw storageFailure();}
    }
    @Override
    public WishTask sync(WorkbenchSecret secret,String digest,WishSyncRequest query) {
        long account=authorize(secret,query.role());
        String task=UUID.randomUUID().toString();
        // 在 HTTP 返回前解析授权输入；后台闭包只携带密文及会话摘要，不持有原始凭证。
        String supplied=query.historyUrl()==null || query.historyUrl().isBlank()?null:WishCodec.authkey(query.historyUrl(),query.role());
        String encrypted=supplied==null?null:cipher.encryptValue(supplied,digest+":"+task);
        acquire(account,task);
        try {
            store.begin(account,task,"SYNC");
            workers.execute(()->run(account,task,digest,query.role(),encrypted));
            return store.task(account,task);
        } catch(RejectedExecutionException ex) {
            store.finish(task,"FAILED","genshin_wishes","wish_workers_busy",null);release(account,task);
            throw new BusinessException(429,"祈愿同步任务繁忙，请稍后再试");
        } catch(RuntimeException ex){release(account,task);throw safe(ex);}
    }
    private void run(long account,String task,String digest,WishRoleRequest role,String encrypted) {
        String stage="genshin_wish_auth";
        Map<String,Object> progress=new LinkedHashMap<>();
        Map<String,Object> lastRequest=new LinkedHashMap<>();
        String cache=prefix+"auth:"+digest+":"+account;
        try {
            progress.put("stage","authorizing");store.progress(task,progress);
            WorkbenchSecret secret=current(digest);
            String key;
            if(encrypted!=null) key=cipher.decryptValue(encrypted,digest+":"+task,String.class);
            else {
                String stored=redis.opsForValue().get(cache);
                key=stored==null?upstream.authorize(secret,role):cipher.decryptValue(stored,cache,String.class);
            }
            // 缓存有效期是本地策略，不是官方有效期；logout 后旧会话摘要无法使用。
            redis.opsForValue().set(cache,cipher.encryptValue(key,cache),Duration.ofMinutes(30));
            long deadline=System.nanoTime()+Duration.ofMinutes(20).toNanos();
            stage="genshin_wishes";
            for(String pool:POOLS) {
                String cursor="0"; boolean done=false; int count=0;
                for(int page=0;page<2000;page++) {
                    check(account,task,digest,deadline);
                    progress.put("stage","reading");progress.put("current_pool",pool);progress.put("current_page",page+1);
                    store.progress(task,progress);
                    lastRequest.put("pool",pool);lastRequest.put("end_id",cursor);lastRequest.put("page",page+1);
                    List<WishRecord> records=page(account,task,digest,deadline,key,role,pool,cursor);
                    check(account,task,digest,deadline);
                    if(records.isEmpty()) {progress.put(pool,Map.of("cursor",cursor,"finished",true,"pages",page,"count",count));store.save(account,task,List.of(),progress);done=true;break;}
                    BigInteger previous="0".equals(cursor)?null:new BigInteger(cursor);
                    for(WishRecord record:records) {
                        BigInteger id=new BigInteger(record.id());
                        if(previous!=null && id.compareTo(previous)>=0) throw new BusinessException(502,"祈愿分页未前进",Map.of("reason","wish_cursor_stalled"));
                        previous=id;
                    }
                    cursor=records.get(records.size()-1).id();
                    count+=records.size();
                    progress.put(pool,Map.of("cursor",cursor,"finished",false,"pages",page+1,"count",count));
                    store.save(account,task,records,progress);
                    // 从头回扫所有可读取记录，使上次任务的旧缺口也能补齐；落库仍是幂等增量。
                    if(delay>0) Thread.sleep(delay);
                }
                if(!done) throw new BusinessException(422,"祈愿分页超过任务上限",Map.of("reason","wish_page_limit"));
            }
            progress.put("stage","complete");store.progress(task,progress);
            store.finish(task,"SUCCESS",null,null,null);
        } catch(Exception ex) {
            if(ex instanceof InterruptedException) Thread.currentThread().interrupt();
            BusinessException business=ex instanceof BusinessException b?b:null;
            String reason=business==null?"wish_sync_failed":String.valueOf(business.getDetails().getOrDefault("reason","wish_sync_failed"));
            Integer code=business!=null && business.getDetails().get("upstream_retcode") instanceof Number n?n.intValue():null;
            String failedStage=business!=null?String.valueOf(business.getDetails().getOrDefault("stage",stage)):stage;
            try {redis.delete(cache);}catch(RuntimeException ignored){}
            try {
                if(!lastRequest.isEmpty()){progress.put("failed_request",lastRequest);store.progress(task,progress);}
                var taskState=store.task(account,task);
                store.finish(task,taskState.insertedCount()>0?"PARTIAL":"FAILED",failedStage,reason,code);
            } catch(RuntimeException ignored) { /* 数据库/Redis 不可用时，后续状态查询按锁租期标记中断。 */ }
        } finally {release(account,task);}
    }
    private List<WishRecord> page(long account,String task,String digest,long deadline,String key,WishRoleRequest role,String pool,String cursor) throws InterruptedException {
        for(int attempt=0;;attempt++) {
            try {return upstream.page(key,role,pool,cursor);}
            catch(BusinessException ex) {
                if(attempt>=2 || !"upstream_rate_limited".equals(ex.getDetails().get("reason")))throw ex;
                Thread.sleep(2000L*(attempt+1));
                check(account,task,digest,deadline);
            }
        }
    }
    private void check(long account,String task,String digest,long deadline) {
        current(digest);
        if(Thread.currentThread().isInterrupted() || System.nanoTime()>deadline) throw new BusinessException(409,"祈愿任务已中断",Map.of("reason","wish_task_interrupted"));
        if(!Objects.equals(1L,redis.execute(RENEW,List.of(lock(account)),task))) throw new BusinessException(409,"祈愿任务锁失效",Map.of("reason","wish_lock_lost"));
        if(store.cancelled(task)) throw new BusinessException(409,"祈愿任务已取消",Map.of("reason","wish_cancelled"));
    }
    private WorkbenchSecret current(String digest) {
        var session=sessions.read(digest,false);
        if(session==null) throw new BusinessException(401,"工作台会话已退出或过期",Map.of("reason","workbench_session_expired"));
        return cipher.decrypt(session.encrypted(),digest);
    }
    @Override
    public WishTask status(WorkbenchSecret secret,WishTaskRequest query) {
        long account=authorize(secret,query.role());
        return database(()->{
            WishTask task=store.task(account,query.taskId());
            if("RUNNING".equals(task.status()) && !query.taskId().equals(redis.opsForValue().get(lock(account)))) {
                store.finish(query.taskId(),"INTERRUPTED","genshin_wishes","wish_task_interrupted",null);
                task=store.task(account,query.taskId());
            }
            return task;
        });
    }
    @Override
    public WishTask cancel(WorkbenchSecret secret,WishTaskRequest query) {
        long account=authorize(secret,query.role());
        return database(()->{store.task(account,query.taskId());store.cancel(account,query.taskId());return store.task(account,query.taskId());});
    }
    @Override
    public WishPage records(WorkbenchSecret secret,WishRecordsRequest query) {
        long account=authorize(secret,query.role());
        try {
            if(query.fromDate()!=null) LocalDate.parse(query.fromDate());
            if(query.toDate()!=null) LocalDate.parse(query.toDate());
            if(query.fromDate()!=null && query.toDate()!=null && query.fromDate().compareTo(query.toDate())>0) throw WishCodec.invalid();
        } catch(DateTimeException ex){throw WishCodec.invalid();}
        return database(()->store.records(account,query));
    }
    @Override
    public WishSummary summary(WorkbenchSecret secret,WishRoleRequest role) {
        long account=authorize(secret,role);
        return database(()->analyze(store.all(account),store.latest(account)));
    }
    public static WishSummary analyze(List<WishRecord> records,WishTask latest) {
        List<WishPoolSummary> pools=new ArrayList<>();
        for(String group:POOLS) {
            List<WishRecord> list=records.stream().filter(r->group.equals(r.poolGroup())).sorted(Comparator.comparing(r->new BigInteger(r.id()))).toList();
            int five=0,four=0,since=0,intervalSum=0,intervalCount=0; boolean seenFive=false;
            List<WishFiveStar> history=new ArrayList<>();
            for(WishRecord r:list) {
                since++;
                if(r.rankType()==4) four++;
                if(r.rankType()==5) {
                    five++; Integer interval=seenFive?since:null;
                    if(interval!=null){intervalSum+=interval;intervalCount++;}
                    history.add(new WishFiveStar(r.id(),r.name(),r.time(),interval));seenFive=true;since=0;
                }
            }
            Collections.reverse(history);
            pools.add(new WishPoolSummary(group,list.size(),five,four,seenFive?since:null,intervalCount==0?null:Math.round(intervalSum*100.0/intervalCount)/100.0,
                    list.isEmpty()?null:list.get(0).time(),list.isEmpty()?null:list.get(list.size()-1).time(),false,List.copyOf(history.subList(0,Math.min(50,history.size())))));
        }
        return new WishSummary(records.size(),pools,latest);
    }
    @Override
    public WishTask importRecords(WorkbenchSecret secret,WishImportRequest query) {
        long account=authorize(secret,query.role());
        List<WishRecord> records=WishCodec.importRecords(query.document(),query.role());
        String task=UUID.randomUUID().toString();acquire(account,task);
        try {
            store.begin(account,task,"IMPORT");
            // 完整校验后，单事务幂等写入，冲突会回滚整次导入。
            store.save(account,task,records,Map.of("imported",true,"history_complete",false));
            store.finish(task,"SUCCESS",null,null,null);return store.task(account,task);
        } catch(RuntimeException ex){try{store.finish(task,"FAILED","genshin_wish_import","wish_import_failed",null);}catch(RuntimeException ignored){}throw safe(ex);}
        finally{release(account,task);}
    }
    @Override
    public Map<String,Object> export(WorkbenchSecret secret,WishRoleRequest role) {
        long account=authorize(secret,role);
        return database(()->{
            List<Map<String,String>> list=store.all(account).stream().map(r->{Map<String,String> m=new LinkedHashMap<>();
                m.put("id",r.id());m.put("gacha_type",r.gachaType());m.put("uigf_gacha_type",r.poolGroup());m.put("item_id",r.itemId());m.put("name",r.name());m.put("item_type",r.itemType());m.put("rank_type",Integer.toString(r.rankType()));m.put("count",Integer.toString(r.count()));m.put("time",r.time());return m;}).toList();
            return Map.of("info",Map.of("version","v4.2","export_app","Niangao-mihoyoApi","export_app_version","0.1.0","export_timestamp",Instant.now().getEpochSecond()),
                    "hk4e",List.of(Map.of("uid",role.gameUid(),"timezone",8,"lang","zh-cn","list",list)));
        });
    }
    private String lock(long account){return prefix+"lock:"+account;}
    private void acquire(long account,String task) {
        if(!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lock(account),task,Duration.ofSeconds(120)))) throw new BusinessException(409,"当前账号已有祈愿任务，请等待或取消后重试");
        String cooldown=prefix+"cooldown:"+account;
        if(!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(cooldown,task,Duration.ofSeconds(10)))) {release(account,task);throw new BusinessException(429,"祈愿操作过于频繁，请等待 10 秒");}
    }
    private void release(long account,String task){try{redis.execute(RELEASE,List.of(lock(account)),task);}catch(RuntimeException ignored){}}
    private <T>T database(Supplier<T> action){try{return action.get();}catch(DataAccessException ex){throw storageFailure();}}
    private RuntimeException safe(RuntimeException ex){return ex instanceof DataAccessException?storageFailure():ex;}
    private BusinessException storageFailure(){return new BusinessException(503,"祈愿数据库暂不可用，请先执行 020 建表脚本；已有数据保留",Map.of("reason","wish_storage_unavailable"));}
}
