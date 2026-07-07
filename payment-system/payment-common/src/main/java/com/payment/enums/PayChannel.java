package com.payment.enums;

import lombok.Getter;

/**
 * 支付渠道枚举
 */
@Getter
public enum PayChannel {

    WECHAT("WECHAT", "微信支付"),
    ALIPAY("ALIPAY", "支付宝"),
    UNION_PAY("UNION_PAY", "银联支付"),
    BANK_CARD("BANK_CARD", "银行卡支付");

    private final String code;
    private final String desc;

    PayChannel(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static PayChannel of(String code) {
        for (PayChannel channel : values()) {
            if (channel.code.equals(code)) {
                return channel;
            }
        }
        throw new IllegalArgumentException("Unknown pay channel: " + code);
    }
}
