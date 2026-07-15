package com.bovae.yac.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "app.file-storage")
public record FileStorageProperties(
        @DefaultValue("./file-storage") String basePath,
        @DefaultValue("20MB") DataSize maxFileSize,
        @DefaultValue("3MB") DataSize maxImageSize) {}
