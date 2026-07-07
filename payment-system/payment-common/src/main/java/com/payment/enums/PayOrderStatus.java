package com.payment.enums;

import lombok.Getter;

/**
 * 支付单状态枚举
 */
@Getter
public enum PayOrderStatus {

    INIT("INIT", "待支付"),
    PAYING("PAYING", "支付中"),
    SUCCESS("SUCCESS", "支付成功"),
    FAIL("FAIL", "支付失败"),
    CLOSED("CLOSED", "已关闭"),
    REFUNDING("REFUNDING", "退款中"),
    REFUNDED("REFUNDED", "已退款");

    private final String code;
    private final String desc;

    PayOrderStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static PayOrderStatus of(String code) {
        for (PayOrderStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown status: " + code);
    }
}
