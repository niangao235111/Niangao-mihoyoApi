package com.niangao.mihoyo.modules.wish.service;

import com.niangao.mihoyo.modules.wish.dto.WishImportRequest;
import com.niangao.mihoyo.modules.wish.dto.WishRecordsRequest;
import com.niangao.mihoyo.modules.wish.dto.WishRoleRequest;
import com.niangao.mihoyo.modules.wish.dto.WishSyncRequest;
import com.niangao.mihoyo.modules.wish.dto.WishTaskRequest;
import com.niangao.mihoyo.modules.wish.vo.WishPage;
import com.niangao.mihoyo.modules.wish.vo.WishSummary;
import com.niangao.mihoyo.modules.wish.vo.WishTask;

import com.niangao.mihoyo.modules.workbench.entity.WorkbenchSecret;
import java.util.Map;

/** 祈愿业务入口；Controller 仅依赖此契约，存储由 Mapper 处理。 */
public interface WishService {
    WishTask sync(WorkbenchSecret secret, String sessionDigest, WishSyncRequest request);
    WishTask status(WorkbenchSecret secret, WishTaskRequest request);
    WishTask cancel(WorkbenchSecret secret, WishTaskRequest request);
    WishPage records(WorkbenchSecret secret, WishRecordsRequest request);
    WishSummary summary(WorkbenchSecret secret, WishRoleRequest role);
    WishTask importRecords(WorkbenchSecret secret, WishImportRequest request);
    Map<String, Object> export(WorkbenchSecret secret, WishRoleRequest role);
}
