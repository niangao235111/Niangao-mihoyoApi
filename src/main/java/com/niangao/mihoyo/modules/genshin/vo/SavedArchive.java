package com.niangao.mihoyo.modules.genshin.vo;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record SavedArchive(String uid, GenshinArchive.Overview overview,
        GenshinArchive.Characters characters, Map<String, Instant> detailUpdatedAt,
        List<QueryRecord> queries) {
    public record QueryRecord(long id, String status, boolean overviewSucceeded, boolean rosterSucceeded,
            Integer expectedDetailCount, int succeededDetailCount, int failedDetailCount,
            String errorStage, Integer upstreamRetcode, String errorReason, Instant startedAt, Instant finishedAt) {}
}
