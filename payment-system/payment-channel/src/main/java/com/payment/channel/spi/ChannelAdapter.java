package com.payment.channel.spi;

import java.util.Map;

/**
 * 渠道适配器接口
 * 所有支付渠道必须实现此接口
 */
public interface ChannelAdapter {

    /**
     * 渠道标识
     */
    String getChannel();

    /**
     * 发起支付
     */
    PayResponse pay(PayRequest request);

    /**
     * 发起退款
     */
    RefundResponse refund(RefundRequest request);

    /**
     * 查询交易状态
     */
    QueryResponse query(QueryRequest request);

    /**
     * 解析渠道回调通知
     */
    NotifyResult parseNotify(Map<String, String> notifyParams);
}
