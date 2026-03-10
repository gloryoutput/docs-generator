package io.github.gloryoutput.docsgenerator.config;

import org.h2.server.web.JakartaWebServlet;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * H2 Console 서블릿 수동 등록 (dev 프로필 전용)
 *
 * <p>Spring Boot 4.x에서 H2 콘솔 자동 설정이 제거되어 수동으로 등록합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Configuration
@Profile("dev")
public class H2ConsoleConfig {
    @Bean
    public ServletRegistrationBean<JakartaWebServlet> h2Console() {
        ServletRegistrationBean<JakartaWebServlet> registration =
                new ServletRegistrationBean<>(new JakartaWebServlet(), "/h2-console/*");
        registration.setLoadOnStartup(1);
        return registration;
    }
}
