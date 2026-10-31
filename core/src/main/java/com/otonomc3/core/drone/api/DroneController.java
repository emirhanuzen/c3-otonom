package com.otonomc3.core.drone.api;

import java.util.List;

import com.otonomc3.core.drone.dto.DroneStateResponseDto;
import com.otonomc3.core.drone.service.DroneLiveStateService;
import com.otonomc3.core.drone.service.DroneNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Live state of the drones, from memory. */
@Tag(name = "İHA canlı durumu",
		description = "İHA'ların bellekte tutulan en güncel durumu. Veritabanına gidilmez. "
				+ "Bağlantı durumu her okumada son mesajın yaşından hesaplanır: "
				+ "3 saniyeden yeniyse ONLINE, 10 saniyeden yeniyse STALE, değilse LOST.")
@RestController
@RequestMapping("/api/drones")
public class DroneController {

	private final DroneLiveStateService service;

	public DroneController(DroneLiveStateService service) {
		this.service = service;
	}

	@Operation(summary = "Bütün İHA'ların canlı durumu",
			description = "Uygulama başladığından beri mesajı alınan bütün İHA'ların son durumunu droneId'ye göre "
					+ "artan sırada döndürür. Hiç İHA yoksa boş liste döner.")
	@ApiResponse(responseCode = "200", description = "İHA listesi (boş olabilir)",
			content = @Content(array = @ArraySchema(schema = @Schema(implementation = DroneStateResponseDto.class))))
	@GetMapping
	public List<DroneStateResponseDto> findAll() {
		return service.findAll().stream().map(DroneStateResponseDto::from).toList();
	}

	@Operation(summary = "Tek İHA'nın canlı durumu",
			description = "Verilen İHA'nın son durumunu, bağlantı durumunu ve son mesajın yaşını döndürür.")
	@ApiResponse(responseCode = "200", description = "İHA'nın canlı durumu",
			content = @Content(schema = @Schema(implementation = DroneStateResponseDto.class)))
	@ApiResponse(responseCode = "400", description = "droneId bir tam sayı değil",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "Bu İHA'dan hiç mesaj alınmadı",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@GetMapping("/{droneId}")
	public DroneStateResponseDto find(
			@Parameter(description = "İHA'nın kimliği (telemetri mesajındaki droneId)", example = "1")
			@PathVariable int droneId) {
		return service.find(droneId)
				.map(DroneStateResponseDto::from)
				.orElseThrow(() -> new DroneNotFoundException(droneId));
	}

}
