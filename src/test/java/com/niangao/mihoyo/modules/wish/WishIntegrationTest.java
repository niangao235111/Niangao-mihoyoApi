package com.niangao.mihoyo.modules.wish;

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
import com.niangao.mihoyo.modules.wish.vo.WishTask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.niangao.mihoyo.common.exception.BusinessException;
import com.niangao.mihoyo.modules.mihoyo.service.MihoyoGameAccountService;
import com.niangao.mihoyo.modules.mihoyo.dto.GameAccountsRequest;
import com.niangao.mihoyo.modules.mihoyo.vo.*;
import com.niangao.mihoyo.modules.workbench.entity.WorkbenchSecret;
import com.niangao.mihoyo.modules.workbench.service.WorkbenchSessionService;
import com.niangao.mihoyo.modules.workbench.mapper.WorkbenchSessionMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 真实本地 MySQL / Redis，随机账号；上游全部 mock，不发送真实短信。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "mihoyo.redis.phone-key-secret=wish-test-only","genshin.wishes.page-delay-ms=0",
        "workbench.session.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WishIntegrationTest {
    @Autowired WishService service;
    @Autowired WishMapper store;
    @Autowired JdbcTemplate db;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper json;
    @Autowired WorkbenchSessionService sessionService;
    @Autowired WorkbenchSessionMapper sessions;
    @MockBean WishUpstreamClient upstream;
    @MockBean MihoyoGameAccountService accounts;
    @LocalServerPort int port;
    final WishRoleRequest role=new WishRoleRequest("500000001","cn_qd01","hk4e_cn");
    String owner,digest,token,csrf;
    WorkbenchSecret secret;
    long account;
    @BeforeAll void schema() {
        new ResourceDatabasePopulator(new FileSystemResource("db/mysql/020_create_genshin_wishes.sql")).execute(Objects.requireNonNull(db.getDataSource()));
    }
    @BeforeEach void setup() {
        owner=Long.toString(ThreadLocalRandom.current().nextLong(900000000000000000L,999999999999999999L));
        secret=new WorkbenchSecret("fixture-stoken",owner,"fixture-mid",List.of(new GameAccountVO("hk4e_cn",role.gameUid(),role.region(),"世界树","测试",60,"原神",false,false)));
        when(accounts.queryAccounts(any())).thenReturn(new GameAccountsResponse("fixture",1,secret.roles()));
        var issued=sessionService.create(new GameAccountsRequest(secret.stoken(),owner,secret.mid(),null,null),null);
        token=issued.token();digest=WorkbenchSessionService.digest(token);csrf=issued.view().csrfToken();
        account=store.account(owner,role);
        when(upstream.authorize(any(),any())).thenReturn("fixture-authkey-never-log");
        when(upstream.page(anyString(),any(),anyString(),anyString())).thenReturn(List.of());
    }
    @AfterEach void cleanup() throws Exception {
        // 先等待当前账号后台工作释放锁，避免跨测试迟到写入。
        for(int i=0;i<200 && Boolean.TRUE.equals(redis.hasKey(lock()));i++)Thread.sleep(10);
        sessions.delete(digest);
        for(String key:List.of(lock(),cooldown(),"niangao:mihoyo:wishes:auth:"+digest+":"+account))redis.delete(key);
        db.update("DELETE FROM ng_genshin_wish_record WHERE account_id=?",account);
        db.update("DELETE FROM ng_genshin_wish_sync_run WHERE account_id=?",account);
        db.update("DELETE FROM ng_genshin_account WHERE id=? AND owner_stuid=?",account,owner);
    }
    String lock(){return "niangao:mihoyo:wishes:lock:"+account;}
    String cooldown(){return "niangao:mihoyo:wishes:cooldown:"+account;}
    WishTask waitTask(String id) throws Exception {
        for(int i=0;i<300;i++){WishTask task=store.task(account,id);if(!"RUNNING".equals(task.status()))return task;Thread.sleep(10);}
        throw new AssertionError("WishTask timed out");
    }
    WishRecord record(String id,String type,int rank){return new WishRecord(id,type,"400".equals(type)?"301":type,"","测试物品","角色",rank,1,"2026-10-18 12:00:00","UPSTREAM");}
    @Test void asynchronousSyncPersistsEveryPoolDeduplicatesAndExports() throws Exception {
        when(upstream.page(anyString(),eq(role),eq("301"),eq("0"))).thenReturn(List.of(record("9007199254740999","400",5),record("9007199254740998","301",3)));
        WishTask start=service.sync(secret,digest,new WishSyncRequest(role,null));
        WishTask completed=waitTask(start.taskId());
        assertEquals("SUCCESS",completed.status());
        var progress=json.valueToTree(completed.progress());
        assertEquals("complete",progress.path("stage").asText());
        for(String pool:List.of("100","200","301","302","500")) assertTrue(progress.path(pool).path("finished").asBoolean());
        assertEquals(2,progress.path("301").path("count").asInt());
        assertEquals(1,progress.path("301").path("pages").asInt());
        assertEquals(0,progress.path("100").path("count").asInt());
        assertEquals(2,service.summary(secret,role).total());
        var page=service.records(secret,new WishRecordsRequest(role,"301",null,null,1,null,null));
        assertEquals("9007199254740999",page.list().get(0).id());assertTrue(page.hasMore());
        assertEquals("2026-10-18 12:00:00",page.list().get(0).time());
        assertEquals("9007199254740998",service.records(secret,new WishRecordsRequest(role,"301",null,page.nextId(),1,null,null)).list().get(0).id());
        redis.delete(cooldown());
        WishTask second=service.sync(secret,digest,new WishSyncRequest(role,null));
        WishTask finished=waitTask(second.taskId());assertEquals("SUCCESS",finished.status(),finished.toString());assertEquals(0,finished.insertedCount());assertEquals(2,finished.duplicateCount());
        var exported=json.valueToTree(service.export(secret,role));
        assertEquals(2,WishCodec.importRecords(exported,role).size());
        assertFalse(exported.toString().contains("authkey"));assertFalse(exported.toString().contains(secret.stoken()));
    }
    @Test void partialFailureRetainsDataAndNextRunRepairsOlderGap() throws Exception {
        when(upstream.page(anyString(),eq(role),eq("301"),eq("0"))).thenReturn(List.of(record("200","301",5)));
        when(upstream.page(anyString(),eq(role),eq("301"),eq("200"))).thenThrow(new BusinessException(502,"sensitive upstream URL",Map.of("stage","genshin_wishes","reason","upstream_network_error")));
        WishTask start=service.sync(secret,digest,new WishSyncRequest(role,null));assertEquals("PARTIAL",waitTask(start.taskId()).status());
        assertEquals(1,service.summary(secret,role).total());
        assertFalse(json.writeValueAsString(store.task(account,start.taskId())).contains("sensitive"));
        redis.delete(cooldown());
        when(upstream.page(anyString(),eq(role),eq("301"),eq("200"))).thenReturn(List.of(record("199","400",4)));
        WishTask retry=service.sync(secret,digest,new WishSyncRequest(role,null));WishTask finished=waitTask(retry.taskId());assertEquals("SUCCESS",finished.status(),finished.toString());assertEquals(2,service.summary(secret,role).total());
    }
    @Test void cancellationPreventsLatePageWriteAndConcurrentAccountJob() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(upstream.page(anyString(),eq(role),eq("100"),eq("0"))).thenAnswer(i->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return List.of(record("1","100",5));});
        WishTask task=service.sync(secret,digest,new WishSyncRequest(role,null));assertTrue(entered.await(5,TimeUnit.SECONDS));
        try {
            assertEquals(409,assertThrows(BusinessException.class,()->service.sync(secret,digest,new WishSyncRequest(role,null))).getStatus());
            service.cancel(secret,new WishTaskRequest(role,task.taskId()));
        }finally{release.countDown();}
        assertEquals("CANCELLED",waitTask(task.taskId()).status());assertEquals(0,service.summary(secret,role).total());
    }
    @Test void importIsAtomicOnConflictAndNeverOverwritesOldRecords() throws Exception {
        String task=UUID.randomUUID().toString();store.begin(account,task,"IMPORT");store.save(account,task,List.of(record("20","301",5)),Map.of());store.finish(task,"SUCCESS",null,null,null);
        var data=json.valueToTree(service.export(secret,role));
        var list=(com.fasterxml.jackson.databind.node.ArrayNode)data.path("hk4e").get(0).path("list");
        var old=list.get(0).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)old).put("name","冲突");
        var extra=list.get(0).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)extra).put("id","21");
        list.removeAll();list.add(extra);list.add(old);
        assertEquals(409,assertThrows(BusinessException.class,()->service.importRecords(secret,new WishImportRequest(role,data))).getStatus());
        assertEquals(1,service.summary(secret,role).total());assertEquals("测试物品",store.all(account).get(0).name());
    }
    @Test void wrongOwnerAndStaleTasksAreIsolated() {
        String task=UUID.randomUUID().toString();store.begin(account,task,"SYNC");
        assertEquals("INTERRUPTED",service.status(secret,new WishTaskRequest(role,task)).status());
        var other=new WorkbenchSecret("fixture","other-owner","fixture",List.of());
        assertEquals(403,assertThrows(BusinessException.class,()->service.status(other,new WishTaskRequest(role,task))).getStatus());
    }
    @Test void logoutDuringRequestPreventsLateRecordsAndDoesNotReviveSession() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(upstream.page(anyString(),eq(role),eq("100"),eq("0"))).thenAnswer(i->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return List.of(record("8","100",5));});
        WishTask task=service.sync(secret,digest,new WishSyncRequest(role,null));assertTrue(entered.await(5,TimeUnit.SECONDS));
        sessions.delete(digest);release.countDown();
        WishTask finished=waitTask(task.taskId());assertEquals("FAILED",finished.status());assertEquals("workbench_session_expired",finished.errorReason());
        assertEquals(0,store.all(account).size());assertNull(sessions.read(digest,false));
    }
    @Test void manualLinkSkipsGenerationAndAuthKeyCacheIsEncrypted() throws Exception {
        String key="manual-key-sensitive";
        WishTask task=service.sync(secret,digest,new WishSyncRequest(role,"https://public-operation-hk4e.mihoyo.com/gacha_info/api/getGachaLog?auth_appid=webview_gacha&authkey="+key));
        assertEquals("SUCCESS",waitTask(task.taskId()).status());
        verify(upstream,never()).authorize(any(),any());
        verify(upstream).page(eq(key),eq(role),eq("100"),eq("0"));
        String encrypted=redis.opsForValue().get("niangao:mihoyo:wishes:auth:"+digest+":"+account);
        assertNotNull(encrypted);assertFalse(encrypted.contains(key));assertFalse(json.writeValueAsString(store.task(account,task.taskId())).contains(key));
    }
    @Test void repeatedCursorStopsWithoutSavingDuplicatePage() throws Exception {
        when(upstream.page(anyString(),eq(role),eq("100"),anyString())).thenReturn(List.of(record("20","100",5)));
        WishTask task=service.sync(secret,digest,new WishSyncRequest(role,null));WishTask done=waitTask(task.taskId());
        assertEquals("PARTIAL",done.status());assertEquals("wish_cursor_stalled",done.errorReason());assertEquals(1,done.insertedCount());
    }
    @Test void upstreamRateLimitHasBoundedBackoffInsteadOfUnboundedRetries() throws Exception {
        when(upstream.page(anyString(),eq(role),eq("100"),eq("0"))).thenThrow(new BusinessException(429,"limited",Map.of("reason","upstream_rate_limited")));
        WishTask task=service.sync(secret,digest,new WishSyncRequest(role,null));
        // 两次退避总计 6 秒，第三次失败后应停止。
        WishTask finished=null;
        for(int i=0;i<1000;i++){WishTask value=store.task(account,task.taskId());if(!"RUNNING".equals(value.status())){finished=value;break;}Thread.sleep(10);}
        assertNotNull(finished);assertEquals("FAILED",finished.status());assertEquals("upstream_rate_limited",finished.errorReason());
        verify(upstream,times(3)).page(anyString(),eq(role),eq("100"),eq("0"));
    }
    @Test void failureAfterTwoHundredKeepsRecordsAndNextRunCanReadBeyondThatBoundary() throws Exception {
        var fail=new java.util.concurrent.atomic.AtomicBoolean(true);
        when(upstream.page(anyString(),eq(role),eq("200"),anyString())).thenAnswer(invocation->{
            String cursor=invocation.getArgument(3);
            if("801".equals(cursor) && fail.get())throw new BusinessException(502,"network",Map.of("stage","genshin_wishes","reason","upstream_network_error"));
            long start="0".equals(cursor)?1000:Long.parseLong(cursor)-1;
            List<WishRecord> page=new ArrayList<>();
            for(long id=start;id>=741 && page.size()<20;id--)page.add(record(Long.toString(id),"200",3));
            return page;
        });
        WishTask first=service.sync(secret,digest,new WishSyncRequest(role,null));WishTask failed=waitTask(first.taskId());
        assertEquals("PARTIAL",failed.status());assertEquals(200,failed.insertedCount());
        var progress=json.valueToTree(failed.progress());
        assertEquals("200",progress.path("failed_request").path("pool").asText());
        assertEquals("801",progress.path("failed_request").path("end_id").asText());
        assertEquals(11,progress.path("failed_request").path("page").asInt());
        assertEquals(200,store.all(account).size());
        fail.set(false);redis.delete(cooldown());
        WishTask next=service.sync(secret,digest,new WishSyncRequest(role,null));WishTask success=waitTask(next.taskId());
        assertEquals("SUCCESS",success.status());assertEquals(60,success.insertedCount());assertEquals(200,success.duplicateCount());
        assertEquals(260,store.all(account).size());
    }
    @Test void httpRequiresCookieCsrfAndSourceAndOnlyImportHasLargeBodyAllowance() throws Exception {
        assertEquals(401,http("summary",json.writeValueAsString(role),null,csrf,null).statusCode());
        assertEquals(403,http("summary",json.writeValueAsString(role),token,"wrong",null).statusCode());
        assertEquals(403,http("summary",json.writeValueAsString(role),token,csrf,"https://evil.example").statusCode());
        assertEquals(200,http("summary",json.writeValueAsString(role),token,csrf,null).statusCode());
        String large=" ".repeat(17000)+json.writeValueAsString(Map.of("role",role,"document",Map.of("info",Map.of("version","v4.2"),"hk4e",List.of(Map.of("uid",role.gameUid(),"timezone",8,"list",List.of())))));
        assertEquals(413,http("sync",large,token,csrf,null).statusCode());
        assertEquals(200,http("import",large,token,csrf,null).statusCode());
        assertEquals(413,http("import"," ".repeat(5*1024*1024+1),token,csrf,null).statusCode());
    }
    HttpResponse<String> http(String path,String body,String cookie,String csrf,String origin) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/workbench/genshin/wishes/"+path)).header("Content-Type","application/json");
        if(cookie!=null)builder.header("Cookie","ng_workbench="+cookie);if(csrf!=null)builder.header("X-Workspace-CSRF",csrf);if(origin!=null)builder.header("Origin",origin);
        return HttpClient.newHttpClient().send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
}
