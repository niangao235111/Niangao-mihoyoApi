package com.niangao.mihoyo.modules.genshin;

import com.google.gson.JsonObject;
import com.niangao.mihoyo.modules.genshin.client.GenshinRecordClient;
import com.niangao.mihoyo.modules.genshin.dto.GenshinRecordRequest;
import com.niangao.mihoyo.modules.genshin.service.GenshinArchiveService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GenshinAvatarTest {
    final GenshinRecordClient client = mock(GenshinRecordClient.class);
    final GenshinArchiveService service = new GenshinArchiveService(client);
    final GenshinRecordRequest request = new GenshinRecordRequest("fixture", "123", "fixture", "500000001", "cn_qd01", "hk4e_bilibili", null);
    final String avatar = "https://upload-bbs.miyoushe.com/game_record/genshin/avatar.png";

    String readAvatar(String original, String alternate) {
        JsonObject role = new JsonObject();
        if (original != null) role.addProperty("AvatarUrl", original);
        if (alternate != null) role.addProperty("avatar_url", alternate);
        JsonObject data = new JsonObject();
        data.add("role", role);
        data.add("stats", new JsonObject());
        when(client.overview(request, null)).thenReturn(data);
        return service.overview(request).avatar();
    }

    @Test void acceptsOriginalUpstreamAvatarField() { assertEquals(avatar, readAvatar(avatar, null)); }
    @Test void acceptsAlternateFieldWhenOriginalIsEmpty() { assertEquals(avatar, readAvatar("", avatar)); }
    @Test void missingAvatarRemainsAbsent() { assertNull(readAvatar("", null)); }
    @Test void preservesImageHostAndSchemeValidation() {
        assertNull(readAvatar("https://example.com/avatar.png", "http://upload-bbs.miyoushe.com/avatar.png"));
        assertNull(readAvatar("https://miyoushe.com.example.com/avatar.png", null));
    }
}
