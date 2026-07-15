package com.bovae.yac.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI yacOpenAPI(BuildProperties buildProperties) {
        return new OpenAPI()
                .info(new Info()
                        .title(buildProperties.getName())
                        .version(buildProperties.getVersion()));
    }
}
