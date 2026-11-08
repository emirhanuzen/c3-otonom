package com.otonomc3.core.command.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.otonomc3.core.command.dto.CommandDto;
import com.otonomc3.core.command.dto.CommandRequestDto;
import com.otonomc3.core.command.service.CommandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Commands to the drones, carried out by the edge adapter (docs/contracts/command-v1.md). */
@Tag(name = "Komutlar",
		description = "İHA'lara kenar adaptörü üzerinden gönderilen komutlar. Komut önce PENDING olarak kaydedilir ve "
				+ "RabbitMQ'ya yayınlanır; sonucu (ACCEPTED, REJECTED, FAILED, EXPIRED, TIMEOUT) adaptörden gelir. "
				+ "12 saniye içinde sonuç gelmezse komut TIMEOUT olur. Her durum değişikliği WebSocket üzerinden "
				+ "/topic/commands kanalına da gönderilir.")
@RestController
public class CommandController {

	private final CommandService service;

	public CommandController(CommandService service) {
		this.service = service;
	}

	@Operation(summary = "İHA'ya komut gönder",
			description = "Komutu doğrular, kaydeder ve kenar adaptörüne yayınlar. Cevap beklenmez: 202 ile PENDING "
					+ "durumundaki komut döner (yayınlanamadıysa FAILED). Sonucu GET /api/commands/{commandId} veya "
					+ "/topic/commands ile izlenir. FAULT_* tipleri yalnızca simülasyon açıkken (otonom.sim.enabled) "
					+ "kabul edilir. GOTO, LOITER ve RESUME_MISSION, İHA'nın son telemetrisinde armed=false veya göreli "
					+ "irtifa 10 m'nin altındaysa gönderilmez (409); RTL ve FAULT_* bu kontrolden muaftır.")
	@ApiResponse(responseCode = "202", description = "Komut kaydedildi ve gönderildi (veya gönderilemedi: FAILED)",
			content = @Content(schema = @Schema(implementation = CommandDto.class)))
	@ApiResponse(responseCode = "400",
			description = "Geçersiz istek: bilinmeyen tip, eksik ya da aralık dışı parametre "
					+ "(GOTO alt 30-500 m, FAULT_LINK_DROP seconds 1-60)",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "FAULT_* tipi, simülasyon kapalıyken",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "Bu İHA'dan hiç mesaj alınmadı",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409",
			description = "İHA'nın bağlantısı ONLINE değil (komut ona ulaşmaz) ya da GOTO/LOITER/RESUME_MISSION için "
					+ "İHA yerde: armed=false veya göreli irtifa 10 m'nin altında (\"İHA yerde, önce kalkış yapılmalı\")",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@PostMapping("/api/drones/{droneId}/commands")
	public ResponseEntity<CommandDto> issue(
			@Parameter(description = "İHA'nın kimliği", example = "1")
			@PathVariable int droneId,
			@Valid @RequestBody CommandRequestDto request) {
		CommandDto command = service.issue(droneId, request.type(), request.params());
		return ResponseEntity.accepted().location(URI.create("/api/commands/" + command.id())).body(command);
	}

	@Operation(summary = "Bir İHA'nın komutları", description = "İHA'ya gönderilen komutları en yeniden eskiye döndürür.")
	@ApiResponse(responseCode = "200", description = "Komutlar, yeniden eskiye (boş olabilir)",
			content = @Content(array = @ArraySchema(schema = @Schema(implementation = CommandDto.class))))
	@ApiResponse(responseCode = "400", description = "limit 1-500 dışında veya droneId tam sayı değil",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@GetMapping("/api/drones/{droneId}/commands")
	public List<CommandDto> findByDrone(
			@Parameter(description = "İHA'nın kimliği", example = "1")
			@PathVariable int droneId,
			@Parameter(description = "En fazla kaç komut döneceği, 1 ile 500 arası. Verilmezse 50.",
					schema = @Schema(type = "integer", format = "int32", defaultValue = "50",
							minimum = "1", maximum = "500"))
			@RequestParam(required = false) Integer limit) {
		return service.findByDrone(droneId, limit);
	}

	@Operation(summary = "Tek komut", description = "Komutun güncel durumunu döndürür.")
	@ApiResponse(responseCode = "200", description = "Komut",
			content = @Content(schema = @Schema(implementation = CommandDto.class)))
	@ApiResponse(responseCode = "400", description = "commandId bir UUID değil",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "Bu kimlikte komut yok",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@GetMapping("/api/commands/{commandId}")
	public CommandDto find(
			@Parameter(description = "Komutun kimliği (commandId)", example = "3f2b8c1e-7d4a-4b6e-9f10-2a3b4c5d6e7f")
			@PathVariable UUID commandId) {
		return service.find(commandId);
	}

}
