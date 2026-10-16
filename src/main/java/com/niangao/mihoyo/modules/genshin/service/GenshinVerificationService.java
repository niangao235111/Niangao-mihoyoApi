package com.niangao.mihoyo.modules.genshin.service;

import com.niangao.mihoyo.common.exception.BusinessException;
import com.niangao.mihoyo.modules.genshin.client.*;
import com.niangao.mihoyo.modules.genshin.dto.*;
import com.niangao.mihoyo.modules.genshin.entity.GenshinVerification.*;
import com.niangao.mihoyo.modules.genshin.mapper.GenshinVerificationMapper;
import com.niangao.mihoyo.modules.genshin.vo.VerificationView;
import com.niangao.mihoyo.modules.workbench.util.WorkbenchCipher;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

@Service
public class GenshinVerificationService {
    private final GenshinVerificationMapper mapper;
    private final WorkbenchCipher cipher;
    private final GenshinRecordClient records;
    private final GenshinDeviceService devices;
    private final GenshinVerificationClient client;
    public GenshinVerificationService(GenshinVerificationMapper mapper, WorkbenchCipher cipher,
            GenshinRecordClient records, GenshinDeviceService devices, GenshinVerificationClient client) {
        this.mapper = mapper; this.cipher = cipher; this.records = records; this.devices = devices; this.client = client;
    }
    public <T> T read(String digest, GenshinRecordRequest request, String stage, Function<String, T> operation) {
        String encrypted = mapper.execute(digest, "read", "proof"), challenge = null;
        if (encrypted != null) {
            Proof proof = decode(encrypted, digest + ":genshin-proof", Proof.class);
            if (matches(request, proof.uid(), proof.region(), proof.gameBiz())) {
                var device = devices.resolve(request.stuid(), request.mid());
                if (proof.deviceId().equals(device.id()) && proof.fp().equals(device.fp())) challenge = proof.challenge();
            }
        }
        try { return operation.apply(challenge); }
        catch (BusinessException ex) {
            if ("upstream_verification_required".equals(ex.getDetails().get("reason"))) {
                Object rawTrace = ex.getDetails().get("verification_trace");
                String trace = rawTrace instanceof String value && value.matches("[A-Za-z0-9_.:-]{1,256}") ? value : null;
                var failure = new Failure(request.gameUid(), request.region(), request.gameBiz(), stage, trace);
                mapper.execute(digest, "failure", encrypted == null ? "" : encrypted, cipher.encryptValue(failure, digest + ":genshin-failure"));
            }
            throw ex;
        }
    }
    public VerificationView start(String digest, GenshinRecordRequest request) {
        String stored = mapper.execute(digest, "read", "failure");
        if (stored == null) throw new BusinessException(409, "请先读取所选账号的档案，触发安全验证后再获取任务");
        var failure = decode(stored, digest + ":genshin-failure", Failure.class);
        if (!matches(request, failure.uid(), failure.region(), failure.gameBiz())) throw new BusinessException(409, "验证账号已变更，请重新读取当前账号档案");
        String id = UUID.randomUUID().toString();
        String reserved = mapper.execute(digest, "reserve", id, stored);
        if (!"ok".equals(reserved)) throw new BusinessException("cooldown".equals(reserved) ? 429 : 409, "验证任务正在处理或请求过快，请稍候再试");
        try {
            var authorization = records.authorize(request);
            var challenge = client.create(authorization, failure);
            var task = new Task(failure, authorization.cookie(), authorization.device().id(), authorization.device().fp(), challenge);
            String encoded = cipher.encryptValue(task, digest + ":genshin-task:" + id);
            if (!"ok".equals(mapper.execute(digest, "ready", id, encoded))) throw expired();
            return new VerificationView(id, Instant.now().plusSeconds(300), challenge);
        } catch (RuntimeException ex) { mapper.execute(digest, "clear", id); throw ex; }
    }
    public void submit(String digest, VerificationSubmit result) {
        String id = result.verificationId();
        String encoded = mapper.execute(digest, "claim", id);
        if (encoded == null) throw expired();
        try {
            var task = decode(encoded, digest + ":genshin-task:" + id, Task.class);
            // GT3 可在原 challenge 后附加短后缀；不接受另一个任务的 challenge。
            if (!result.geetestChallenge().startsWith(task.challenge().challenge())
                    || result.geetestChallenge().length() > task.challenge().challenge().length() + 64) {
                throw new BusinessException(403, "验证码与当前验证任务不匹配，请重新获取任务");
            }
            String challenge = client.submit(task, result);
            var failure = task.failure();
            var proof = new Proof(failure.uid(), failure.region(), failure.gameBiz(), task.deviceId(), task.fp(), challenge);
            if (!"ok".equals(mapper.execute(digest, "pass", id, cipher.encryptValue(proof, digest + ":genshin-proof")))) throw expired();
        } catch (RuntimeException ex) { mapper.execute(digest, "clear", id); throw ex; }
    }
    private <T> T decode(String value, String aad, Class<T> type) {
        try { return cipher.decryptValue(value, aad, type); }
        catch (BusinessException ex) { if (ex.getStatus() == 401) throw expired(); throw ex; }
    }
    private boolean matches(GenshinRecordRequest request, String uid, String region, String gameBiz) {
        return request.gameUid().equals(uid) && request.region().equals(region) && request.gameBiz().equals(gameBiz);
    }
    private BusinessException expired() { return new BusinessException(410, "验证任务已过期、已提交或会话已退出，请重新获取任务"); }
}
