package com.niangao.mihoyo.modules.wish.vo;

import java.util.List;

public record WishSummary(int total, List<WishPoolSummary> pools, WishTask latestTask) {}
