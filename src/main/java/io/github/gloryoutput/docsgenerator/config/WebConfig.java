package io.github.gloryoutput.docsgenerator.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 설정
 *
 * <p>Swagger UI, H2 Console 등 정적 리소스 경로를 설정합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Swagger UI 루트 접근 시 리다이렉트
        registry.addRedirectViewController("/docs", "/swagger-ui/index.html");
    }
}
