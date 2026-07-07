package com.payment.core.state;

/**
 * 支付单状态
 */
public enum PayOrderState {
    INIT,          // 待支付
    PAYING,        // 支付中
    SUCCESS,       // 支付成功
    FAIL,          // 支付失败
    CLOSED,        // 已关闭
    REFUNDING,     // 退款中
    REFUNDED       // 已退款
}
