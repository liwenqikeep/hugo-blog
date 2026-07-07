package com.payment.channel.spi;

import lombok.Data;

import java.util.Map;

/**
 * 回调通知解析结果
 */
@Data
public class NotifyResult {

    /** 是否支付成功 */
    private boolean success;

    /** 渠道交易号 */
    private String channelTransactionId;

    /** 我方支付单号 */
    private String orderId;

    /** 渠道返回的原始结果 */
    private String channelResult;

    /** 给渠道的应答（如 "SUCCESS" 或 "FAIL"） */
    private String channelAck;

    /** 解析后的参数 */
    private Map<String, String> parsedParams;
}
