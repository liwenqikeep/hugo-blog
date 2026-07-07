package com.payment.channel.alipay;

import com.payment.channel.spi.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 支付宝支付适配器
 */
@Slf4j
@Component
public class AlipayAdapter implements ChannelAdapter {

    @Override
    public String getChannel() {
        return "ALIPAY";
    }

    @Override
    public PayResponse pay(PayRequest request) {
        log.info("支付宝支付: orderId={}, amount={}", request.getOrderId(), request.getAmount());

        // 实际开发中：调用支付宝 SDK
        PayResponse response = new PayResponse();
        response.setSuccess(true);
        response.setChannelOrderId("ALI" + System.currentTimeMillis());
        response.setQrCode("https://qr.alipay.com/" + request.getOrderId());
        return response;
    }

    @Override
    public RefundResponse refund(RefundRequest request) {
        log.info("支付宝退款: refundOrderId={}", request.getRefundOrderId());
        RefundResponse response = new RefundResponse();
        response.setSuccess(true);
        response.setChannelRefundId("ALI_REFUND_" + System.currentTimeMillis());
        return response;
    }

    @Override
    public QueryResponse query(QueryRequest request) {
        log.info("支付宝查询: orderId={}", request.getOrderId());
        QueryResponse response = new QueryResponse();
        response.setSuccess(true);
        response.setChannelStatus("SUCCESS");
        return response;
    }

    @Override
    public NotifyResult parseNotify(Map<String, String> notifyParams) {
        log.info("解析支付宝回调: params={}", notifyParams);

        NotifyResult result = new NotifyResult();
        result.setSuccess(true);
        result.setChannelTransactionId(notifyParams.get("trade_no"));
        result.setOrderId(notifyParams.get("out_trade_no"));
        result.setChannelAck("success");
        return result;
    }
}
