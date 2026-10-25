package com.niangao.mihoyo.modules.wish.entity;



public record WishRecord(String id, String gachaType, String poolGroup, String itemId, String name,
                         String itemType, int rankType, int count, String time, String source) {}
