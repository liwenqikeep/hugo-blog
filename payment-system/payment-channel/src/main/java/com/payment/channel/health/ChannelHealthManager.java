package com.payment.channel.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 渠道健康管理器
 */
@Slf4j
@Component
public class ChannelHealthManager {

    private final Map<String, ChannelHealth> healthMap = new ConcurrentHashMap<>();

    private static final int FAIL_THRESHOLD = 10;
    private static final long CIRCUIT_TIMEOUT_MS = 30_000;

    /**
     * 获取渠道健康状态
     */
    public ChannelHealth getHealth(String channel) {
        return healthMap.computeIfAbsent(channel, k -> {
            ChannelHealth health = new ChannelHealth();
            health.setChannel(channel);
            return health;
        });
    }

    /**
     * 记录调用成功
     */
    public void recordSuccess(String channel, long responseTimeMs) {
        getHealth(channel).recordSuccess(responseTimeMs);
    }

    /**
     * 记录调用失败
     */
    public void recordFailure(String channel, String errorCode) {
        ChannelHealth health = getHealth(channel);
        health.recordFailure(errorCode);

        if (health.getConsecutiveFailures() >= FAIL_THRESHOLD) {
            log.warn("渠道熔断: channel={}, consecutiveFailures={}", channel, health.getConsecutiveFailures());
            health.setCircuitBreakerState(CircuitState.OPEN);
            health.setCircuitOpenedAt(System.currentTimeMillis());
        }
    }

    /**
     * 判断渠道是否熔断
     */
    public boolean isCircuitBroken(String channel) {
        ChannelHealth health = healthMap.get(channel);
        if (health == null) return false;

        if (health.getCircuitBreakerState() == CircuitState.OPEN) {
            if (System.currentTimeMillis() - health.getCircuitOpenedAt() > CIRCUIT_TIMEOUT_MS) {
                health.setCircuitBreakerState(CircuitState.HALF_OPEN);
                return false;
            }
            return true;
        }
        return false;
    }

    /**
     * 判断渠道是否启用
     */
    public boolean isChannelEnabled(String channel) {
        ChannelHealth health = healthMap.get(channel);
        return health == null || health.isEnabled();
    }

    /**
     * 降级渠道
     */
    public void downgradeChannel(String channel, long durationMinutes) {
        getHealth(channel).setEnabled(false);
        log.warn("渠道已降级: channel={}, duration={}min", channel, durationMinutes);
    }

    /**
     * 获取渠道健康评分（0-50）
     */
    public double getHealthScore(String channel) {
        ChannelHealth health = healthMap.get(channel);
        if (health == null) return 50;
        if (!health.isEnabled()) return 0;

        double score = 50;
        // 成功率扣分
        if (health.getTotalRequests() > 0) {
            double successRate = (double) health.getSuccessCount() / health.getTotalRequests();
            score *= successRate;
        }
        // 响应时间扣分
        if (health.getAvgResponseTimeMs() > 1000) {
            score *= 0.8;
        }
        return score;
    }

    /**
     * 获取渠道费率
     */
    public double getChannelRate(String channel) {
        return getHealth(channel).getRate();
    }
}
