package com.niangao.mihoyo.modules.mihoyo.enums;

/** 米哈游短信流程状态，与网站登录状态分离。 */
public enum LoginState {
    /** 创建完成，允许申请发送短信。 */
    CREATED,
    /** 已原子占用执行资格，其他实例不得再次发送。 */
    SMS_PROCESSING,
    /** 需要完成短信阶段人机验证。 */
    SMS_CHALLENGE,
    /** 短信步骤完成，允许提交验证码。 */
    SMS_SENT,
    /** 正在取得凭证，其他实例不得重复提交。 */
    LOGIN_PROCESSING,
    /** 需要完成登录阶段人机验证。 */
    LOGIN_CHALLENGE,
    /** 已取得米哈游凭证。 */
    LOGGED_IN,
    /** 请求失败、结果不明或执行者中断，不能自动重试原会话。 */
    FAILED
}
