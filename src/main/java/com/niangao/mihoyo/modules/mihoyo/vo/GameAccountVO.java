package com.niangao.mihoyo.modules.mihoyo.vo;

/**
 * 关联游戏账号 VO，返回字段白名单。record 用来表达创建后不再修改的响应数据。
 *
 * @param gameBiz 游戏渠道标识
 * @param gameUid 游戏 UID 字符串
 * @param region 服务器标识
 * @param regionName 可选服务器名称
 * @param nickname 可选昵称
 * @param level 可选等级
 * @param gameName 可选游戏名称
 * @param isChosen 可选上游选中标记
 * @param isOfficial 可选上游官方渠道标记
 */
public record GameAccountVO(
        String gameBiz,
        String gameUid,
        String region,
        String regionName,
        String nickname,
        Integer level,
        String gameName,
        Boolean isChosen,
        Boolean isOfficial) {}
