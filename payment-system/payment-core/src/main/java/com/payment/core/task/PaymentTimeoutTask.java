package com.payment.core.state;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 支付超时关闭定时任务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentTimeoutTask {

    private final PayOrderStateMachineService stateMachineService;

    /** 超时时间，单位：分钟 */
    private static final long TIMEOUT_MINUTES = 30;

    /**
     * 每分钟扫描超时未支付的支付单，自动关闭
     */
    @Scheduled(fixedRate = 60_000)
    public void closeTimeoutOrders() {
        // 实际开发中，这里调用仓储层查询超时支付单
        // List<PaymentOrder> timeoutOrders = paymentOrderRepository
        //         .findByStatusAndCreateTimeBefore("INIT", LocalDateTime.now().minusMinutes(TIMEOUT_MINUTES));
        //
        // for (PaymentOrder order : timeoutOrders) {
        //     try {
        //         stateMachineService.sendEvent(order.getOrderId(), PayOrderEvent.PAY_CANCEL);
        //         log.info("超时关闭支付单: {}", order.getOrderId());
        //     } catch (Exception e) {
        //         log.error("关闭超时支付单失败: {}", order.getOrderId(), e);
        //     }
        // }

        log.debug("支付超时检测任务执行完成");
    }
}
