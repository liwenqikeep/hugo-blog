package com.payment.core.state;

import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.listener.StateMachineListenerAdapter;
import org.springframework.statemachine.state.State;
import org.springframework.stereotype.Component;

/**
 * 支付单状态变更监听器
 */
@Slf4j
@Component
public class PayOrderStateChangeListener
        extends StateMachineListenerAdapter<PayOrderState, PayOrderEvent> {

    @Override
    public void stateChanged(State<PayOrderState, PayOrderEvent> from,
                              State<PayOrderState, PayOrderEvent> to) {
        PayOrderState fromState = from != null ? from.getId() : null;
        PayOrderState toState = to.getId();

        log.info("支付单状态变更: {} → {}", fromState, toState);

        switch (toState) {
            case SUCCESS -> {
                log.info("触发支付成功处理: 入账、发事件、通知商户");
                // 1. 记账入账
                // 2. 发布 PaymentSucceededEvent
                // 3. 通知商户
            }
            case FAIL -> {
                log.info("触发支付失败处理: 判断重试、发事件");
                // 1. 判断是否需要重试
                // 2. 发布 PaymentFailedEvent
            }
            case REFUNDED -> {
                log.info("触发退款完成处理: 解冻资金");
                // 1. 解冻资金
                // 2. 发布 RefundCompletedEvent
            }
        }
    }

    @Override
    public void eventNotAccepted(PayOrderEvent event) {
        log.warn("非法状态转换: 事件 {} 不被当前状态接受", event);
        throw new IllegalStateException("当前状态不允许执行: " + event);
    }
}
