package com.niangao.mihoyo.modules.wish.vo;

import com.niangao.mihoyo.modules.wish.entity.WishRecord;

import java.util.List;

public record WishPage(List<WishRecord> list, String nextId, boolean hasMore) {}
