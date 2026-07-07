package com.payment.exception;

import lombok.Getter;

/**
 * 支付业务异常
 */
@Getter
public class PaymentException extends RuntimeException {

    private final String code;

    public PaymentException(String code, String message) {
        super(message);
        this.code = code;
    }

    public PaymentException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    // ========== 常用异常工厂方法 ==========

    public static PaymentException paramError(String message) {
        return new PaymentException("PARAM_ERROR", message);
    }

    public static PaymentException systemError(String message) {
        return new PaymentException("SYSTEM_ERROR", message);
    }

    public static PaymentException channelError(String message) {
        return new PaymentException("CHANNEL_ERROR", message);
    }

    public static PaymentException riskRejected(String message) {
        return new PaymentException("RISK_REJECTED", message);
    }
}
