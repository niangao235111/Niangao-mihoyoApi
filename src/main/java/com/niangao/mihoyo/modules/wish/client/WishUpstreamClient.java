package com.niangao.mihoyo.modules.wish.client;

import com.niangao.mihoyo.modules.wish.dto.WishRoleRequest;
import com.niangao.mihoyo.modules.wish.entity.WishRecord;
import com.niangao.mihoyo.modules.wish.util.WishCodec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.*;
import com.niangao.mihoyo.common.exception.BusinessException;
import com.niangao.mihoyo.modules.genshin.service.GenshinDeviceService;
import com.niangao.mihoyo.modules.mihoyo.client.*;
import com.niangao.mihoyo.modules.mihoyo.client.model.UpstreamReply;
import com.niangao.mihoyo.modules.mihoyo.util.*;
import com.niangao.mihoyo.modules.workbench.entity.WorkbenchSecret;
import io.clientcore.core.http.models.HttpHeaderName;
import io.clientcore.core.http.models.HttpRequest;
import io.clientcore.core.http.models.HttpMethod;
import io.clientcore.core.models.binarydata.BinaryData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uigf.mihoyo.game.AuthKeyRequest;
import uigf.mihoyo.game.genshin.gacha.GachaClientBuilder;
import java.util.*;
import java.util.function.Consumer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** 只使用固定配置的上游地址；不记录异常对象，避免 authkey 查询串泄露。 */
@Component
public class WishUpstreamClient {
    private final SdkTransport transport;
    private final GenshinDeviceService devices;
    private final ObjectMapper json;
    private final String authEndpoint, historyEndpoint, salt, appVersion;
    public WishUpstreamClient(SdkTransport transport, GenshinDeviceService devices, ObjectMapper json,
            @Value("${genshin.wishes.auth-endpoint:https://api-takumi.miyoushe.com}") String authEndpoint,
            @Value("${genshin.wishes.history-endpoint:https://public-operation-hk4e.mihoyo.com}") String historyEndpoint,
            @Value("${genshin.wishes.auth-ds-salt:EJncUPGnOHajenjLhBOsdpwEMZmiCmQX}") String salt,
            @Value("${genshin.wishes.auth-app-version:2.71.1}") String appVersion) {
        this.transport=transport; this.devices=devices; this.json=json;
        this.authEndpoint=authEndpoint; this.historyEndpoint=historyEndpoint; this.salt=salt;this.appVersion=appVersion;
    }
    public String authorize(WorkbenchSecret secret,WishRoleRequest role) {
        String device=secret.deviceId() == null ? devices.deviceId(secret.stuid(),secret.mid())
                : devices.deviceId(secret.stuid(),secret.mid(),secret.deviceId());
        var reply=new UpstreamReply();
        // AuthKey 生成不自动重试；DS1 使用 SDK 的对应操作元数据。
        var http=transport.create(device,reply,true,null,262144,h -> {
            h.set(HttpHeaderName.fromString("x-rpc-client_type"),"5");
            h.set(HttpHeaderName.fromString("x-rpc-app_version"),appVersion);
            h.set(HttpHeaderName.fromString("X-Requested-With"),"com.mihoyo.hyperion");
            h.set(HttpHeaderName.ORIGIN,"https://webstatic.mihoyo.com");
            h.set(HttpHeaderName.REFERER,"https://webstatic.mihoyo.com/");
            h.set(HttpHeaderName.USER_AGENT,"Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Mobile miHoYoBBS/"+appVersion);
        });
        CookieUtil.validate(secret.stoken(),"stoken"); CookieUtil.validate(secret.mid(),"mid");
        // 固定 SDK 的 AuthKeyApiClient 实际指向 AuthKey A 的旧路径，不能用于 B。
        // 仍复用有超时、TLS、响应上限和脱敏的 transport，仅显式构造文档中的 B 请求。
        var request=new HttpRequest().setMethod(HttpMethod.POST).setUri(authEndpoint+"/binding/api/genAuthKey");
        request.getHeaders().set(HttpHeaderName.CONTENT_TYPE,"application/json")
                .set(HttpHeaderName.COOKIE,CookieUtil.stoken(secret.stoken(),secret.stuid(),secret.mid()))
                .set(HttpHeaderName.fromString("DS"),ds());
        request.setBody(BinaryData.fromObject(new AuthKeyRequest("webview_gacha","hk4e_cn",Long.parseLong(role.gameUid()),role.region())));
        JsonObject data=invoke("genshin_wish_auth",reply,ignored -> http.send(request));
        String key=ProtocolJsonUtil.optional(data,"authkey");
        if(key==null || key.isBlank() || key.length()>8192) throw failure("genshin_wish_auth",502,"invalid_upstream_response",null);
        try { key=java.net.URLDecoder.decode(key.replace("+","%2B"),StandardCharsets.UTF_8); }
        catch(IllegalArgumentException ex) { throw failure("genshin_wish_auth",502,"invalid_upstream_response",null); }
        if(key.isBlank() || key.chars().anyMatch(c->c<33 || c>126)) throw failure("genshin_wish_auth",502,"invalid_upstream_response",null);
        return key;
    }
    private String ds() {
        try {
            String t=Long.toString(java.time.Instant.now().getEpochSecond());
            String alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
            SecureRandom random=new SecureRandom();StringBuilder nonce=new StringBuilder();
            for(int i=0;i<6;i++)nonce.append(alphabet.charAt(random.nextInt(alphabet.length())));
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(("salt="+salt+"&t="+t+"&r="+nonce).getBytes(StandardCharsets.UTF_8)));
            return t+","+nonce+","+hash;
        }catch(Exception ex){throw new IllegalStateException("DS1 unavailable");}
    }
    public List<WishRecord> page(String authkey, WishRoleRequest role,String pool,String cursor) {
        var reply=new UpstreamReply();
        var http=transport.createReadOnlyPooled("genshin_wishes","niangao-wishes",reply,true,1024*1024,h -> {});
        var client=new GachaClientBuilder().endpoint(historyEndpoint).httpClient(http).httpRetryOptions(SdkTransport.noRetries())
                .httpRedirectOptions(SdkTransport.noRedirects()).httpInstrumentationOptions(SdkTransport.noLogs()).buildGachaClient();
        JsonObject data=invoke("genshin_wishes",reply,ignored -> client.getHistory(pool,authkey,"webview_gacha","zh-cn",
                null,cursor,1,20,role.region(),"hk4e_cn",null,null,pool,null,null,null));
        String region=ProtocolJsonUtil.optional(data,"region");
        if(region!=null && !role.region().equals(region)) throw failure("genshin_wishes",422,"wish_account_mismatch",null);
        var list=data.getAsJsonArray("list");
        if(list==null || list.size()>20) throw failure("genshin_wishes",502,"invalid_upstream_response",null);
        List<WishRecord> records=new ArrayList<>();
        try {
            for(JsonElement item:list) {
                JsonObject object=item.getAsJsonObject();
                if(!role.gameUid().equals(ProtocolJsonUtil.optional(object,"uid"))) throw failure("genshin_wishes",422,"wish_account_mismatch",null);
                WishRecord record=WishCodec.record(json.readTree(object.toString()),"UPSTREAM");
                if(!pool.equals(record.poolGroup())) throw failure("genshin_wishes",502,"invalid_upstream_response",null);
                records.add(record);
            }
        } catch(BusinessException ex) { if(ex.getStatus()==400) throw failure("genshin_wishes",502,"invalid_upstream_response",null); throw ex; }
        catch(Exception ex) { throw failure("genshin_wishes",502,"invalid_upstream_response",null); }
        return records;
    }
    private JsonObject invoke(String stage,UpstreamReply reply,Consumer<Void> operation) {
        try { operation.accept(null); }
        catch(RuntimeException ex) { if(reply.getBody()==null) throw ReadOnlyRetry.failure(stage,ex); }
        if(reply.getHttpStatus()==429) throw failure(stage,429,"upstream_rate_limited",null);
        try { SdkTransport.validateReply(reply); }
        catch(RuntimeException ex) { throw failure(stage,502,"invalid_upstream_response",null); }
        int code=ProtocolJsonUtil.integer(reply.getBody(),"retcode");
        if(code!=0) throw failure(stage,422,code==-101 || code==-100 ? "wish_authorization_expired" : "upstream_rejected",code);
        JsonElement data=reply.getBody().get("data");
        if(data==null || !data.isJsonObject()) throw failure(stage,502,"invalid_upstream_response",null);
        return data.getAsJsonObject();
    }
    private BusinessException failure(String stage,int status,String reason,Integer code) {
        Map<String,Object> details=new HashMap<>(); details.put("stage",stage); details.put("reason",reason);
        if(code!=null) details.put("upstream_retcode",code);
        return new BusinessException(status,"祈愿请求未完成，请检查授权或稍后重试；已保存记录保留",details);
    }
}
