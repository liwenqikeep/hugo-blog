package com.payment.channel.spi;

import lombok.Data;

/**
 * 退款请求
 */
@Data
public class RefundRequest {

    /** 原支付单号 */
    private String originalOrderId;

    /** 退款单号 */
    private String refundOrderId;

    /** 退款金额（分） */
    private Long refundAmount;

    /** 退款原因 */
    private String refundReason;
}
