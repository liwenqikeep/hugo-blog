package com.payment.channel.registry;

import com.payment.channel.spi.ChannelAdapter;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 渠道适配器注册中心
 * Spring 自动注入所有 ChannelAdapter 实现
 */
@Component
public class ChannelAdapterRegistry {

    /** channel -> adapter 映射 */
    private final Map<String, ChannelAdapter> adapterMap;

    public ChannelAdapterRegistry(List<ChannelAdapter> adapters) {
        this.adapterMap = adapters.stream()
                .collect(Collectors.toMap(
                        ChannelAdapter::getChannel,
                        Function.identity(),
                        (a, b) -> { throw new IllegalStateException("重复的渠道: " + a.getChannel()); }
                ));
    }

    /**
     * 获取渠道适配器
     */
    public ChannelAdapter getAdapter(String channel) {
        ChannelAdapter adapter = adapterMap.get(channel);
        if (adapter == null) {
            throw new IllegalArgumentException("不支持的支付渠道: " + channel);
        }
        return adapter;
    }

    /**
     * 获取所有支持的渠道列表
     */
    public Set<String> getSupportedChannels() {
        return adapterMap.keySet();
    }
}
