package com.otonomc3.core.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the no-fly zone settings; the zones themselves live in com.otonomc3.core.geofence. */
@Configuration
@EnableConfigurationProperties(GeofenceProperties.class)
public class GeofenceConfig {
}
