package com.payment.core.domain.event;

import com.payment.core.domain.model.Money;

/**
 * 支付成功事件
 */
public class PaymentSucceededEvent extends DomainEvent {

    private final String paymentOrderId;
    private final String bizOrderNo;
    private final Money paidAmount;
    private final String channel;

    public PaymentSucceededEvent(String paymentOrderId, String bizOrderNo,
                                  Money paidAmount, String channel) {
        this.paymentOrderId = paymentOrderId;
        this.bizOrderNo = bizOrderNo;
        this.paidAmount = paidAmount;
        this.channel = channel;
    }

    public String getPaymentOrderId() { return paymentOrderId; }
    public String getBizOrderNo() { return bizOrderNo; }
    public Money getPaidAmount() { return paidAmount; }
    public String getChannel() { return channel; }
}
