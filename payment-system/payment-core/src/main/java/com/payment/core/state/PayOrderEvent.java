package com.payment.core.state;

/**
 * 支付单事件
 */
public enum PayOrderEvent {
    PAY_START,      // 发起支付
    PAY_SUCCESS,    // 支付成功
    PAY_FAIL,       // 支付失败
    PAY_CANCEL,     // 取消/关闭
    REFUND_START,   // 发起退款
    REFUND_COMPLETE // 退款完成
}
