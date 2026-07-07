package com.payment.channel.spi;

import lombok.Data;

/**
 * 查询请求
 */
@Data
public class QueryRequest {

    /** 支付单号 */
    private String orderId;

    /** 渠道交易号 */
    private String channelOrderId;

    public QueryRequest() {}

    public QueryRequest(String orderId) {
        this.orderId = orderId;
    }
}
