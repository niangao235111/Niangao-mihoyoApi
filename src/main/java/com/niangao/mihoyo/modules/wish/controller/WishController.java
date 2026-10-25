package com.niangao.mihoyo.modules.wish.controller;

import com.niangao.mihoyo.modules.wish.dto.WishImportRequest;
import com.niangao.mihoyo.modules.wish.dto.WishRecordsRequest;
import com.niangao.mihoyo.modules.wish.dto.WishRoleRequest;
import com.niangao.mihoyo.modules.wish.dto.WishSyncRequest;
import com.niangao.mihoyo.modules.wish.dto.WishTaskRequest;
import com.niangao.mihoyo.modules.wish.service.WishService;
import com.niangao.mihoyo.modules.wish.vo.WishPage;
import com.niangao.mihoyo.modules.wish.vo.WishSummary;
import com.niangao.mihoyo.modules.wish.vo.WishTask;

import com.niangao.mihoyo.common.result.Result;
import com.niangao.mihoyo.modules.workbench.service.WorkbenchSessionService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping(value="/api/workbench/genshin/wishes",consumes="application/json",produces="application/json")
public class WishController {
    private final WorkbenchSessionService sessions;
    private final WishService wishes;
    public WishController(WorkbenchSessionService sessions,WishService wishes){this.sessions=sessions;this.wishes=wishes;}
    @PostMapping("/sync")
    public ResponseEntity<Result<WishTask>> sync(@Valid @RequestBody WishSyncRequest query,
            @CookieValue(name="ng_workbench",required=false)String token,@RequestHeader(name="X-Workspace-CSRF",required=false)String csrf) {
        WishTask task=sessions.withSession(token,csrf,s->wishes.sync(s,WorkbenchSessionService.digest(token),query));
        return ResponseEntity.status(202).body(new Result<>(202,"祈愿同步已开始",task));
    }
    @PostMapping("/sync/status") public Result<WishTask> status(@Valid @RequestBody WishTaskRequest query,@CookieValue(name="ng_workbench",required=false)String token,@RequestHeader(name="X-Workspace-CSRF",required=false)String csrf){return Result.success(sessions.withSession(token,csrf,s->wishes.status(s,query)));}
    @PostMapping("/sync/cancel") public Result<WishTask> cancel(@Valid @RequestBody WishTaskRequest query,@CookieValue(name="ng_workbench",required=false)String token,@RequestHeader(name="X-Workspace-CSRF",required=false)String csrf){return Result.success(sessions.withSession(token,csrf,s->wishes.cancel(s,query)));}
    @PostMapping("/records") public Result<WishPage> records(@Valid @RequestBody WishRecordsRequest query,@CookieValue(name="ng_workbench",required=false)String token,@RequestHeader(name="X-Workspace-CSRF",required=false)String csrf){return Result.success(sessions.withSession(token,csrf,s->wishes.records(s,query)));}
    @PostMapping("/summary") public Result<WishSummary> summary(@Valid @RequestBody WishRoleRequest role,@CookieValue(name="ng_workbench",required=false)String token,@RequestHeader(name="X-Workspace-CSRF",required=false)String csrf){return Result.success(sessions.withSession(token,csrf,s->wishes.summary(s,role)));}
    @PostMapping("/import") public Result<WishTask> importRecords(@Valid @RequestBody WishImportRequest query,@CookieValue(name="ng_workbench",required=false)String token,@RequestHeader(name="X-Workspace-CSRF",required=false)String csrf){return Result.success(sessions.withSession(token,csrf,s->wishes.importRecords(s,query)));}
    @PostMapping("/export") public Result<Map<String,Object>> export(@Valid @RequestBody WishRoleRequest role,@CookieValue(name="ng_workbench",required=false)String token,@RequestHeader(name="X-Workspace-CSRF",required=false)String csrf){return Result.success(sessions.withSession(token,csrf,s->wishes.export(s,role)));}
}
