package com.example.lending.loan.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Tắt CSRF vì Microservices giao tiếp qua API không dùng Session Browser
            .csrf(csrf -> csrf.disable())
            // Yêu cầu mọi API (/api/loans/**) đều phải có Token hợp lệ
            .authorizeHttpRequests(authz -> authz
                // Các endpoint Healthcheck, Prometheus thì mở cửa tự do
                .requestMatchers("/actuator/**").permitAll()
                // Còn lại bắt buộc phải đi qua cổng xác thực
                .anyRequest().authenticated()
            )
            // Kích hoạt chế độ máy chủ tài nguyên (Resource Server), bắt buộc xác thực bằng JWT
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}));
            
        return http.build();
    }
}
