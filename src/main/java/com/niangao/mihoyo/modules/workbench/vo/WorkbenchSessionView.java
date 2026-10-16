package com.niangao.mihoyo.modules.workbench.vo;

import com.niangao.mihoyo.modules.mihoyo.vo.GameAccountVO;
import java.time.Instant;
import java.util.List;

/** Cookie Token 和米哈游凭证不出现在此响应中。 */
public record WorkbenchSessionView(boolean connected, String accountLabel, String csrfToken,
        Instant createdAt, Instant expiresAt, Instant absoluteExpiresAt, List<GameAccountVO> roles) {
    @Override
    public String toString() { return "WorkbenchSessionView[redacted]"; }
}
