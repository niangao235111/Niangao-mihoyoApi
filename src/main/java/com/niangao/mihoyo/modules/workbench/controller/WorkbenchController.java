package com.niangao.mihoyo.modules.workbench.controller;

import com.niangao.mihoyo.common.exception.BusinessException;
import com.niangao.mihoyo.common.result.Result;
import com.niangao.mihoyo.modules.genshin.dto.GenshinRecordRequest;
import com.niangao.mihoyo.modules.genshin.service.GenshinArchiveService;
import com.niangao.mihoyo.modules.genshin.service.GenshinVerificationService;
import com.niangao.mihoyo.modules.genshin.service.GenshinArchiveStorage;
import com.niangao.mihoyo.modules.genshin.vo.SavedArchive;
import com.niangao.mihoyo.modules.genshin.dto.VerificationSubmit;
import com.niangao.mihoyo.modules.genshin.vo.VerificationView;
import com.niangao.mihoyo.modules.genshin.vo.GenshinArchive;
import com.niangao.mihoyo.modules.mihoyo.dto.GameAccountsRequest;
import com.niangao.mihoyo.modules.mihoyo.service.MihoyoGameAccountService;
import com.niangao.mihoyo.modules.mihoyo.vo.*;
import com.niangao.mihoyo.modules.workbench.config.WorkbenchSessionProperties;
import com.niangao.mihoyo.modules.workbench.dto.WorkbenchQuery;
import com.niangao.mihoyo.modules.workbench.entity.WorkbenchSecret;
import com.niangao.mihoyo.modules.workbench.service.WorkbenchSessionService;
import com.niangao.mihoyo.modules.workbench.vo.WorkbenchSessionView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.util.Map;

@RestController
@Slf4j
@RequestMapping(value = "/api/workbench", produces = "application/json")
public class WorkbenchController {
    public static final String COOKIE = "ng_workbench";
    private final WorkbenchSessionService sessions;
    private final WorkbenchSessionProperties settings;
    private final MihoyoGameAccountService accounts;
    private final GenshinArchiveService archive;
    private final GenshinVerificationService verification;
    private final GenshinArchiveStorage storage;

    public WorkbenchController(WorkbenchSessionService sessions, WorkbenchSessionProperties settings,
            MihoyoGameAccountService accounts, GenshinArchiveService archive, GenshinVerificationService verification, GenshinArchiveStorage storage) {
        this.sessions = sessions;
        this.settings = settings;
        this.accounts = accounts;
        this.archive = archive;
        this.verification = verification;
        this.storage = storage;
    }

    /** 独立健康检查：不要求登录、不访问米哈游上游、不返回敏感信息，供容器健康探测使用。 */
    @GetMapping("/health")
    public Result<Map<String, String>> health() {
        return Result.success(Map.of("status", "ok"));
    }

