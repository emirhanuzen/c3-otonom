package com.otonomc3.core.telemetry.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TelemetryMessageTest {

	private final JsonMapper jsonMapper = JsonMapper.builder().build();

	@Test
	void readsSchemaVersion2Snapshot() {
		String json = """
				{"schemaVersion":2,"droneId":3,"sessionId":"6f1c2a4e-9b1d-4c3e-8a7f-2d5e6b7c8d9e","seq":42,
				 "timestamp":"2026-10-24T12:34:56.789Z",
				 "position":{"lat":39.9207123,"lon":32.8541234,"altMsl":950.12,"altRel":50.5,"heading":271.3},
				 "velocity":{"groundSpeed":12.34,"airSpeed":null,"climbRate":-0.5},
				 "battery":{"percent":87,"voltage":12.45},
				 "gps":{"fixType":3,"satellites":14},
				 "status":{"mode":"AUTO","armed":true},
				 "someFutureField":{"x":1}}
				""";

		TelemetryMessage message = jsonMapper.readValue(json, TelemetryMessage.class);

		assertThat(message.schemaVersion()).isEqualTo(2);
		assertThat(message.droneId()).isEqualTo(3);
		assertThat(message.sessionId()).isEqualTo(UUID.fromString("6f1c2a4e-9b1d-4c3e-8a7f-2d5e6b7c8d9e"));
		assertThat(message.seq()).isEqualTo(42L);
		assertThat(message.timestamp()).isEqualTo(Instant.parse("2026-10-24T12:34:56.789Z"));
		assertThat(message.position()).isEqualTo(
				new TelemetryMessage.Position(39.9207123, 32.8541234, 950.12, 50.5, 271.3));
		assertThat(message.velocity()).isEqualTo(new TelemetryMessage.Velocity(12.34, null, -0.5));
		assertThat(message.battery()).isEqualTo(new TelemetryMessage.Battery(87, 12.45));
		assertThat(message.gps()).isEqualTo(new TelemetryMessage.Gps(3, 14));
		assertThat(message.status()).isEqualTo(new TelemetryMessage.Status("AUTO", true));
	}

	@Test
	void missingAndNullSectionsBecomeNull() {
		String json = """
				{"schemaVersion":2,"droneId":1,"sessionId":"6f1c2a4e-9b1d-4c3e-8a7f-2d5e6b7c8d9e","seq":0,
				 "timestamp":"2026-10-24T12:34:56.789Z","position":null}
				""";

		TelemetryMessage message = jsonMapper.readValue(json, TelemetryMessage.class);

		assertThat(message.position()).isNull();
		assertThat(message.velocity()).isNull();
		assertThat(message.status()).isNull();
	}

}
