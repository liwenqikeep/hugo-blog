package com.payment.channel.spi;

import lombok.Data;

/**
 * 查询响应
 */
@Data
public class QueryResponse {

    private boolean success;
    private String channelOrderId;
    private String channelResult;
    private String channelStatus;  // 渠道侧的最新状态
}
