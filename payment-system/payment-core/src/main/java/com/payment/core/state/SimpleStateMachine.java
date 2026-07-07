package com.payment.core.state;

import lombok.Getter;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 轻量级状态机 - 不依赖 Spring StateMachine
 * <p>
 * 适用于对性能要求较高的场景，避免 Spring StateMachine 的状态持久化开销。
 *
 * @param <S> 状态枚举类型
 * @param <E> 事件枚举类型
 */
public class SimpleStateMachine<S extends Enum<S>, E extends Enum<E>> {

    /** 状态转换表：当前状态 → (事件 → 目标状态) */
    private final Map<S, Map<E, S>> transitions;

    /** 每个状态的进入动作 */
    private final Map<S, Consumer<StateContext<S, E>>> entryActions;

    /** 每个状态的退出动作 */
    private final Map<S, Consumer<StateContext<S, E>>> exitActions;

    public SimpleStateMachine(Class<S> stateType, Class<E> eventType) {
        this.transitions = new EnumMap<>(stateType);
        this.entryActions = new EnumMap<>(stateType);
        this.exitActions = new EnumMap<>(stateType);
    }

    /**
     * 添加状态转换
     */
    public SimpleStateMachine<S, E> addTransition(S source, E event, S target) {
        transitions.computeIfAbsent(source, k -> new EnumMap<>(event.getClass()))
                .put(event, target);
        return this;
    }

    /**
     * 添加进入动作
     */
    public SimpleStateMachine<S, E> onEntry(S state, Consumer<StateContext<S, E>> action) {
        entryActions.put(state, action);
        return this;
    }

    /**
     * 添加退出动作
     */
    public SimpleStateMachine<S, E> onExit(S state, Consumer<StateContext<S, E>> action) {
        exitActions.put(state, action);
        return this;
    }

    /**
     * 触发事件
     *
     * @param currentState 当前状态
     * @param event        触发事件
     * @param orderId      业务 ID（用于上下文）
     * @return 状态转换上下文
     * @throws IllegalStateException 如果当前状态不允许该事件
     */
    public StateContext<S, E> fire(S currentState, E event, String orderId) {
        Map<E, S> stateTransitions = transitions.get(currentState);
        if (stateTransitions == null || !stateTransitions.containsKey(event)) {
            throw new IllegalStateException(
                    String.format("不允许的状态转换: 当前状态=%s, 事件=%s", currentState, event));
        }

        S targetState = stateTransitions.get(event);

        // 执行退出动作
        if (exitActions.containsKey(currentState)) {
            exitActions.get(currentState)
                    .accept(new StateContext<>(currentState, targetState, event, orderId));
        }

        // 执行进入动作
        if (entryActions.containsKey(targetState)) {
            entryActions.get(targetState)
                    .accept(new StateContext<>(currentState, targetState, event, orderId));
        }

        return new StateContext<>(currentState, targetState, event, orderId);
    }

    /**
     * 状态转换上下文
     */
    @Getter
    public static class StateContext<S, E> {
        private final S source;
        private final S target;
        private final E event;
        private final String orderId;

        public StateContext(S source, S target, E event, String orderId) {
            this.source = source;
            this.target = target;
            this.event = event;
            this.orderId = orderId;
        }
    }
}
