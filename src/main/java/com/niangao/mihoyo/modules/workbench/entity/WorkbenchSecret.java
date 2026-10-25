package com.niangao.mihoyo.modules.workbench.entity;

import com.niangao.mihoyo.modules.mihoyo.vo.GameAccountVO;
import java.util.List;

/** 仅存放在 Redis 的 AES-GCM 密文中；不携带短期 login_ticket。 */
public record WorkbenchSecret(String stoken, String stuid, String mid, List<GameAccountVO> roles, String deviceId) {
    public WorkbenchSecret(String stoken, String stuid, String mid, List<GameAccountVO> roles) {
        this(stoken, stuid, mid, roles, null);
    }
    @Override
    public String toString() { return "WorkbenchSecret[redacted]"; }
}
