package com.otonomc3.core.command.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.otonomc3.core.command.service.CommandType;
import com.otonomc3.core.command.service.CommandValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The command message as docs/contracts/command-v1.md describes it, schema version 1. */
class CommandMessageDtoTest {

	private static final UUID ID = UUID.fromString("3f2b8c1e-7d4a-4b6e-9f10-2a3b4c5d6e7f");
	private static final Instant ISSUED = Instant.parse("2026-11-05T12:00:00Z");

	private final JsonMapper jsonMapper = JsonMapper.builder().build();

	@Test
	void takeoffMessageMatchesTheContract() {
		JsonNode json = write(CommandType.TAKEOFF, CommandValidator.validate(CommandType.TAKEOFF, Map.of("alt", 80)));

		assertThat(json.get("schemaVersion").asInt()).isEqualTo(1);
		assertThat(json.get("commandId").asString()).isEqualTo(ID.toString());
		assertThat(json.get("droneId").asInt()).isEqualTo(2);
		assertThat(json.get("type").asString()).isEqualTo("TAKEOFF");
		assertThat(json.get("params").propertyNames()).containsExactly("alt");
		assertThat(json.get("params").get("alt").asDouble()).isEqualTo(80.0);
		assertThat(Instant.parse(json.get("issuedAt").asString())).isEqualTo(ISSUED);
	}

	@Test
	void takeoffWithoutAltitudeCarriesTheDefault() {
		JsonNode json = write(CommandType.TAKEOFF, CommandValidator.validate(CommandType.TAKEOFF, null));

		assertThat(json.get("params").get("alt").asDouble()).isEqualTo(60.0);
	}

	@Test
	void disarmMessageHasEmptyParams() {
		JsonNode json = write(CommandType.DISARM, CommandValidator.validate(CommandType.DISARM, Map.of("force", true)));

		assertThat(json.get("schemaVersion").asInt()).isEqualTo(1);
		assertThat(json.get("type").asString()).isEqualTo("DISARM");
		assertThat(json.get("params").isEmpty()).isTrue();
	}

	@ParameterizedTest
	@EnumSource(CommandType.class)
	void typeIsWrittenWithItsContractName(CommandType type) {
		assertThat(jsonMapper.writeValueAsString(type)).isEqualTo("\"" + type.name() + "\"");
	}

	@Test
	void rejectedTakeoffResultKeepsTheWholeDetail() {
		CommandResultMessageDto result = jsonMapper.readValue("""
				{"schemaVersion":1,"commandId":"3f2b8c1e-7d4a-4b6e-9f10-2a3b4c5d6e7f","droneId":2,
				 "status":"REJECTED","detail":"arming refused by the aircraft: MAV_RESULT_FAILED; PreArm: GPS not healthy",
				 "completedAt":"2026-11-05T12:00:01.500Z"}
				""", CommandResultMessageDto.class);

		assertThat(result.status()).isEqualTo("REJECTED");
		assertThat(result.detail())
				.isEqualTo("arming refused by the aircraft: MAV_RESULT_FAILED; PreArm: GPS not healthy");
	}

	private JsonNode write(CommandType type, Map<String, Object> params) {
		CommandMessageDto message = new CommandMessageDto(CommandMessageDto.SCHEMA_VERSION, ID, 2, type, params, ISSUED);
		return jsonMapper.readTree(jsonMapper.writeValueAsString(message));
	}

}
