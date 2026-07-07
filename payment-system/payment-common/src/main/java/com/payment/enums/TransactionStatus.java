package com.payment.enums;

import lombok.Getter;

/**
 * 交易流水状态枚举
 */
@Getter
public enum TransactionStatus {

    INIT("INIT", "初始化"),
    CALLING("CALLING", "调起渠道中"),
    SUCCESS("SUCCESS", "交易成功"),
    FAIL("FAIL", "交易失败"),
    CLOSED("CLOSED", "已关闭");

    private final String code;
    private final String desc;

    TransactionStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
