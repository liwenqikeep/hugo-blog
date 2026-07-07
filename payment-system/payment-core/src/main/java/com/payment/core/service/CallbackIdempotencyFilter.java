package com.payment.core.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 支付回调幂等防重放过滤器
 */
@Slf4j
@Component
public class CallbackIdempotencyFilter {

    /** 最近 N 秒内的相同回调，认为是重放 */
    private static final long REPLAY_WINDOW_SECONDS = 5;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 检查是否是重放攻击
     *
     * @param orderId      支付单号
     * @param channelTxId  渠道交易号
     * @param callbackTime 回调时间
     * @return true: 是重放攻击
     */
    public boolean isReplayAttack(String orderId, String channelTxId, LocalDateTime callbackTime) {
        String key = "callback:" + orderId + ":" + channelTxId;
        String lastTime = redisTemplate.opsForValue().get(key);

        if (lastTime != null) {
            LocalDateTime lastCallback = LocalDateTime.parse(lastTime);
            if (Duration.between(lastCallback, callbackTime).getSeconds() < REPLAY_WINDOW_SECONDS) {
                log.warn("检测到回调重放攻击: orderId={}, channelTxId={}", orderId, channelTxId);
                return true;
            }
        }

        redisTemplate.opsForValue().set(key, callbackTime.toString(), 1, TimeUnit.HOURS);
        return false;
    }
}
