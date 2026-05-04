package com.hunt.otziv.config.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import org.springframework.http.server.ServletServerHttpRequest;

import java.lang.reflect.Type;

@Component
@ControllerAdvice
public class RestApiAuditRequestBodyAdvice extends RequestBodyAdviceAdapter {

    @Override
    public boolean supports(
            MethodParameter methodParameter,
            Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType
    ) {
        return true;
    }

    @Override
    public Object afterBodyRead(
            Object body,
            HttpInputMessage inputMessage,
            MethodParameter parameter,
            Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType
    ) {
        if (inputMessage instanceof ServletServerHttpRequest servletRequest) {
            HttpServletRequest request = servletRequest.getServletRequest();
            request.setAttribute(RestApiAuditActionResolver.REQUEST_BODY_ATTRIBUTE, body);
        }

        return body;
    }
}
