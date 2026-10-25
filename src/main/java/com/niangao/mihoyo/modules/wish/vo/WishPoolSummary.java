package com.niangao.mihoyo.modules.wish.vo;

import java.util.List;

public record WishPoolSummary(String pool, int total, int fiveStars, int fourStars,
                              Integer pullsSinceLastFive, Double averageFiveInterval,
                              String earliestTime, String latestTime, boolean historyComplete,
                              List<WishFiveStar> fiveStarHistory) {}
