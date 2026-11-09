package com.otonomc3.core.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the onboard fence settings; the sync lives in com.otonomc3.core.fence. */
@Configuration
@EnableConfigurationProperties(FenceProperties.class)
public class FenceConfig {
}
