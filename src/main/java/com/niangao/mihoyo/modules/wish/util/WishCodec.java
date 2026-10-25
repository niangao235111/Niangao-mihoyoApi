package com.niangao.mihoyo.modules.wish.util;

import com.niangao.mihoyo.modules.wish.dto.WishRoleRequest;
import com.niangao.mihoyo.modules.wish.entity.WishRecord;
import static com.niangao.mihoyo.modules.wish.util.WishPools.POOLS;

import com.fasterxml.jackson.databind.JsonNode;
import com.niangao.mihoyo.common.exception.BusinessException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.DateTimeException;
import java.time.format.*;
import java.util.*;

/** 协议校验独立于数据库；链接仅解析，从不向用户给定地址发请求。 */
public final class WishCodec {
    private WishCodec() {}
    public static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    private static final Set<String> HOSTS = Set.of("public-operation-hk4e.mihoyo.com", "hk4e-api.mihoyo.com", "webstatic.mihoyo.com", "webstatic.miyoushe.com");
    public static BusinessException invalid() { return new BusinessException(400, "祈愿数据格式不正确或与所选国服账号不匹配"); }
    public static String authkey(String url, WishRoleRequest role) {
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !HOSTS.contains(uri.getHost()) || uri.getUserInfo()!=null || uri.getPort()!=-1) throw invalid();
            String raw = uri.getRawQuery();
            if (raw == null && uri.getRawFragment()!=null && uri.getRawFragment().contains("?")) raw=uri.getRawFragment().substring(uri.getRawFragment().indexOf('?')+1);
            Map<String,String> values = new HashMap<>();
            if (raw == null) throw invalid();
            for (String pair : raw.split("&")) {
                String[] parts=pair.split("=",2);
                String key=URLDecoder.decode(parts[0],StandardCharsets.UTF_8);
                String value=parts.length==2?URLDecoder.decode(parts[1],StandardCharsets.UTF_8):"";
                if(values.putIfAbsent(key,value)!=null) throw invalid();
            }
            if (!"webview_gacha".equals(values.get("auth_appid"))) throw invalid();
            if(values.containsKey("region") && !role.region().equals(values.get("region"))) throw invalid();
            if(values.containsKey("game_biz") && !"hk4e_cn".equals(values.get("game_biz"))) throw invalid();
            String key=values.get("authkey");
            if(key==null || key.isBlank() || key.length()>8192 || key.chars().anyMatch(c->c<33 || c>126)) throw invalid();
            return key;
        } catch (RuntimeException ex) { throw invalid(); }
    }
    public static String text(JsonNode node,String field,int max,boolean empty) {
        JsonNode value=node.get(field);
        if(value==null || !value.isTextual() || value.asText().length()>max || (!empty && value.asText().isBlank()) || value.asText().chars().anyMatch(c->c<32)) throw invalid();
        return value.asText();
    }
    public static WishRecord record(JsonNode node, String source) {
        String id=text(node,"id",19,false), type=text(node,"gacha_type",4,false);
        if(!id.matches("[1-9][0-9]{0,18}") || !(POOLS.contains(type)||"400".equals(type))) throw invalid();
        String group="400".equals(type)?"301":type;
        if(node.has("uigf_gacha_type") && !group.equals(text(node,"uigf_gacha_type",4,false))) throw invalid();
        int rank=integer(node,"rank_type"), count=integer(node,"count");
        if(rank<3 || rank>5 || count!=1) throw invalid();
        String time=text(node,"time",19,false);
        try { if(LocalDateTime.parse(time,TIME).getYear()<1000) throw invalid(); } catch(DateTimeException ex) { throw invalid(); }
        String item=node.has("item_id")?text(node,"item_id",20,true):"";
        if(!item.isEmpty() && !item.matches("[0-9]+")) throw invalid();
        return new WishRecord(id,type,group,item,text(node,"name",128,false),text(node,"item_type",32,false),rank,count,time,source);
    }
    private static int integer(JsonNode node,String field) {
        JsonNode n=node.get(field);
        if(n==null || !(n.isTextual()||n.isIntegralNumber()) || !n.asText().matches("[0-9]{1,3}")) throw invalid();
        return Integer.parseInt(n.asText());
    }
    public static List<WishRecord> importRecords(JsonNode document,WishRoleRequest role) {
        if(!document.isObject() || !"v4.2".equals(document.path("info").path("version").asText())) throw new BusinessException(400,"目前仅支持 UIGF v4.2 原神记录");
        JsonNode accounts=document.path("hk4e");
        if(!accounts.isArray() || accounts.size()!=1) throw new BusinessException(400,"请导入仅包含当前所选原神账号的 UIGF 文件");
        JsonNode account=accounts.get(0);
        if(!role.gameUid().equals(account.path("uid").asText()) || !account.path("timezone").isIntegralNumber() || account.path("timezone").asInt()!=8) throw invalid();
        JsonNode list=account.path("list");
        if(!list.isArray() || list.size()>20000) throw new BusinessException(413,"单次最多导入 20000 条祈愿记录");
        Map<String,WishRecord> unique=new LinkedHashMap<>();
        List<WishRecord> records=new ArrayList<>();
        for(JsonNode item:list) {
            if(item.has("uid") && !role.gameUid().equals(item.path("uid").asText())) throw invalid();
            WishRecord record=record(item,"IMPORT");
            WishRecord old=unique.putIfAbsent(record.id(),record);
            if(old!=null && !old.equals(record)) throw new BusinessException(409,"导入文件存在冲突的记录 ID");
            records.add(record);
        }
        return List.copyOf(records);
    }
}
