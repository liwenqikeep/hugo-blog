package com.payment.channel.wechat;

import com.payment.channel.spi.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 微信支付适配器
 */
@Slf4j
@Component
public class WechatPayAdapter implements ChannelAdapter {

    @Override
    public String getChannel() {
        return "WECHAT";
    }

    @Override
    public PayResponse pay(PayRequest request) {
        log.info("微信支付: orderId={}, amount={}", request.getOrderId(), request.getAmount());

        // 实际开发中：调用微信支付 SDK
        // WechatPayResponse wechatResp = wechatApi.createOrder(wechatReq);

        PayResponse response = new PayResponse();
        response.setSuccess(true);
        response.setChannelOrderId("WX" + System.currentTimeMillis());
        response.setPayUrl("https://wx.tenpay.com/pay/" + request.getOrderId());
        return response;
    }

    @Override
    public RefundResponse refund(RefundRequest request) {
        log.info("微信退款: refundOrderId={}, amount={}", request.getRefundOrderId(), request.getRefundAmount());
        RefundResponse response = new RefundResponse();
        response.setSuccess(true);
        response.setChannelRefundId("WX_REFUND_" + System.currentTimeMillis());
        return response;
    }

    @Override
    public QueryResponse query(QueryRequest request) {
        log.info("微信查询: orderId={}", request.getOrderId());
        QueryResponse response = new QueryResponse();
        response.setSuccess(true);
        response.setChannelStatus("SUCCESS");
        return response;
    }

    @Override
    public NotifyResult parseNotify(Map<String, String> notifyParams) {
        log.info("解析微信回调: params={}", notifyParams);

        // 实际开发中：验证签名 + 解析参数
        NotifyResult result = new NotifyResult();
        result.setSuccess(true);
        result.setChannelTransactionId(notifyParams.get("transaction_id"));
        result.setOrderId(notifyParams.get("out_trade_no"));
        result.setChannelAck("SUCCESS");
        return result;
    }
}
