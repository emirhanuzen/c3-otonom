package com.otonomc3.core.command.dto;

import java.util.Map;

import com.otonomc3.core.command.service.CommandType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Body of POST /api/drones/{droneId}/commands.
 *
 * @param params type-specific params; may be omitted for types without params
 */
public record CommandRequestDto(
		@Schema(description = "Komut tipi", example = "GOTO")
		@NotNull CommandType type,
		@Schema(description = "Tipe göre parametreler. GOTO: lat (-90..90), lon (-180..180), alt (30..500 m, eve göre); "
				+ "FAULT_GPS_LOSS: enabled (true = GPS kaybı); FAULT_LINK_DROP: seconds (1..60). "
				+ "Diğer tipler parametre almaz.",
				example = "{\"lat\": 38.6063, \"lon\": 39.2914, \"alt\": 120}")
		Map<String, Object> params) {
}
