package com.niangao.mihoyo.modules.mihoyo.vo;

import java.util.List;

/**
 * 关联账号查询结果，空列表也表示查询成功。
 *
 * @param authFlow 服务端凭证转换与查询链路标识
 * @param count 筛选后账号数量，与 list 长度一致
 * @param list 不可修改的账号列表
 */
public record GameAccountsResponse(String authFlow, int count, List<GameAccountVO> list) {}
