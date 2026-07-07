package com.payment.core.domain.event;

import com.payment.core.domain.model.Money;

/**
 * 支付失败事件
 */
public class PaymentFailedEvent extends DomainEvent {

    private final String paymentOrderId;
    private final String bizOrderNo;
    private final String failReason;
    private final String channel;

    public PaymentFailedEvent(String paymentOrderId, String bizOrderNo,
                               String failReason, String channel) {
        this.paymentOrderId = paymentOrderId;
        this.bizOrderNo = bizOrderNo;
        this.failReason = failReason;
        this.channel = channel;
    }

    public String getPaymentOrderId() { return paymentOrderId; }
    public String getBizOrderNo() { return bizOrderNo; }
    public String getFailReason() { return failReason; }
    public String getChannel() { return channel; }
}
