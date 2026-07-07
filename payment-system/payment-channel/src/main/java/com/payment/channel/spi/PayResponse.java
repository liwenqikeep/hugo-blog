package com.payment.channel.spi;

import lombok.Data;

/**
 * 支付响应
 */
@Data
public class PayResponse {

    /** 是否成功 */
    private boolean success;

    /** 错误码 */
    private String errorCode;

    /** 错误信息 */
    private String errorMessage;

    /** 渠道交易号 */
    private String channelOrderId;

    /** H5 支付链接 */
    private String payUrl;

    /** 二维码内容 */
    private String qrCode;

    /** 渠道原始响应 */
    private String rawResponse;
}
