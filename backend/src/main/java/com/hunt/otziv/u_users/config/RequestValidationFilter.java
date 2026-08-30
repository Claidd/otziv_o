package com.hunt.otziv.u_users.config;

import com.hunt.otziv.config.jwt.service.BodyTooLargeException;
import com.hunt.otziv.config.jwt.service.CachedBodyHttpServletRequest;
import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@WebFilter(urlPatterns = "/*")
public class RequestValidationFilter implements Filter {

    private static final int TOCHKA_WEBHOOK_MAX_BODY_BYTES = 65_536;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        String requestURI = applicationPath(httpRequest);

        if (containsInvalidCharacters(httpRequest.getRequestURI())) {
            httpResponse.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid characters in request");
            return;
        }

        if ("POST".equalsIgnoreCase(httpRequest.getMethod())
                && matchesEndpoint(requestURI, "/api/payments/tochka/webhook")) {
            try {
                chain.doFilter(
                        new CachedBodyHttpServletRequest(httpRequest, TOCHKA_WEBHOOK_MAX_BODY_BYTES),
                        response
                );
            } catch (BodyTooLargeException exception) {
                httpResponse.sendError(
                        HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                        "Tochka webhook body exceeds byte limit"
                );
            }
            return;
        }

        if ("POST".equalsIgnoreCase(httpRequest.getMethod())
                && matchesEndpoint(requestURI, "/webhook/max")) {
            try {
                chain.doFilter(new CachedBodyHttpServletRequest(httpRequest, 262_144), response);
            } catch (BodyTooLargeException exception) {
                httpResponse.sendError(
                        HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                        "MAX webhook body exceeds byte limit"
                );
            }
            return;
        }

        if ("POST".equalsIgnoreCase(httpRequest.getMethod())
                && (matchesEndpoint(requestURI, "/api/auth/register")
                || matchesEndpoint(requestURI, "/api/auth/register-performer"))) {
            try {
                chain.doFilter(new CachedBodyHttpServletRequest(httpRequest, 32_768), response);
            } catch (BodyTooLargeException exception) {
                httpResponse.sendError(
                        HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                        "Registration request body exceeds byte limit"
                );
            }
            return;
        }

        if ("POST".equalsIgnoreCase(httpRequest.getMethod())
                && matchesEndpoint(requestURI, "/register")) {
            long contentLength = request.getContentLengthLong();
            // Servlet multipart parsing can bypass an input-stream wrapper through getParts().
            // Requiring a known framed length closes chunked-size bypasses without breaking MultipartFile.
            if (contentLength < 0) {
                httpResponse.sendError(HttpServletResponse.SC_LENGTH_REQUIRED, "Content-Length is required");
                return;
            }
            if (contentLength > 5_242_880L) {
                httpResponse.sendError(
                        HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                        "Registration request body exceeds byte limit"
                );
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private boolean containsInvalidCharacters(String value) {
        String invalidChars = "[$|`]";
        Pattern pattern = Pattern.compile(invalidChars);
        Matcher matcher = pattern.matcher(value);
        return matcher.find();
    }

    private static String applicationPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        return contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)
                ? path.substring(contextPath.length())
                : path;
    }

    private static boolean matchesEndpoint(String path, String endpoint) {
        return endpoint.equals(path) || path.startsWith(endpoint + ";");
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // Initialization code, if needed
    }

    @Override
    public void destroy() {
        // Cleanup code, if needed
    }
}
