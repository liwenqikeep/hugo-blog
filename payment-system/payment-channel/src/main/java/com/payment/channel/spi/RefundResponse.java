package com.payment.channel.spi;

import lombok.Data;

/**
 * 退款响应
 */
@Data
public class RefundResponse {

    private boolean success;
    private String errorCode;
    private String errorMessage;
    private String channelRefundId;
}
