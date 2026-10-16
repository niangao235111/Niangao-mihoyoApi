package com.niangao.mihoyo.modules.mihoyo.service;

import com.niangao.mihoyo.modules.mihoyo.dto.GameAccountsRequest;
import com.niangao.mihoyo.modules.mihoyo.vo.GameAccountsResponse;

public interface MihoyoGameAccountService {
    /** 查询指定通行证已关联的原神账号；本期不会创建网站侧绑定记录。 */
    GameAccountsResponse queryAccounts(GameAccountsRequest request);
}
