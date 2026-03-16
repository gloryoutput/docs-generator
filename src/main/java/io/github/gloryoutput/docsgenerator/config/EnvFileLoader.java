package io.github.gloryoutput.docsgenerator.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 환경별 .env 파일을 로드하는 EnvironmentPostProcessor
 *
 * <p>활성 프로필에 따라 .env.{profile} 파일을 읽어 환경 변수로 등록합니다.
 * 프로필이 없으면 기본 .env 파일을 로드합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@SuppressWarnings("removal")
public class EnvFileLoader implements EnvironmentPostProcessor {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String[] activeProfiles = environment.getActiveProfiles();
        // 활성 프로필별 .env 파일 로드
        if (activeProfiles.length > 0) {
            for (String profile : activeProfiles) {
                loadEnvFile(environment, "deploy/.env." + profile);
            }
        } else {
            loadEnvFile(environment, "deploy/.env");
        }
    }

    private void loadEnvFile(ConfigurableEnvironment environment, String fileName) {
        Path envPath = Path.of(fileName).toAbsolutePath();
        System.out.println("[EnvFileLoader] 파일 탐색: " + envPath);
        if (!Files.exists(envPath)) {
            System.out.println("[EnvFileLoader] 파일 없음: " + envPath);
            return;
        }
        try {
            Map<String, Object> properties = new HashMap<>();
            Files.readAllLines(envPath).forEach(line -> {
                line = line.trim();
                // 빈 줄과 주석 무시
                if (line.isEmpty() || line.startsWith("#")) {
                    return;
                }
                int idx = line.indexOf('=');
                if (idx > 0) {
                    String key = line.substring(0, idx).trim();
                    String value = line.substring(idx + 1).trim();
                    properties.put(key, value);
                }
            });
            if (!properties.isEmpty()) {
                System.out.println("[EnvFileLoader] " + fileName + " 로드 완료 - keys: " + properties.keySet());
                environment.getPropertySources()
                        .addFirst(new MapPropertySource("envFile-" + fileName, properties));
            }
        } catch (IOException e) {
            // .env 파일 로드 실패 시 무시 (선택적 설정)
        }
    }
}