    @PostMapping(value = "/session", consumes = "application/json")
    public Result<WorkbenchSessionView> create(@Valid @RequestBody GameAccountsRequest request,
            @CookieValue(name = COOKIE, required = false) String previous, HttpServletResponse response) {
        var issued = sessions.create(request, previous);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(issued.token(), settings.getAbsoluteTtl()));
        return Result.success(issued.view());
    }

    @GetMapping("/session")
    public Result<WorkbenchSessionView> restore(@CookieValue(name = COOKIE, required = false) String token) {
        return Result.success(sessions.restore(token));
    }

    @PostMapping(value = "/session/renew", consumes = "application/json")
    public Result<WorkbenchSessionView> renew(@CookieValue(name = COOKIE, required = false) String token,
            @RequestHeader(name = "X-Workspace-CSRF", required = false) String csrf) {
        return Result.success(sessions.renew(token, csrf));
    }

    @PostMapping(value = "/session/logout", consumes = "application/json")
    public Result<MessageResponse> logout(@CookieValue(name = COOKIE, required = false) String token,
            @RequestHeader(name = "X-Workspace-CSRF", required = false) String csrf, HttpServletResponse response) {
        sessions.logout(token, csrf);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO));
        return Result.success(new MessageResponse("工作台会话已退出"));
    }

    @PostMapping(value = "/game/accounts", consumes = "application/json")
    public Result<GameAccountsResponse> accounts(@Valid @RequestBody WorkbenchQuery.Accounts query,
            @CookieValue(name = COOKIE, required = false) String token,
            @RequestHeader(name = "X-Workspace-CSRF", required = false) String csrf) {
        return Result.success(sessions.withSession(token, csrf, secret -> accounts.queryAccounts(
                new GameAccountsRequest(secret.stoken(), secret.stuid(), secret.mid(), query.gameBiz(), null, secret.deviceId()))));
    }

    @PostMapping(value = "/genshin/overview", consumes = "application/json")
    public Result<GenshinArchive.Overview> overview(@Valid @RequestBody WorkbenchQuery.Archive query,
            @CookieValue(name = COOKIE, required = false) String token,
            @RequestHeader(name = "X-Workspace-CSRF", required = false) String csrf) {
        return Result.success(sessions.withSession(token, csrf, secret -> {
            var request = recordRequest(secret, query);
            return storage.track(secret, request, "genshin_overview", () -> verification.read(WorkbenchSessionService.digest(token), request, "genshin_overview", challenge -> archive.overview(request, challenge)));
        }));
    }

    @PostMapping(value = "/genshin/characters", consumes = "application/json")
    public Result<GenshinArchive.Characters> characters(@Valid @RequestBody WorkbenchQuery.Archive query,
            @CookieValue(name = COOKIE, required = false) String token,
            @RequestHeader(name = "X-Workspace-CSRF", required = false) String csrf) {
        return Result.success(sessions.withSession(token, csrf, secret -> {
            var request = recordRequest(secret, query);
            return storage.track(secret, request, "genshin_characters", () -> verification.read(WorkbenchSessionService.digest(token), request, "genshin_characters", challenge -> archive.characters(request, false, challenge)));
        }));
    }

    @PostMapping(value = "/genshin/characters/details", consumes = "application/json")
    public Result<GenshinArchive.Characters> details(@Valid @RequestBody WorkbenchQuery.Archive query,
            @CookieValue(name = COOKIE, required = false) String token,
            @RequestHeader(name = "X-Workspace-CSRF", required = false) String csrf) {
        return Result.success(sessions.withSession(token, csrf, secret -> {
            var request = recordRequest(secret, query);
            return storage.track(secret, request, "genshin_equipment", () -> verification.read(WorkbenchSessionService.digest(token), request, "genshin_equipment", challenge -> archive.characters(request, true, challenge)));
        }));
    }

    @PostMapping(value = "/genshin/saved", consumes = "application/json")
    public Result<SavedArchive> saved(@Valid @RequestBody WorkbenchQuery.Archive query,
            @CookieValue(name = COOKIE, required = false) String token,
            @RequestHeader(name = "X-Workspace-CSRF", required = false) String csrf) {
        return Result.success(sessions.withSession(token, csrf, secret -> storage.read(secret, recordRequest(secret, query))));
    }

    @PostMapping(value = "/genshin/verification/start", consumes = "application/json")
    public Result<VerificationView> startVerification(@Valid @RequestBody WorkbenchQuery.Archive query,
            @CookieValue(name = COOKIE, required = false) String token,
            @RequestHeader(name = "X-Workspace-CSRF", required = false) String csrf) {
        return Result.success(sessions.withSession(token, csrf, secret ->
                verification.start(WorkbenchSessionService.digest(token), recordRequest(secret, query))));
    }

    @PostMapping(value = "/genshin/verification/submit", consumes = "application/json")
    public Result<MessageResponse> submitVerification(@Valid @RequestBody VerificationSubmit result,
            @CookieValue(name = COOKIE, required = false) String token,
            @RequestHeader(name = "X-Workspace-CSRF", required = false) String csrf) {
        return Result.success(sessions.withSession(token, csrf, secret -> {
            verification.submit(WorkbenchSessionService.digest(token), result);
            return new MessageResponse("安全验证已通过，正在重新读取档案");
        }));
    }

    private GenshinRecordRequest recordRequest(WorkbenchSecret secret, WorkbenchQuery.Archive query) {
        return new GenshinRecordRequest(secret.stoken(), secret.stuid(), secret.mid(), query.gameUid(), query.region(), query.gameBiz(), query.characterIds(), secret.deviceId());
    }

    private String cookie(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE, value).httpOnly(true).secure(settings.isCookieSecure())
                .sameSite("Lax").path("/api").maxAge(maxAge).build().toString();
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<?>> failure(BusinessException ex, HttpServletRequest request) {
        // 前端打开页面时用 GET /api/workbench/session 试探登录态，未登录的 401 属预期；
        // 容器健康探测此前复用同一路径，导致每 20 秒一条告警。仅这类探测降到 DEBUG，其余业务错误仍告警。
        boolean expectedProbe = ex.getStatus() == 401 && "GET".equals(request.getMethod())
                && "/api/workbench/session".equals(request.getRequestURI());
        if (expectedProbe) {
            log.debug("工作台未登录探测 status=401, path={}", request.getRequestURI());
        } else {
            log.warn("工作台接口失败 status={}, stage={}, upstream_retcode={}", ex.getStatus(),
                    ex.getDetails().getOrDefault("stage", "unspecified"), ex.getDetails().getOrDefault("upstream_retcode", "none"));
        }
        var builder = ResponseEntity.status(ex.getStatus());
        // 旧请求的迟到 401 不得清除浏览器刚换入的新 Cookie；退出接口负责显式清除。
        return builder.body(Result.failure(ex.getStatus(), ex.getMessage(), ex.getDetails().isEmpty() ? null : ex.getDetails()));
    }
}
