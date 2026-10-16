package com.niangao.mihoyo.modules.genshin.controller;

import com.niangao.mihoyo.common.result.Result;
import com.niangao.mihoyo.modules.genshin.dto.GenshinRecordRequest;
import com.niangao.mihoyo.modules.genshin.service.GenshinArchiveService;
import com.niangao.mihoyo.modules.genshin.vo.GenshinArchive.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/genshin", consumes = "application/json", produces = "application/json")
public class GenshinArchiveController {
    private final GenshinArchiveService service;

    public GenshinArchiveController(GenshinArchiveService service) { this.service = service; }

    @PostMapping("/overview")
    public Result<Overview> overview(@Valid @RequestBody GenshinRecordRequest request) {
        return Result.success(service.overview(request));
    }

    @PostMapping("/characters")
    public Result<Characters> characters(@Valid @RequestBody GenshinRecordRequest request) {
        return Result.success(service.characters(request, false));
    }

    @PostMapping("/characters/details")
    public Result<Characters> details(@Valid @RequestBody GenshinRecordRequest request) {
        return Result.success(service.characters(request, true));
    }
}
