package com.bovae.yac;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class YacApplication {

    public static void main(String[] args) {
        SpringApplication.run(YacApplication.class, args);
    }
}
