package com.hunt.otziv.u_users.config;

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

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        String requestURI = ((HttpServletRequest) request).getRequestURI();

        if (containsInvalidCharacters(requestURI)) {
            ((HttpServletResponse) response).sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid characters in request");
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean containsInvalidCharacters(String value) {
        String invalidChars = "[$|`]";
        Pattern pattern = Pattern.compile(invalidChars);
        Matcher matcher = pattern.matcher(value);
        return matcher.find();
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
