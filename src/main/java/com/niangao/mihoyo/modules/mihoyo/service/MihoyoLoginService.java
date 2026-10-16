package com.niangao.mihoyo.modules.mihoyo.service;

import com.niangao.mihoyo.modules.mihoyo.dto.*;
import com.niangao.mihoyo.modules.mihoyo.vo.*;

/** 米哈游凭证获取用例，不处理网站用户登录。 */
public interface MihoyoLoginService {
    /** 创建米哈游登录流程，不发送短信。 */
    LoginResponse start(StartLoginRequest request);

    /** 发送短信或返回待完成的人机验证任务。 */
    LoginResponse sendSms(SendSmsRequest request);

    /** 提交验证码取得米哈游凭证，可能先返回登录阶段的人机验证任务。 */
    LoginResponse verify(VerifyLoginRequest request);

    /** 读取流程状态及待验证任务，不返回登录凭证。 */
    LoginResponse status(SessionRequest request);

    /** 结束当前流程，不撤销第三方凭证。 */
    MessageResponse delete(SessionRequest request);
}
