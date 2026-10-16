package com.niangao.mihoyo.modules.mihoyo.controller;

import com.niangao.mihoyo.common.result.Result;
import com.niangao.mihoyo.modules.mihoyo.dto.*;
import com.niangao.mihoyo.modules.mihoyo.service.MihoyoLoginService;
import com.niangao.mihoyo.modules.mihoyo.vo.*;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

/** 请求入口仅负责参数校验和调用 Service，不拼装米哈游请求。 */
@RestController
@RequestMapping(
        value = "/api/mihoyo/login",
        produces = "application/json",
        consumes = "application/json")
public class MihoyoLoginController {

    private final MihoyoLoginService service;

    public MihoyoLoginController(MihoyoLoginService service) {
        this.service = service;
    }

    /** 创建流程会话，不发送短信。 */
    @PostMapping("/start")
    public Result<LoginResponse> start(@Valid @RequestBody StartLoginRequest request) {
        return Result.success(service.start(request));
    }

    /** 发送短信，可能返回人机验证任务。 */
    @PostMapping("/sms")
    public Result<LoginResponse> sms(@Valid @RequestBody SendSmsRequest request) {
        return Result.success(service.sendSms(request));
    }

    /** 获取米哈游凭证，不签发网站登录令牌。 */
    @PostMapping("/verify")
    public Result<LoginResponse> verify(@Valid @RequestBody VerifyLoginRequest request) {
        return Result.success(service.verify(request));
    }

    /** 只返回状态，不重新返回凭证。 */
    @PostMapping("/status")
    public Result<LoginResponse> status(@Valid @RequestBody SessionRequest request) {
        return Result.success(service.status(request));
    }

    /** 清理流程，不撤销米哈游凭证。 */
    @PostMapping("/delete")
    public Result<MessageResponse> delete(@Valid @RequestBody SessionRequest request) {
        return Result.success(service.delete(request));
    }
}
