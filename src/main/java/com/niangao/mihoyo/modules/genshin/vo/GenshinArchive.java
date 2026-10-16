package com.niangao.mihoyo.modules.genshin.vo;

import java.time.Instant;
import java.util.List;

/** 对外档案白名单；不直接透传上游 JSON，不包含任何登录 Cookie。 */
public final class GenshinArchive {
    private GenshinArchive() {}

    public record Metric(String key, String label, Integer value) {}
    public record Exploration(String name, Double percentage, Integer level) {}
    public record Theater(Integer maxAct, Boolean unlocked, Boolean hasData) {}
    public record Overview(String uid, String nickname, Integer level, String avatar,
            Integer achievements, Integer activeDays, String spiralAbyss, Theater theater,
            List<Metric> extra, List<Exploration> explorations, Instant updatedAt) {}
    public record Property(String type, String name, String value) {}
    public record Skill(String id, String name, Integer level, Integer type, String icon) {}
    public record Weapon(String id, String name, String icon, Integer rarity, Integer level,
            Integer refinement, Property mainProperty, Property subProperty) {}
    public record Artifact(String id, String name, String icon, Integer rarity, Integer level,
            Integer position, String positionName, String setName, Property mainProperty,
            List<Property> subProperties) {}
    public record Character(String id, String name, String icon, String element, Integer rarity,
            Integer level, Integer constellation, Integer friendship, Weapon weapon,
            List<Skill> skills, List<Artifact> artifacts, List<Property> properties,
            boolean detailed) {}
    public record Characters(String uid, int count, List<Character> list, Instant updatedAt) {}
}
