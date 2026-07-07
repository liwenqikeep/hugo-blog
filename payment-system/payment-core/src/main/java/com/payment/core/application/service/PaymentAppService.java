package com.payment.core.application.service;

import com.payment.core.domain.event.PaymentFailedEvent;
import com.payment.core.domain.event.PaymentSucceededEvent;
import com.payment.core.domain.model.Money;
import com.payment.core.domain.model.PaymentOrder;
import com.payment.core.domain.model.Transaction;
import com.payment.core.domain.repository.PaymentOrderRepository;
import com.payment.core.domain.service.ChannelRoutingService;
import com.payment.core.domain.service.IdempotencyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 支付应用服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentAppService {

    private final PaymentOrderRepository paymentOrderRepository;
    private final ChannelRoutingService channelRoutingService;
    private final IdempotencyService idempotencyService;

    /**
     * 创建支付单
     */
    @Transactional
    public PaymentOrder createPayment(String bizOrderNo, Money amount,
                                       String userId, String merchantId) {
        // 幂等检查
        String idempotentKey = "create_payment:" + bizOrderNo;
        PaymentOrder existing = idempotencyService.tryProcess(idempotentKey);
        if (existing != null) {
            log.info("幂等命中，返回已有支付单: {}", existing.getOrderId());
            return existing;
        }

        // 创建支付单
        String orderId = generateOrderId();
        PaymentOrder order = PaymentOrder.create(orderId, bizOrderNo, amount, userId, merchantId);

        // 渠道路由
        ChannelRoutingService.RouteResult route = channelRoutingService.route(amount, userId, merchantId);
        order.startPay(route.getChannel());

        // 持久化
        paymentOrderRepository.save(order);

        // 保存幂等结果
        idempotencyService.saveResult(idempotentKey, order);

        log.info("创建支付单成功: orderId={}, amount={}, channel={}", orderId, amount.toYuanString(), route.getChannel());
        return order;
    }

    /**
     * 处理支付回调
     */
    @Transactional
    public PaymentOrder handleCallback(String transactionId, boolean success, String channelResponse) {
        // 查询流水和支付单
        Transaction transaction = findTransaction(transactionId);
        PaymentOrder order = paymentOrderRepository.findById(transaction.getPaymentOrderId())
                .orElseThrow(() -> new RuntimeException("支付单不存在: " + transaction.getPaymentOrderId()));

        if (success) {
            transaction.markSuccess(channelResponse);
            order.complete();
            paymentOrderRepository.updateTransaction(transaction);
            paymentOrderRepository.save(order);

            // 发布支付成功事件
            publishPaymentSucceeded(order);
            log.info("支付成功: orderId={}, transactionId={}", order.getOrderId(), transactionId);
        } else {
            transaction.markFail(channelResponse);
            order.fail(channelResponse);
            paymentOrderRepository.updateTransaction(transaction);
            paymentOrderRepository.save(order);

            // 发布支付失败事件
            publishPaymentFailed(order);
            log.warn("支付失败: orderId={}, transactionId={}, reason={}", order.getOrderId(), transactionId, channelResponse);
        }

        return order;
    }

    private void publishPaymentSucceeded(PaymentOrder order) {
        PaymentSucceededEvent event = new PaymentSucceededEvent(
                order.getOrderId(), order.getBizOrderNo(), order.getAmount(), order.getChannel());
        // TODO: 通过事件总线发布 event
        log.info("发布支付成功事件: {}", event.getEventId());
    }

    private void publishPaymentFailed(PaymentOrder order) {
        PaymentFailedEvent event = new PaymentFailedEvent(
                order.getOrderId(), order.getBizOrderNo(), order.getFailReason(), order.getChannel());
        // TODO: 通过事件总线发布 event
        log.info("发布支付失败事件: {}", event.getEventId());
    }

    private String generateOrderId() {
        return "PAY" + System.currentTimeMillis();
    }

    private Transaction findTransaction(String transactionId) {
        // TODO: 从仓储查询 Transaction
        return null;
    }
}
