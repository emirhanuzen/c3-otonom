package com.otonomc3.core.command.api;

import com.otonomc3.core.command.dto.SimStatusDto;
import com.otonomc3.core.config.SimProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Whether the simulator fault commands are available. */
@Tag(name = "Simülasyon", description = "SITL simülasyonuna özgü ayarlar.")
@RestController
public class SimController {

	private final SimProperties properties;

	public SimController(SimProperties properties) {
		this.properties = properties;
	}

	@Operation(summary = "Simülasyon durumu",
			description = "enabled true ise FAULT_* komutları (batarya, GPS, irtifa, bağlantı arızası) gönderilebilir. "
					+ "\"sim\" profiliyle (application-sim.properties) açılır; varsayılan kapalıdır.")
	@ApiResponse(responseCode = "200", description = "Simülasyon durumu",
			content = @Content(schema = @Schema(implementation = SimStatusDto.class)))
	@GetMapping("/api/sim/status")
	public SimStatusDto status() {
		return new SimStatusDto(properties.enabled());
	}

}
