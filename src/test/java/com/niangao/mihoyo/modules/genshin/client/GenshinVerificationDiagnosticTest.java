package com.niangao.mihoyo.modules.genshin.client;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.List;
import java.util.Map;

class GenshinVerificationDiagnosticTest {
    @Test void taskCookiesFollowDomainPathExpiryAndRemainIsolated() {
        URI source = URI.create("https://record.example.com/game_record/card/wapi/createVerification");
        URI target = URI.create("https://record.example.com/game_record/card/wapi/verifyVerification");
        String first = GenshinVerificationClient.taskCookies("ltoken_v2=user-a; session=old; expired=old", source,
                Map.of("Set-Cookie", List.of(
                        "session=new-a; Path=/; Secure; HttpOnly",
                        "task=task-a; Path=/game_record/card/wapi; Secure",
                        "foreign=secret; Domain=unrelated.example.org; Path=/",
                        "other_path=secret; Path=/other",
                        "expired=secret; Max-Age=0; Path=/")), target);
        assertTrue(first.contains("ltoken_v2=user-a"));
        assertTrue(first.contains("session=new-a"));
        assertTrue(first.contains("task=task-a"));
        for (String excluded : List.of("session=old", "foreign=", "other_path=", "expired="))
            assertFalse(first.contains(excluded), first);
        String second = GenshinVerificationClient.taskCookies("ltoken_v2=user-b", source, Map.of(), target);
        assertEquals("ltoken_v2=user-b", second);
    }

    @Test void alternateMsgFieldIsReadWithoutExposingIdentifiers() {
        var result = new JsonObject();
        result.addProperty("msg", "验证码校验失败，secret-token");
        assertTrue(GenshinVerificationClient.diagnosticMessage(result).contains("验证码校验失败"));
        assertFalse(GenshinVerificationClient.diagnosticMessage(result).contains("secret"));
    }
    @Test void errorMessageRetainsChineseExplanationWithoutEchoedCredentialsOrLogInjection() {
        var result = new JsonObject();
        result.addProperty("message", "验证失败，字段：fixture-ltoken、123456789、https://example.com/secret\r\nCookie=secret");
        String message = GenshinVerificationClient.diagnosticMessage(result);
        assertTrue(message.contains("验证失败"));
        for (String secret : new String[]{"fixture", "123456789", "example", "secret", "Cookie", "\r", "\n"})
            assertFalse(message.contains(secret));
    }

    @Test void missingStructuredOrOversizedMessagesAreNotLoggedVerbatim() {
        var result = new JsonObject();
        assertEquals("unavailable", GenshinVerificationClient.diagnosticMessage(result));
        result.add("message", new JsonObject());
        assertEquals("unavailable", GenshinVerificationClient.diagnosticMessage(result));
        result.addProperty("message", "密".repeat(257));
        assertEquals("redacted", GenshinVerificationClient.diagnosticMessage(result));
    }
}
