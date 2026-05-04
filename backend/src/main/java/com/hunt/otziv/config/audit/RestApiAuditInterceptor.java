package com.hunt.otziv.config.audit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.security.Principal;

@Component
@RequiredArgsConstructor
public class RestApiAuditInterceptor implements HandlerInterceptor {

    private static final Logger auditLog = LoggerFactory.getLogger("ACCESS_LOGGER");

    private final RestApiAuditActionResolver actionResolver;

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception
    ) {
        if (!(handler instanceof HandlerMethod handlerMethod) || !isRestEndpoint(handlerMethod)) {
            return;
        }

        String action = actionResolver.resolve(request, handlerMethod);
        String login = currentLogin(request);
        String method = request.getMethod();
        String path = request.getRequestURI();
        int status = response.getStatus();

        if (exception == null) {
            auditLog.info("{} {} | httpStatus={} | {} - {}", method, path, status, action, login);
            return;
        }

        auditLog.info(
                "{} {} | httpStatus={} | {} - {} | error={}",
                method,
                path,
                status,
                action,
                login,
                exception.getClass().getSimpleName()
        );
    }

    private boolean isRestEndpoint(HandlerMethod handlerMethod) {
        return AnnotationUtils.findAnnotation(handlerMethod.getBeanType(), RestController.class) != null
                || AnnotationUtils.findAnnotation(handlerMethod.getMethod(), ResponseBody.class) != null
                || ResponseEntity.class.isAssignableFrom(handlerMethod.getMethod().getReturnType());
    }

    private String currentLogin(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
                && authentication.getName() != null
                && !authentication.getName().isBlank()) {
            return authentication.getName();
        }

        Principal principal = request.getUserPrincipal();
        if (principal != null && principal.getName() != null && !principal.getName().isBlank()) {
            return principal.getName();
        }

        return "anonymous";
    }
}
