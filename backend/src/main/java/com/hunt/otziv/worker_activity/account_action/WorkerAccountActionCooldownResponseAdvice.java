package com.hunt.otziv.worker_activity.account_action;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Slow assignment work must not restart a full minute when its response reaches the browser. */
@ControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class WorkerAccountActionCooldownResponseAdvice implements ResponseBodyAdvice<Object>, WebMvcConfigurer, HandlerInterceptor {
    private static final String RESPONSE_STATE_ATTRIBUTE = WorkerAccountActionCooldownResponseAdvice.class.getName() + ".state";
    private final WorkerAccountActionCooldownRepository repository;

    @Override
    public boolean supports(MethodParameter method, Class<? extends HttpMessageConverter<?>> converter) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter method, MediaType mediaType,
                                  Class<? extends HttpMessageConverter<?>> converter,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            var state = responseState(servletRequest.getServletRequest());
            if (state != null) {
                WorkerAccountActionCooldownHeaders.write(state, response.getHeaders()::set);
            }
        }
        return body;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Legacy card endpoints render HTML fragments, bypassing ResponseBodyAdvice.
        registry.addInterceptor(this);
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                           ModelAndView modelAndView) {
        var state = responseState(request);
        if (state != null && !response.isCommitted()) {
            WorkerAccountActionCooldownHeaders.write(state, response::setHeader);
        }
    }

    private WorkerAccountActionCooldownState responseState(HttpServletRequest request) {
        if (request.getAttribute(RESPONSE_STATE_ATTRIBUTE) instanceof WorkerAccountActionCooldownState state) {
            return state;
        }
        Object accepted = request.getAttribute(WorkerAccountActionCooldownService.ACCEPTED_REQUEST_ATTRIBUTE);
        if (!(accepted instanceof WorkerAccountActionCooldownService.AcceptedAction action)) {
            return null;
        }
        WorkerAccountActionCooldownState state;
        try {
            // A switch changed during a slow mutation must take effect in its eventual response too.
            state = Objects.requireNonNull(repository.currentState(action.username()));
        } catch (RuntimeException exception) {
            log.warn("Не удалось обновить состояние таймера в ответе выполненного действия", exception);
            // Keep the original observation time: stale policy must not overwrite a newer UI state.
            // The action itself has already completed and must not fail because this optional read failed.
            state = action.state();
        }
        request.setAttribute(RESPONSE_STATE_ATTRIBUTE, state);
        return state;
    }
}
