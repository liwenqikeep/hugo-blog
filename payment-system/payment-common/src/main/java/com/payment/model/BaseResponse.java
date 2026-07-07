package com.payment.model;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应体
 */
@Data
public class BaseResponse<T> implements Serializable {

    private String code;
    private String message;
    private String requestId;
    private T data;

    public static <T> BaseResponse<T> success(T data) {
        BaseResponse<T> response = new BaseResponse<>();
        response.setCode("SUCCESS");
        response.setMessage("成功");
        response.setData(data);
        return response;
    }

    public static <T> BaseResponse<T> success(String requestId, T data) {
        BaseResponse<T> response = success(data);
        response.setRequestId(requestId);
        return response;
    }

    public static <T> BaseResponse<T> fail(String code, String message) {
        BaseResponse<T> response = new BaseResponse<>();
        response.setCode(code);
        response.setMessage(message);
        return response;
    }

    public boolean isSuccess() {
        return "SUCCESS".equals(code);
    }
}
