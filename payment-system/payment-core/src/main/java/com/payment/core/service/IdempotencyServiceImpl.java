package com.payment.core.service;

import com.payment.core.domain.service.IdempotencyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 幂等服务实现 - Redis 去重
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyServiceImpl implements IdempotencyService {

    private static final String IDEMPOTENT_KEY_PREFIX = "idempotent:";
    private static final long KEY_TTL_SECONDS = 86400L; // 24小时

    private final StringRedisTemplate redisTemplate;

    @Override
    @SuppressWarnings("unchecked")
    public <T> T tryProcess(String idempotentKey) {
        String key = IDEMPOTENT_KEY_PREFIX + idempotentKey;
        String value = redisTemplate.opsForValue().get(key);
        if (value != null) {
            log.debug("幂等命中: key={}", idempotentKey);
            return (T) value;
        }
        return null;
    }

    @Override
    public void saveResult(String idempotentKey, Object result) {
        String key = IDEMPOTENT_KEY_PREFIX + idempotentKey;
        String value = result != null ? result.toString() : "";
        redisTemplate.opsForValue().set(key, value, KEY_TTL_SECONDS, TimeUnit.SECONDS);
        log.debug("幂等结果已保存: key={}", idempotentKey);
    }
}
