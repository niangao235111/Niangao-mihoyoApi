package com.niangao.mihoyo.modules.workbench.service;

import com.niangao.mihoyo.common.exception.BusinessException;
import com.niangao.mihoyo.modules.mihoyo.dto.GameAccountsRequest;
import com.niangao.mihoyo.modules.mihoyo.service.MihoyoGameAccountService;
import com.niangao.mihoyo.modules.workbench.entity.WorkbenchSecret;
import com.niangao.mihoyo.modules.workbench.mapper.WorkbenchSessionMapper;
import com.niangao.mihoyo.modules.workbench.mapper.WorkbenchSessionMapper.Stored;
import com.niangao.mihoyo.modules.workbench.util.WorkbenchCipher;
import com.niangao.mihoyo.modules.workbench.vo.WorkbenchSessionView;
import com.niangao.mihoyo.modules.genshin.util.GenshinChannels;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

@Service
public class WorkbenchSessionService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final WorkbenchSessionMapper mapper;
    private final WorkbenchCipher cipher;
    private final MihoyoGameAccountService accounts;

    public WorkbenchSessionService(WorkbenchSessionMapper mapper, WorkbenchCipher cipher, MihoyoGameAccountService accounts) {
        this.mapper = mapper;
        this.cipher = cipher;
        this.accounts = accounts;
    }

    public record Issued(String token, WorkbenchSessionView view) {
        @Override
        public String toString() { return "IssuedWorkbenchSession[redacted]"; }
    }

    public Issued create(GameAccountsRequest input, String previousToken) {
        cipher.requireConfigured();
        // 不接受筛选参数作为会话状态；从上游核验并保存全部关联角色的快照。
        var result = accounts.queryAccounts(new GameAccountsRequest(input.stoken(), input.stuid(), input.mid(), null, null));
        WorkbenchSecret secret = new WorkbenchSecret(input.stoken(), input.stuid(), input.mid(), List.copyOf(result.list()));
        String token = random(), digest = digest(token), csrf = random();
        Stored stored = mapper.insert(digest, cipher.encrypt(secret, digest), csrf);
        if (stored == null) throw new BusinessException(503, "工作台会话创建失败，请重试");
        // 成功换入新会话后，旧会话不再可用；清理失败则不把新会话交给浏览器。
        try { if (validToken(previousToken)) mapper.delete(digest(previousToken)); }
        catch (RuntimeException ex) { mapper.delete(digest); throw ex; }
        return new Issued(token, view(secret, stored));
    }

    public WorkbenchSessionView restore(String token) {
        String digest = digest(token);
        Stored stored = required(mapper.read(digest, true));
        return view(cipher.decrypt(stored.encrypted(), digest), stored);
    }

    public WorkbenchSessionView renew(String token, String csrf) {
        return withSession(token, csrf, secret -> view(secret, required(mapper.read(digest(token), false))));
    }

    public void logout(String token, String csrf) {
        if (!validToken(token)) return;
        Stored existing = mapper.read(digest(token), false);
        if (existing == null) return;
        checkCsrf(existing, csrf);
        mapper.delete(digest(token));
    }

    public <T> T withSession(String token, String csrf, Function<WorkbenchSecret, T> action) {
        String digest = digest(token);
        checkCsrf(required(mapper.read(digest, false)), csrf);
        Stored current = required(mapper.read(digest, true));
        try {
            T result = action.apply(cipher.decrypt(current.encrypted(), digest));
            // 网络请求期间退出或过期，迟到响应不能作为有效会话继续返回。
            required(mapper.read(digest, false));
            return result;
        } catch (BusinessException ex) {
            if ("stoken_to_ltoken".equals(ex.getDetails().get("stage"))
                    && (Objects.equals(-100, ex.getDetails().get("upstream_retcode"))
                        || Objects.equals(-101, ex.getDetails().get("upstream_retcode")))) {
                mapper.delete(digest);
                throw new BusinessException(401, "米哈游凭证已失效，请重新授权连接", Map.of("reason", "mihoyo_credentials_expired"));
            }
            throw ex;
        }
    }

    private WorkbenchSessionView view(WorkbenchSecret secret, Stored stored) {
        String id = secret.stuid();
        return new WorkbenchSessionView(true, "米哈游账号 · " + id.substring(Math.max(0, id.length() - 4)),
                stored.csrf(), Instant.ofEpochMilli(stored.createdAt()), Instant.ofEpochMilli(stored.expiresAt()),
                Instant.ofEpochMilli(stored.absoluteExpiresAt()), secret.roles().stream()
                        .filter(role -> GenshinChannels.supports(role.gameBiz(), role.region())).toList());
    }

    private Stored required(Stored stored) {
        if (stored == null) throw new BusinessException(401, "工作台会话已过期或退出，请重新连接账号", Map.of("reason", "workbench_session_expired"));
        return stored;
    }

    private void checkCsrf(Stored stored, String supplied) {
        if (supplied == null || !MessageDigest.isEqual(stored.csrf().getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(403, "工作台请求校验失败，请刷新页面后重试");
        }
    }

    private static boolean validToken(String token) { return token != null && token.matches("[A-Za-z0-9_-]{43}"); }
    public static String digest(String token) {
        if (!validToken(token)) throw new BusinessException(401, "尚未连接工作台，请登录或导入凭证");
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static String random() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
