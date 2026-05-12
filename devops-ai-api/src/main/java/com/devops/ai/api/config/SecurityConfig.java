package com.devops.ai.api.config;

import com.devops.ai.infrastructure.entity.ApiToken;
import com.devops.ai.infrastructure.repository.ApiTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;

import javax.servlet.Filter;
import javax.servlet.http.HttpServletRequest;
import java.util.Date;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Configuration
    @Order(1)
    public static class ApiSecurityConfig extends WebSecurityConfigurerAdapter {

        private final ApiTokenRepository apiTokenRepository;

        public ApiSecurityConfig(ApiTokenRepository apiTokenRepository) {
            this.apiTokenRepository = apiTokenRepository;
        }

        @Override
        protected void configure(HttpSecurity http) throws Exception {
            http
                    .antMatcher("/api/**")
                    .authorizeRequests()
                    .antMatchers("/api-docs/**","/api/**", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                    .anyRequest().authenticated()
                    .and()
                    .addFilterBefore(apiTokenFilter(), org.springframework.security.web.authentication.www.BasicAuthenticationFilter.class)
                    .csrf().disable()
                    .sessionManagement().disable();
        }

        private Filter apiTokenFilter() {
            return (request, response, chain) -> {
                if (request instanceof HttpServletRequest) {
                    String authHeader = ((HttpServletRequest) request).getHeader("Authorization");
                    if (authHeader != null && authHeader.startsWith("Bearer ")) {
                        String token = authHeader.substring(7);
                        try {
                            ApiToken apiToken = apiTokenRepository.findByTokenAndActiveTrue(token);
                            if (apiToken != null && apiToken.getActive()) {
                                if (apiToken.getExpiresAt() != null && apiToken.getExpiresAt().before(new Date())) {
                                    log.warn("API token expired: {}", apiToken.getId());
                                } else {
                                    chain.doFilter(request, response);
                                    return;
                                }
                            }
                        } catch (Exception e) {
                            log.warn("API token validation error: {}", e.getMessage(), e);
                        }
                    }
                }
                chain.doFilter(request, response);
            };
        }
    }

    @Configuration
    @Order(2)
    public static class WebSecurityConfig extends WebSecurityConfigurerAdapter {

        @Override
        protected void configure(HttpSecurity http) throws Exception {
            http.authorizeRequests()
                    .antMatchers("/**").permitAll()
                    .and()
                    .csrf().disable()
                    .headers().frameOptions().disable();
        }
    }
}
