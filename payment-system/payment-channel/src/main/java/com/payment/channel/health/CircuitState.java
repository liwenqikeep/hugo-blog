package com.payment.channel.health;

/**
 * 熔断器状态
 */
public enum CircuitState {
    CLOSED,     // 关闭：正常运行
    OPEN,       // 打开：熔断中
    HALF_OPEN   // 半开：探测中
}
