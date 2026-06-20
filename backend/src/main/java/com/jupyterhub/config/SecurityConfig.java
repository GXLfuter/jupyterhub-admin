package com.jupyterhub.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security安全配置
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf().disable()
            .cors().and()
            .authorizeRequests()
                // 登录接口允许匿名访问
                .antMatchers("/auth/login", "/auth/validate").permitAll()
                // 测试接口允许访问
                .antMatchers("/test/**").permitAll()
                // WebSocket端点允许访问
                .antMatchers("/ws/**", "/api/ws/**").permitAll()
                // H2控制台允许访问
                .antMatchers("/h2-console/**").permitAll()
                // 静态资源和前端代理
                .antMatchers("/**").permitAll()
                // 其他请求需要认证
                .anyRequest().authenticated()
            .and()
            .headers().frameOptions().disable();

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
