package com.payment.channel.spi;

import lombok.Data;

/**
 * 支付请求
 */
@Data
public class PayRequest {

    /** 支付单号 */
    private String orderId;

    /** 商户订单号 */
    private String bizOrderNo;

    /** 金额（分） */
    private Long amount;

    /** 金额（元，给支付宝用） */
    private String amountYuan;

    /** 商品描述 */
    private String subject;

    /** 回调通知地址 */
    private String notifyUrl;

    /** 用户 IP */
    private String userIp;

    /** 扩展参数 */
    private String extra;
}
