package com.payment.core.state;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.config.EnableStateMachine;
import org.springframework.statemachine.config.StateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;

import java.util.EnumSet;

/**
 * 支付单状态机配置
 */
@Configuration
@EnableStateMachine(name = "payOrderStateMachine")
public class PayOrderStateMachineConfig
        extends StateMachineConfigurerAdapter<PayOrderState, PayOrderEvent> {

    @Override
    public void configure(StateMachineStateConfigurer<PayOrderState, PayOrderEvent> states)
            throws Exception {
        states
            .withStates()
                .initial(PayOrderState.INIT)
                .states(EnumSet.allOf(PayOrderState.class))
                .end(PayOrderState.SUCCESS)
                .end(PayOrderState.FAIL)
                .end(PayOrderState.CLOSED)
                .end(PayOrderState.REFUNDED);
    }

    @Override
    public void configure(
            StateMachineTransitionConfigurer<PayOrderState, PayOrderEvent> transitions)
            throws Exception {
        transitions
            // INIT → PAYING
            .withExternal()
                .source(PayOrderState.INIT)
                .target(PayOrderState.PAYING)
                .event(PayOrderEvent.PAY_START)

            // INIT → CLOSED
            .and().withExternal()
                .source(PayOrderState.INIT)
                .target(PayOrderState.CLOSED)
                .event(PayOrderEvent.PAY_CANCEL)

            // PAYING → SUCCESS
            .and().withExternal()
                .source(PayOrderState.PAYING)
                .target(PayOrderState.SUCCESS)
                .event(PayOrderEvent.PAY_SUCCESS)

            // PAYING → FAIL
            .and().withExternal()
                .source(PayOrderState.PAYING)
                .target(PayOrderState.FAIL)
                .event(PayOrderEvent.PAY_FAIL)

            // FAIL → PAYING（重试）
            .and().withExternal()
                .source(PayOrderState.FAIL)
                .target(PayOrderState.PAYING)
                .event(PayOrderEvent.PAY_START)

            // SUCCESS → REFUNDING
            .and().withExternal()
                .source(PayOrderState.SUCCESS)
                .target(PayOrderState.REFUNDING)
                .event(PayOrderEvent.REFUND_START)

            // REFUNDING → REFUNDED
            .and().withExternal()
                .source(PayOrderState.REFUNDING)
                .target(PayOrderState.REFUNDED)
                .event(PayOrderEvent.REFUND_COMPLETE);
    }
}
