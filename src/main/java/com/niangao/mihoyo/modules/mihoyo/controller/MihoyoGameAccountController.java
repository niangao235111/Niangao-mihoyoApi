package com.niangao.mihoyo.modules.mihoyo.controller;

import com.niangao.mihoyo.common.result.Result;
import com.niangao.mihoyo.modules.mihoyo.dto.GameAccountsRequest;
import com.niangao.mihoyo.modules.mihoyo.service.MihoyoGameAccountService;
import com.niangao.mihoyo.modules.mihoyo.vo.GameAccountsResponse;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(
        value = "/api/mihoyo/game",
        produces = "application/json",
        consumes = "application/json")
public class MihoyoGameAccountController {

    private final MihoyoGameAccountService service;

    public MihoyoGameAccountController(MihoyoGameAccountService service) {
        this.service = service;
    }

    /** 校验查询 DTO 并调用业务层，将账号查询 VO 包装为统一 Result。 */
    @PostMapping("/accounts")
    public Result<GameAccountsResponse> accounts(@Valid @RequestBody GameAccountsRequest request) {
        return Result.success(service.queryAccounts(request));
    }
}
