package com.niangao.mihoyo.modules.genshin;

import com.niangao.mihoyo.common.exception.BusinessException;
import com.niangao.mihoyo.modules.genshin.client.GenshinDeviceClient;
import com.niangao.mihoyo.modules.genshin.mapper.GenshinDeviceMapper;
import com.niangao.mihoyo.modules.genshin.service.GenshinDeviceService;
import com.niangao.mihoyo.modules.mihoyo.config.MihoyoRedisProperties;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GenshinDeviceServiceTest {
    final GenshinDeviceMapper mapper = mock(GenshinDeviceMapper.class);
    final GenshinDeviceClient client = mock(GenshinDeviceClient.class);
    final MihoyoRedisProperties settings = new MihoyoRedisProperties();
    GenshinDeviceService service;
    @BeforeEach void setup() {
        settings.setPhoneKeySecret("test-only-genshin-device-hmac");
        service = new GenshinDeviceService(mapper, client, settings);
    }
    @Test void accountIdentityIsStableAcrossInstancesAndSeparatedByAccountAndDeployment() {
        String id = service.deviceId("123", "fixture-mid");
        assertEquals(id, new GenshinDeviceService(mapper, client, settings).deviceId("123", "fixture-mid"));
        assertNotEquals(id, service.deviceId("124", "fixture-mid"));
        assertNotEquals(id, service.deviceId("123", "other-mid"));
        settings.setPhoneKeySecret("another-deployment-test-key");
        assertNotEquals(id, service.deviceId("123", "fixture-mid"));
        assertFalse(id.contains("fixture-mid")); assertEquals(36, id.length());
    }
    @Test void concurrentArchiveRequestsShareOneServerIssuedFingerprint() throws Exception {
        var cached = new AtomicReference<String>();
        when(mapper.find(anyString())).thenAnswer(call -> cached.get());
        when(mapper.saveIfAbsent(anyString(), anyString())).thenAnswer(call -> {
            cached.compareAndSet(null, call.getArgument(1)); return cached.get();
        });
        when(client.fetch(anyString())).thenReturn("server-fingerprint");
        var executor = Executors.newFixedThreadPool(8);
        try {
            var tasks = new ArrayList<Future<GenshinDeviceService.Device>>();
            for (int i = 0; i < 16; i++) tasks.add(executor.submit(() -> service.resolve("123", "fixture-mid")));
            for (var task : tasks) {
                assertEquals("server-fingerprint", task.get(10, TimeUnit.SECONDS).fp());
                assertEquals(service.deviceId("123", "fixture-mid"), task.get().id());
            }
            verify(client, times(1)).fetch(anyString());
        } finally { executor.shutdownNow(); }
    }
    @Test void fingerprintFailureNeverCachesOrReturnsInventedValue() {
        when(client.fetch(anyString())).thenThrow(new BusinessException(502, "fingerprint unavailable"));
        assertEquals(502, assertThrows(BusinessException.class, () -> service.resolve("123", "fixture-mid")).getStatus());
        verify(mapper, never()).saveIfAbsent(anyString(), anyString());
        assertFalse(new GenshinDeviceService.Device("private-device", "private-fp").toString().contains("private"));
    }
}
