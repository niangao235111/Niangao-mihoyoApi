package com.niangao.mihoyo.modules.genshin.service;

import com.niangao.mihoyo.common.exception.BusinessException;
import com.niangao.mihoyo.modules.genshin.dto.GenshinRecordRequest;
import com.niangao.mihoyo.modules.genshin.mapper.GenshinArchiveMapper;
import com.niangao.mihoyo.modules.genshin.util.GenshinChannels;
import com.niangao.mihoyo.modules.genshin.vo.GenshinArchive.*;
import com.niangao.mihoyo.modules.genshin.vo.SavedArchive;
import com.niangao.mihoyo.modules.workbench.entity.WorkbenchSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import java.util.Map;
import java.util.function.Supplier;

@Service
@Slf4j
public class GenshinArchiveStorage {
    private final GenshinArchiveMapper mapper;
    private final boolean enabled;
    public GenshinArchiveStorage(GenshinArchiveMapper mapper, @Value("${genshin.storage.enabled:true}") boolean enabled) {
        this.mapper = mapper; this.enabled = enabled;
    }
    private void authorize(WorkbenchSecret secret, GenshinRecordRequest request) {
        if (!GenshinChannels.supports(request.gameBiz(), request.region())) throw new BusinessException(400, "仅支持国服官服和B服");
        if (!request.stuid().equals(secret.stuid()) || secret.roles().stream().noneMatch(role ->
                request.gameUid().equals(role.gameUid()) && request.region().equals(role.region()) && GenshinChannels.supports(role.gameBiz(), role.region()))) {
            throw new BusinessException(403, "该原神角色未关联当前账号，请重新连接或查询绑定角色");
        }
    }
    public SavedArchive read(WorkbenchSecret secret, GenshinRecordRequest request) {
        authorize(secret, request);
        if (!enabled) throw new BusinessException(503, "档案保存尚未启用");
        try { return mapper.read(request); }
        catch (DataAccessException ex) { throw unavailable(); }
    }
    public <T> T track(WorkbenchSecret secret, GenshinRecordRequest request, String stage, Supplier<T> operation) {
        if (!enabled) return operation.get();
        authorize(secret, request);
        boolean details = "genshin_equipment".equals(stage);
        int expected = details && request.characterIds() != null ? (int) request.characterIds().stream().distinct().count() : 0;
        if (details && expected == 0) throw new BusinessException(400, "请选择角色后读取装备");
        GenshinArchiveMapper.Run run;
        try { run = mapper.begin(request, details); }
        catch (DataAccessException ex) { throw unavailable(); }
        try {
            T value = operation.get();
            if (value instanceof Overview overview) mapper.overview(run, overview);
            else if (value instanceof Characters characters) mapper.characters(run, characters, details, expected);
            else throw new BusinessException(502, "档案响应异常，未保存");
            return value;
        } catch (RuntimeException ex) {
            String reason = ex instanceof BusinessException business && "upstream_verification_required".equals(business.getDetails().get("reason"))
                    ? "upstream_verification_required" : ex instanceof DataAccessException ? "storage_unavailable" : "archive_request_failed";
            Integer code = ex instanceof BusinessException business && business.getDetails().get("upstream_retcode") instanceof Number number ? number.intValue() : null;
            try { mapper.failed(run, stage, code, reason, expected); }
            catch (DataAccessException ignored) { log.warn("档案查询记录保存失败 stage={}", stage); }
            if (ex instanceof DataAccessException) throw unavailable();
            throw ex;
        }
    }
    private BusinessException unavailable() {
        return new BusinessException(503, "档案数据库暂不可用，已保存数据不会被清空，请稍后重试", Map.of("reason", "archive_storage_unavailable"));
    }
}
