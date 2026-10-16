package com.niangao.mihoyo.modules.mihoyo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.google.gson.JsonParser;
import com.niangao.mihoyo.common.exception.BusinessException;
import com.niangao.mihoyo.common.exception.GlobalExceptionHandler;
import com.niangao.mihoyo.modules.mihoyo.client.MihoyoPassportClient;
import com.niangao.mihoyo.modules.mihoyo.client.model.UpstreamReply;
import com.niangao.mihoyo.modules.mihoyo.config.MihoyoProperties;
import com.niangao.mihoyo.modules.mihoyo.dto.VerifyLoginRequest;
import com.niangao.mihoyo.modules.mihoyo.entity.LoginSession;
import com.niangao.mihoyo.modules.mihoyo.enums.LoginState;
import com.niangao.mihoyo.modules.mihoyo.mapper.LoginSessionMapper;
import com.niangao.mihoyo.modules.mihoyo.service.impl.MihoyoLoginServiceImpl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Clock;
import java.time.Instant;

/** 验证成功凭证和异常内敏感内容不会随新增日志写入控制台。 */
@ExtendWith(OutputCaptureExtension.class)
class ConsoleLogSafetyTest {
    private static final String SESSION_ID = "aabbccdd-1234-4234-8234-123456789012";
    private static final String PHONE = "13987654321";
    private static final String CAPTCHA = "987654";
    private static final String TOKEN = "secret-stoken-test-value";

    @Test
    void successfulLoginLogsStateAndDurationWithoutCredentials(CapturedOutput output) {
        LoginSessionMapper mapper = mock(LoginSessionMapper.class);
        MihoyoPassportClient client = mock(MihoyoPassportClient.class);
        LoginSession session = session();
        when(mapper.findById(SESSION_ID)).thenReturn(session);
        when(mapper.beginOperation(eq(SESSION_ID), anyLong(), any(), anyBoolean()))
                .thenReturn(session);
        when(mapper.completeOperation(any(), anyString())).thenAnswer(call -> call.getArgument(0));
        UpstreamReply reply = new UpstreamReply();
        reply.setBody(
                JsonParser.parseString(
                                "{\"retcode\":0,\"data\":{\"stoken\":\""
                                        + TOKEN
                                        + "\",\"user_info\":{\"aid\":\"123456789\",\"mid\":\"secret-mid-test\"}}}")
                        .getAsJsonObject());
        when(client.call(any(), any(), anyString(), isNull())).thenReturn(reply);
        MihoyoLoginServiceImpl service =
                new MihoyoLoginServiceImpl(
                        mapper, client, new MihoyoProperties(), Clock.systemUTC());
        assertEquals(
                LoginState.LOGGED_IN,
                service.verify(new VerifyLoginRequest(SESSION_ID, CAPTCHA, null)).state());
        assertTrue(output.getAll().contains("LOGGED_IN"));
        assertTrue(output.getAll().contains("elapsed_ms="));
        assertFalse(output.getAll().contains(PHONE));
        assertFalse(output.getAll().contains(CAPTCHA));
        assertFalse(output.getAll().contains(TOKEN));
        assertFalse(output.getAll().contains("secret-mid-test"));
        assertFalse(output.getAll().contains(SESSION_ID));
    }

    @Test
    void unexpectedErrorsLogTypeAndLocationWithoutRawExceptionMessage(CapturedOutput output) {
        LoginSessionMapper mapper = mock(LoginSessionMapper.class);
        MihoyoPassportClient client = mock(MihoyoPassportClient.class);
        LoginSession session = session();
        when(mapper.findById(SESSION_ID)).thenReturn(session);
        when(mapper.beginOperation(eq(SESSION_ID), anyLong(), any(), anyBoolean()))
                .thenReturn(session);
        RuntimeException error =
                new RuntimeException("raw-response:" + TOKEN + ":" + CAPTCHA + ":" + PHONE);
        when(client.call(any(), any(), anyString(), isNull())).thenThrow(error);
        MihoyoLoginServiceImpl service =
                new MihoyoLoginServiceImpl(
                        mapper, client, new MihoyoProperties(), Clock.systemUTC());
        assertEquals(
                502,
                assertThrows(
                                BusinessException.class,
                                () ->
                                        service.verify(
                                                new VerifyLoginRequest(SESSION_ID, CAPTCHA, null)))
                        .getStatus());
        assertEquals(500, new GlobalExceptionHandler().unexpected(error).getStatusCode().value());
        assertTrue(output.getAll().contains("error_type=RuntimeException"));
        assertTrue(output.getAll().contains("location="));
        assertFalse(output.getAll().contains(TOKEN));
        assertFalse(output.getAll().contains(CAPTCHA));
        assertFalse(output.getAll().contains(PHONE));
        assertFalse(output.getAll().contains(SESSION_ID));
    }

    private LoginSession session() {
        LoginSession session =
                new LoginSession(
                        SESSION_ID, PHONE, "+86", "device", Instant.now().plusSeconds(600));
        session.setState(LoginState.LOGIN_PROCESSING);
        session.setOperationId("operation-test");
        session.setSnapshotTime(Instant.now());
        return session;
    }
}
