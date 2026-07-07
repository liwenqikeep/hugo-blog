package com.payment.core.domain.service;

import com.payment.core.domain.model.PaymentOrder;
import com.payment.core.domain.model.Money;

import java.util.Map;

/**
 * 渠道路由服务
 */
public interface ChannelRoutingService {

    /**
     * 路由决策
     *
     * @param amount    支付金额
     * @param userId    用户 ID
     * @param merchantId 商户 ID
     * @return 路由结果
     */
    RouteResult route(Money amount, String userId, String merchantId);

    class RouteResult {
        private final String channel;
        private final Map<String, String> channelConfig;
        private final boolean downgraded;

        public RouteResult(String channel, Map<String, String> channelConfig, boolean downgraded) {
            this.channel = channel;
            this.channelConfig = channelConfig;
            this.downgraded = downgraded;
        }

        public String getChannel() { return channel; }
        public Map<String, String> getChannelConfig() { return channelConfig; }
        public boolean isDowngraded() { return downgraded; }
    }
}
