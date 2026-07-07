package com.payment.channel.health;

import lombok.Data;

/**
 * 渠道健康状态
 */
@Data
public class ChannelHealth {

    private String channel;

    /** 熔断器状态 */
    private CircuitState circuitBreakerState = CircuitState.CLOSED;

    /** 熔断开启时间 */
    private long circuitOpenedAt;

    /** 连续失败次数 */
    private int consecutiveFailures;

    /** 总请求数（滑动窗口内） */
    private int totalRequests;

    /** 成功数 */
    private int successCount;

    /** 平均响应时间 */
    private double avgResponseTimeMs;

    /** 渠道费率（如 0.006 表示 0.6%） */
    private double rate = 0.006;

    /** 是否启用 */
    private boolean enabled = true;

    public synchronized void recordSuccess(long responseTimeMs) {
        consecutiveFailures = 0;
        totalRequests++;
        successCount++;
        avgResponseTimeMs = (avgResponseTimeMs * (totalRequests - 1) + responseTimeMs) / totalRequests;

        if (circuitBreakerState == CircuitState.HALF_OPEN) {
            circuitBreakerState = CircuitState.CLOSED;
        }
    }

    public synchronized void recordFailure(String errorCode) {
        consecutiveFailures++;
        totalRequests++;
    }
}
