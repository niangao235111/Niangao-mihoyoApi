package com.niangao.mihoyo.modules.wish.vo;



public record WishTask(String taskId, String kind, String status, boolean cancelRequested,
                       int fetchedCount, int insertedCount, int duplicateCount, Object progress,
                       String errorStage, String errorReason, Integer upstreamRetcode,
                       String createdAt, String updatedAt, String finishedAt) {}
