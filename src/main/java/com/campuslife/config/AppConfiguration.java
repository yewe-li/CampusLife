package com.campuslife.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration
public class AppConfiguration {
    @Bean public Clock clock() { return Clock.systemUTC(); }
    @Bean public OpenAPI openAPI() {
        return new OpenAPI().info(new Info().title("CampusLife API").version("1.0.0")
                .description("校园优惠教学项目。金额单位：分；时间：UTC。dev 模式使用模拟验证码，无真实短信与支付。"))
            .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                .type(SecurityScheme.Type.HTTP).scheme("bearer").description("登录接口返回的随机会话 token")));
    }
}
