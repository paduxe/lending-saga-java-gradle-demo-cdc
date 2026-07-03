package com.example.lending.orchestrator.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
public class TracingLoggingFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        String traceId = Optional.ofNullable(http.getHeader("X-Trace-Id"))
                .orElse(UUID.randomUUID().toString());
        String sagaId = Optional.ofNullable(http.getHeader("X-Saga-Id")).orElse("N/A");

        MDC.put("traceId", traceId);
        MDC.put("sagaId", sagaId);

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
