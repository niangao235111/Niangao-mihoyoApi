package com.niangao.mihoyo.modules.genshin.service;

import com.google.gson.*;
import com.niangao.mihoyo.common.exception.BusinessException;
import com.niangao.mihoyo.modules.genshin.client.GenshinRecordClient;
import com.niangao.mihoyo.modules.genshin.dto.GenshinRecordRequest;
import com.niangao.mihoyo.modules.genshin.vo.GenshinArchive.*;
import com.niangao.mihoyo.modules.genshin.vo.GenshinArchive.Character;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.time.Instant;
import java.util.*;

/** 将米游社战绩转换成稳定的网页档案字段，缺失数据保持 null，不伪造成零。 */
@Service
public class GenshinArchiveService {
    private final GenshinRecordClient client;

    public GenshinArchiveService(GenshinRecordClient client) { this.client = client; }

    public Overview overview(GenshinRecordRequest request) {
        return overview(request, null);
    }
    public Overview overview(GenshinRecordRequest request, String challenge) {
        JsonObject data = client.overview(request, challenge);
        JsonObject stats = object(data, "stats");
        if (!data.has("stats") || !data.get("stats").isJsonObject()) {
            throw new BusinessException(502, "原神总览响应缺少统计数据");
        }
        JsonObject role = object(data, "role"), theater = object(stats, "role_combat");
        List<Metric> extra = new ArrayList<>();
        String[][] fields = {
            {"avatar_number", "拥有角色"}, {"full_fetter_avatar_num", "满好感角色"},
            {"way_point_number", "传送锚点"}, {"domain_number", "解锁秘境"},
            {"anemoculus_number", "风神瞳"}, {"geoculus_number", "岩神瞳"},
            {"electroculus_number", "雷神瞳"}, {"dendroculus_number", "草神瞳"},
            {"hydroculus_number", "水神瞳"}, {"pyroculus_number", "火神瞳"},
            {"moonoculus_number", "月神瞳"}, {"common_chest_number", "普通宝箱"},
            {"exquisite_chest_number", "精致宝箱"}, {"precious_chest_number", "珍贵宝箱"},
            {"luxurious_chest_number", "华丽宝箱"}, {"magic_chest_number", "奇馈宝箱"}
        };
        for (String[] field : fields) {
            Integer value = number(stats, field[0]);
            if (value != null) extra.add(new Metric(field[0], field[1], value));
        }
        List<Exploration> explorations = new ArrayList<>();
        for (JsonElement item : array(data, "world_explorations")) {
            if (!item.isJsonObject()) continue;
            JsonObject area = item.getAsJsonObject();
            Integer percentage = number(area, "exploration_percentage");
            explorations.add(new Exploration(text(area, "name"),
                    percentage == null ? null : percentage / 10.0, number(area, "level")));
        }
        return new Overview(request.gameUid(), text(role, "nickname"), number(role, "level"),
                image(text(role, "avatar_url")), number(stats, "achievement_number"),
                number(stats, "active_day_number"), text(stats, "spiral_abyss"),
                new Theater(number(theater, "max_round_id"), bool(theater, "is_unlock"), bool(theater, "has_data")),
                List.copyOf(extra), List.copyOf(explorations), Instant.now());
    }

