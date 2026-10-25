package com.niangao.mihoyo.modules.wish;

import com.niangao.mihoyo.modules.wish.client.WishUpstreamClient;
import com.niangao.mihoyo.modules.wish.dto.WishRoleRequest;
import com.niangao.mihoyo.modules.wish.entity.WishRecord;
import com.niangao.mihoyo.modules.wish.service.impl.WishServiceImpl;
import com.niangao.mihoyo.modules.wish.util.WishCodec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.niangao.mihoyo.common.exception.BusinessException;
import com.niangao.mihoyo.modules.genshin.service.GenshinDeviceService;
import com.niangao.mihoyo.modules.mihoyo.client.SdkTransport;
import com.niangao.mihoyo.modules.mihoyo.config.MihoyoProperties;
import com.niangao.mihoyo.modules.workbench.entity.WorkbenchSecret;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WishProtocolTest {
    final ObjectMapper json=new ObjectMapper();
    final WishRoleRequest role=new WishRoleRequest("500000001","cn_qd01","hk4e_cn");
    @Test void historyLinkDecodesExactlyOnceAndNeverAcceptsForeignHostsOrRoles() {
        assertEquals("a+b/c=",WishCodec.authkey("https://public-operation-hk4e.mihoyo.com/gacha_info/api/getGachaLog?auth_appid=webview_gacha&authkey=a%2Bb%2Fc%3D&region=cn_qd01",role));
        for(String url:List.of("http://public-operation-hk4e.mihoyo.com/?auth_appid=webview_gacha&authkey=a","https://evil.example/?auth_appid=webview_gacha&authkey=a","https://public-operation-hk4e.mihoyo.com/?auth_appid=webview_gacha&authkey=a&authkey=b","https://public-operation-hk4e.mihoyo.com/?auth_appid=csc&authkey=a","https://public-operation-hk4e.mihoyo.com/?auth_appid=webview_gacha&authkey=a&region=cn_gf01")) assertThrows(BusinessException.class,()->WishCodec.authkey(url,role));
    }
    @Test void importValidatesVersionUidTimezonePrecisionAndConflicts() throws Exception {
        var document=json.readTree("""
                {"info":{"version":"v4.2"},"hk4e":[{"uid":"500000001","timezone":8,"list":[{"id":"1637787960000243356","gacha_type":"400","uigf_gacha_type":"301","item_id":"","name":"测试","item_type":"角色","rank_type":"5","count":"1","time":"2026-10-18 12:00:00"}]}]}
                """);
        var records=WishCodec.importRecords(document,role);
        assertEquals("1637787960000243356",records.get(0).id());assertEquals("301",records.get(0).poolGroup());
        ((com.fasterxml.jackson.databind.node.ObjectNode)document.path("hk4e").get(0)).put("timezone",-5);
        assertThrows(BusinessException.class,()->WishCodec.importRecords(document,role));
    }
    @Test void averagesExcludeTruncatedFirstIntervalAndMergeCharacterPools() {
        List<WishRecord> records=List.of(record("10",5,"301"),record("9",3,"400"),record("8",4,"301"),record("7",5,"400"),record("6",3,"301"),record("11",3,"301"));
        var summary=WishServiceImpl.analyze(records,null).pools().stream().filter(p->p.pool().equals("301")).findFirst().orElseThrow();
        assertEquals(6,summary.total());assertEquals(3.0,summary.averageFiveInterval());assertEquals(1,summary.pullsSinceLastFive());
        assertNull(summary.fiveStarHistory().get(1).interval());assertFalse(summary.historyComplete());
    }
    WishRecord record(String id,int rank,String type){return new WishRecord(id,type,"301","","测试","角色",rank,1,"2026-10-18 12:00:00","UPSTREAM");}
    @Test void sdkSendsAuthKeyBBodyDs1AndSafeHistoryPagination() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        List<String> errors=new ArrayList<>();
        server.createContext("/binding/api/genAuthKey",e->{
            try {
                var body=json.readTree(e.getRequestBody().readAllBytes());
                assertEquals("webview_gacha",body.path("auth_appid").asText());assertEquals("hk4e_cn",body.path("game_biz").asText());
                assertEquals(500000001,body.path("game_uid").asLong());assertEquals("cn_qd01",body.path("region").asText());
                assertEquals("5",e.getRequestHeaders().getFirst("x-rpc-client_type"));
                String[] ds=e.getRequestHeaders().getFirst("DS").split(",");
                assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(("salt=fixture-salt&t="+ds[0]+"&r="+ds[1]).getBytes(StandardCharsets.UTF_8))),ds[2]);
                assertTrue(e.getRequestHeaders().getFirst("Cookie").contains("stoken_v2=fixture"));
            }catch(Throwable t){errors.add(t.getClass().getSimpleName()+":"+t.getMessage());}
            byte[] reply="{\"retcode\":0,\"message\":\"OK\",\"data\":{\"authkey\":\"a%2Bb%2Fc%3D\",\"authkey_ver\":1,\"sign_type\":2}}".getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200,reply.length);e.getResponseBody().write(reply);e.close();
        });
        server.createContext("/gacha_info/api/getGachaLog",e->{
            try {
                String query=e.getRequestURI().getRawQuery();
                Map<String,String> params=new HashMap<>();for(String pair:query.split("&")){String[] p=pair.split("=",2);params.put(p[0],URLDecoder.decode(p[1],StandardCharsets.UTF_8));}
                assertEquals("a+b/c=",params.get("authkey"));assertEquals("1",params.get("authkey_ver"));assertEquals("2",params.get("sign_type"));
                assertEquals("20",params.get("size"));assertEquals("1234567890123456789",params.get("end_id"));
                assertNull(e.getRequestHeaders().getFirst("Cookie"));
            }catch(Throwable t){errors.add(t.getClass().getSimpleName()+":"+t.getMessage());}
            byte[] reply="{\"retcode\":0,\"message\":\"OK\",\"data\":{\"region\":\"cn_qd01\",\"list\":[]}}".getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200,reply.length);e.getResponseBody().write(reply);e.close();
        });
        server.start();
        try {
            var devices=mock(GenshinDeviceService.class);when(devices.deviceId(anyString(),anyString())).thenReturn("fixture-device");
            String endpoint="http://127.0.0.1:"+server.getAddress().getPort();
            var client=new WishUpstreamClient(new SdkTransport(new MihoyoProperties()),devices,json,endpoint,endpoint,"fixture-salt","2.71.1");
            assertEquals("a+b/c=",client.authorize(new WorkbenchSecret("fixture","123","fixture",List.of()),role));
            assertTrue(client.page("a+b/c=",role,"301","1234567890123456789").isEmpty());
            assertEquals(List.of(),errors);
        }finally{server.stop(0);}
    }
}
