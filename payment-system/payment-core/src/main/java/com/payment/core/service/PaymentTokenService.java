package com.payment.core.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 支付 Token 服务 - 防止前端重复提交
 */
@Slf4j
@Service
public class PaymentTokenService {

    private static final String TOKEN_PREFIX = "pay_token:";
    private static final long TOKEN_TTL = 300L; // 5 分钟

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 生成支付 token
     */
    public String generateToken(String bizOrderNo) {
        String token = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(
                TOKEN_PREFIX + token, bizOrderNo, TOKEN_TTL, TimeUnit.SECONDS);
        log.debug("支付 Token 已生成: bizOrderNo={}, token={}", bizOrderNo, token);
        return token;
    }

    /**
     * 验证并消费 token（幂等）
     *
     * @return true: token 有效且未被使用
     */
    public boolean consumeToken(String token) {
        String key = TOKEN_PREFIX + token;
        Long result = redisTemplate.delete(key);
        boolean valid = result != null && result > 0;
        if (!valid) {
            log.warn("支付 Token 已使用或不存在: token={}", token);
        }
        return valid;
    }
}
