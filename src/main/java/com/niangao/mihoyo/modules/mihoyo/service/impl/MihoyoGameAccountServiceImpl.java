package com.niangao.mihoyo.modules.mihoyo.service.impl;

import com.google.gson.*;
import com.niangao.mihoyo.common.exception.BusinessException;
import com.niangao.mihoyo.modules.mihoyo.client.MihoyoGameAccountClient;
import com.niangao.mihoyo.modules.mihoyo.dto.GameAccountsRequest;
import com.niangao.mihoyo.modules.mihoyo.service.MihoyoGameAccountService;
import com.niangao.mihoyo.modules.mihoyo.util.CookieUtil;
import com.niangao.mihoyo.modules.mihoyo.util.ProtocolJsonUtil;
import com.niangao.mihoyo.modules.mihoyo.vo.*;
import com.niangao.mihoyo.modules.genshin.util.GenshinChannels;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 校验业务范围、筛选原神渠道、转换 VO；查询本期不持久化绑定关系。 */
@Slf4j
@Service
public class MihoyoGameAccountServiceImpl implements MihoyoGameAccountService {
    private final MihoyoGameAccountClient client;

    public MihoyoGameAccountServiceImpl(MihoyoGameAccountClient client) {
        this.client = client;
    }

    /** 先校验凭证和查询范围，再委托 Client 取关联角色。仅保留原神账号，并把 UID、等级、标记转换为接口约定的类型。 */
    @Override
    public GameAccountsResponse queryAccounts(GameAccountsRequest request) {
        // 凭证会被写入 HTTP Cookie，先在发请求前拦截分隔符等非法内容。
        String stoken = CookieUtil.validate(request.stoken(), "stoken");
        String mid = CookieUtil.validate(request.mid(), "mid");
        if (request.stuid() == null || !request.stuid().matches("[0-9]{1,20}"))
            throw new BusinessException(400, "Invalid stuid");
        String game = blankToNull(request.gameBiz());
        if (game != null && !Set.of("hk4e_cn", "hk4e_bilibili").contains(game))
            throw new BusinessException(400, "仅支持原神账号查询");
        String ds = blankToNull(request.ds());
        if (ds != null && !ds.matches("[0-9]+,[a-z0-9]+,[0-9a-f]{32}"))
            throw new BusinessException(400, "Invalid DS header");
        // Client 返回上游角色，Service 在本地按游戏和渠道筛选。
        long started = System.nanoTime();
        log.info("关联游戏账号查询开始 game_biz={}", game == null ? "all" : game);
        JsonArray raw = client.queryRoles(stoken, request.stuid(), mid, ds);
        List<GameAccountVO> accounts = new ArrayList<>();
        for (JsonElement item : raw) {
            if (!item.isJsonObject()) throw new BusinessException(502, "上游游戏账号条目格式异常");
            JsonObject role = item.getAsJsonObject();
            String biz = ProtocolJsonUtil.optional(role, "game_biz");
            String region = ProtocolJsonUtil.optional(role, "region");
            if (!GenshinChannels.supports(biz, region)
                    || (game != null && !("hk4e_bilibili".equals(game) ? "cn_qd01" : "cn_gf01").equals(region)))
                continue;
            // 显式构造 VO 白名单，避免凭证或未知上游字段混入公开响应。
            accounts.add(
                    new GameAccountVO(
                            biz,
                            ProtocolJsonUtil.required(role, "game_uid"),
                            ProtocolJsonUtil.required(role, "region"),
                            ProtocolJsonUtil.optional(role, "region_name"),
                            ProtocolJsonUtil.optional(role, "nickname"),
                            optionalInteger(role, "level"),
                            ProtocolJsonUtil.optional(role, "game_name"),
                            optionalBoolean(role, "is_chosen"),
                            optionalBoolean(role, "is_official")));
        }
        log.info(
                "关联游戏账号查询完成 account_count={}, elapsed_ms={}",
                accounts.size(),
                (System.nanoTime() - started) / 1_000_000);
        return new GameAccountsResponse(
                "stoken_to_ltoken_to_roles", accounts.size(), List.copyOf(accounts));
    }

    /** 将可选筛选参数的空白值统一视为未指定，避免空字符串参与渠道比较。 */
    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 上游未提供等级等可选字段时返回 null，由 Jackson 省略；已提供的值必须能解析为整数。 */
    private Integer optionalInteger(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull()
                ? ProtocolJsonUtil.integer(object, key)
                : null;
    }

    /** 兼容上游布尔或 0/1 标记；缺失时保留 null，不人为补出 false。 */
    private Boolean optionalBoolean(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull()
                ? ProtocolJsonUtil.bool(object, key)
                : null;
    }
}