    public Characters characters(GenshinRecordRequest request, boolean detailed) {
        return characters(request, detailed, null);
    }
    public Characters characters(GenshinRecordRequest request, boolean detailed, String challenge) {
        JsonObject data = detailed ? client.details(request, challenge) : client.characters(request, challenge);
        if (!data.has("list") || !data.get("list").isJsonArray()) {
            throw new BusinessException(502, "原神角色响应缺少列表");
        }
        JsonObject propertyMap = object(data, "property_map");
        Map<String, Character> result = new LinkedHashMap<>();
        Set<String> requested = request.characterIds() == null ? Set.of() : new HashSet<>(request.characterIds());
        for (JsonElement item : array(data, "list")) {
            if (!item.isJsonObject()) throw new BusinessException(502, "原神角色条目格式异常");
            JsonObject raw = item.getAsJsonObject();
            JsonObject base = raw.has("base") ? object(raw, "base") : raw;
            String id = text(base, "id");
            if (id == null || !id.matches("[0-9]{1,10}")) throw new BusinessException(502, "上游角色 ID 异常");
            if (id.equals("0")) continue;
            if (detailed && !requested.contains(id)) throw new BusinessException(502, "上游返回了未请求的角色");
            JsonObject weapon = object(raw, "weapon");
            if (weapon.isEmpty()) weapon = object(base, "weapon");
            List<Skill> skills = new ArrayList<>();
            for (JsonElement element : array(raw, "skills")) {
                if (!element.isJsonObject()) continue;
                JsonObject skill = element.getAsJsonObject();
                skills.add(new Skill(text(skill, "skill_id"), text(skill, "name"), number(skill, "level"),
                        number(skill, "skill_type"), image(text(skill, "icon"))));
            }
            List<Artifact> artifacts = new ArrayList<>();
            for (JsonElement element : array(raw, "relics")) {
                if (!element.isJsonObject()) continue;
                JsonObject relic = element.getAsJsonObject();
                List<Property> sub = new ArrayList<>();
                for (JsonElement prop : array(relic, "sub_property_list")) {
                    if (prop.isJsonObject()) sub.add(property(prop.getAsJsonObject(), propertyMap));
                }
                artifacts.add(new Artifact(text(relic, "id"), text(relic, "name"), image(text(relic, "icon")),
                        number(relic, "rarity"), number(relic, "level"), number(relic, "pos"),
                        text(relic, "pos_name"), text(object(relic, "set"), "name"),
                        property(object(relic, "main_property"), propertyMap), List.copyOf(sub)));
            }
            Map<String, Property> props = new LinkedHashMap<>();
            for (String key : List.of("selected_properties", "base_properties", "extra_properties", "element_properties")) {
                for (JsonElement element : array(raw, key)) {
                    if (!element.isJsonObject()) continue;
                    Property prop = property(element.getAsJsonObject(), propertyMap);
                    if (prop != null) props.putIfAbsent(prop.type(), prop);
                }
            }
            result.put(id, new Character(id, text(base, "name"), image(text(base, "icon")),
                    text(base, "element"), number(base, "rarity"), number(base, "level"),
                    number(base, "actived_constellation_num"), number(base, "fetter"),
                    weapon.isEmpty() ? null : new Weapon(text(weapon, "id"), text(weapon, "name"), image(text(weapon, "icon")),
                            number(weapon, "rarity"), number(weapon, "level"), number(weapon, "affix_level"),
                            property(object(weapon, "main_property"), propertyMap), property(object(weapon, "sub_property"), propertyMap)),
                    List.copyOf(skills), List.copyOf(artifacts), List.copyOf(props.values()), detailed));
        }
        return new Characters(request.gameUid(), result.size(), List.copyOf(result.values()), Instant.now());
    }

    private static Property property(JsonObject raw, JsonObject map) {
        if (raw.isEmpty()) return null;
        String type = text(raw, "property_type");
        if (type == null) return null;
        JsonObject info = object(map, type);
        String value = text(raw, "final");
        if (value == null) value = text(raw, "value");
        return new Property(type, text(info, "name"), value);
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static JsonArray array(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    private static String text(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonPrimitive()) throw new BusinessException(502, "原神战绩字段类型异常");
        return value.getAsString();
    }

    private static Integer number(JsonObject parent, String key) {
        String value = text(parent, key);
        if (value == null) return null;
        try { return Integer.valueOf(value); }
        catch (NumberFormatException ex) { throw new BusinessException(502, "原神战绩数值格式异常"); }
    }

    private static Boolean bool(JsonObject parent, String key) {
        String value = text(parent, key);
        if (value == null) return null;
        if (value.equals("true") || value.equals("1")) return true;
        if (value.equals("false") || value.equals("0")) return false;
        throw new BusinessException(502, "原神战绩标记格式异常");
    }

    private static String image(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || host == null || uri.getUserInfo() != null) return null;
            if (host.endsWith(".mihoyo.com") || host.endsWith(".miyoushe.com")
                    || host.endsWith(".hoyoverse.com") || host.endsWith(".hoyolab.com")) return value;
        } catch (IllegalArgumentException ignored) { }
        return null;
    }
}
