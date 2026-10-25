package com.niangao.mihoyo.modules.mihoyo;

import com.niangao.mihoyo.modules.mihoyo.config.MihoyoRedisProperties;
import com.niangao.mihoyo.modules.mihoyo.service.LoginDeviceBinding;
import com.niangao.mihoyo.modules.mihoyo.vo.MihoyoCredentials;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LoginDeviceBindingTest {
    @Test void bindingIsCredentialSpecificShortLivedAndContainsNoPlaintextCredentials() {
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        Map<String, String> stored = new HashMap<>();
        doAnswer(call -> {
            assertEquals(Duration.ofMinutes(10), call.getArgument(2));
            stored.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        when(values.get(anyString())).thenAnswer(call -> stored.get(call.getArgument(0)));
        var settings = new MihoyoRedisProperties();
        settings.setPhoneKeySecret("test-only-binding-hmac");
        var binding = new LoginDeviceBinding(redis, settings);
        var credentials = new MihoyoCredentials("stoken", "private-stoken", "123456789", "private-mid", null);
        String device = "a".repeat(53);
        binding.remember(credentials, device);
        assertEquals(device, binding.find("private-stoken", "123456789", "private-mid"));
        assertNull(binding.find("other-stoken", "123456789", "private-mid"));
        assertNull(binding.find("private-stoken", "987654321", "private-mid"));
        assertNull(binding.find("private-stoken", "123456789", "other-mid"));
        for (String secret : new String[]{"private-stoken", "123456789", "private-mid"})
            assertFalse(stored.toString().contains(secret));
        stored.clear();
        assertNull(binding.find("private-stoken", "123456789", "private-mid"));
        assertThrows(IllegalArgumentException.class, () -> binding.remember(credentials, "bad\r\nheader"));
    }
}
