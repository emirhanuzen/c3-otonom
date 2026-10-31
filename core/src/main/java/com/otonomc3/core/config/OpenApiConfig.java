package com.otonomc3.core.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** General information of the OpenAPI document at /v3/api-docs, shown by Swagger UI at /swagger-ui.html. */
@Configuration
public class OpenApiConfig {

	/** Kept equal to the project version in pom.xml. */
	private static final String API_VERSION = "0.0.1-SNAPSHOT";

	@Bean
	OpenAPI openApi() {
		return new OpenAPI().info(new Info()
				.title("OTONOM-C3 API")
				.description("İHA'ların canlı durumunu ve kayıtlı telemetri geçmişini sunan REST API. "
						+ "Canlı durum bellekten okunur; geçmiş telemetri PostGIS veritabanından gelir. "
						+ "Hata cevapları RFC 9457 (application/problem+json) biçimindedir.")
				.version(API_VERSION));
	}

}
