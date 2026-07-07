package com.payment.channel.handler;

import com.payment.channel.registry.ChannelAdapterRegistry;
import com.payment.channel.spi.ChannelAdapter;
import com.payment.channel.spi.NotifyResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 统一回调处理器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelNotifyHandler {

    private final ChannelAdapterRegistry adapterRegistry;

    /**
     * 统一处理渠道回调
     *
     * @param channel      渠道标识
     * @param notifyParams 渠道回调参数
     * @return 解析结果
     */
    public NotifyResult handleNotify(String channel, Map<String, String> notifyParams) {
        // 1. 获取适配器
        ChannelAdapter adapter = adapterRegistry.getAdapter(channel);

        // 2. 解析回调（包括验签）
        NotifyResult notifyResult = adapter.parseNotify(notifyParams);

        // 3. 实际开发中调用 PayCallbackService 处理回调
        if (notifyResult.isSuccess()) {
            log.info("回调处理成功: channel={}, orderId={}, channelTxId={}",
                    channel, notifyResult.getOrderId(), notifyResult.getChannelTransactionId());
            // payCallbackService.handlePayCallback(notifyResult.getTransactionId(), notifyResult.getChannelResult());
        }

        return notifyResult;
    }
}
