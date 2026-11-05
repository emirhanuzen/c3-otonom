package com.otonomc3.core.alarm.api;

import java.util.List;

import com.otonomc3.core.alarm.dto.AlarmDto;
import com.otonomc3.core.alarm.service.AlarmQuery;
import com.otonomc3.core.alarm.service.AlarmService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Alarms raised by the rule engine. */
@Tag(name = "Alarmlar",
		description = "Kural motorunun İHA'ların canlı durumundan ürettiği alarmlar. Kurallar her saniye çalışır. "
				+ "Her İHA ve alarm türü için en fazla bir aktif alarm vardır; koşulun şiddeti değişirse aynı alarm "
				+ "güncellenir, koşul 3 saniye boyunca görülmezse alarm kapanır. "
				+ "Değişiklikler WebSocket üzerinden /topic/alarms kanalına da gönderilir.")
@RestController
@RequestMapping("/api/alarms")
public class AlarmController {

	private final AlarmService service;

	public AlarmController(AlarmService service) {
		this.service = service;
	}

	@Operation(summary = "Alarm listesi",
			description = "Aktif veya kapanmış alarmları en yeniden eskiye doğru döndürür. "
					+ "İsteğe bağlı olarak tek bir İHA'nın alarmlarıyla sınırlanabilir.")
	@ApiResponse(responseCode = "200", description = "Alarmlar, yeniden eskiye (boş olabilir)",
			content = @Content(array = @ArraySchema(schema = @Schema(implementation = AlarmDto.class))))
	@ApiResponse(responseCode = "400",
			description = "Geçersiz parametre: limit 1-500 dışında, active true/false değil veya droneId tam sayı değil",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@GetMapping
	public List<AlarmDto> find(
			@Parameter(description = "true: aktif alarmlar, false: kapanmış alarmlar. Verilmezse true.")
			@RequestParam(defaultValue = "true") boolean active,
			@Parameter(description = "Yalnızca bu İHA'nın alarmları. Verilmezse bütün İHA'lar.", example = "1")
			@RequestParam(required = false) Integer droneId,
			@Parameter(description = "En fazla kaç alarm döneceği, 1 ile 500 arası. Verilmezse 100.",
					schema = @Schema(type = "integer", format = "int32", defaultValue = "100",
							minimum = "1", maximum = "500"))
			@RequestParam(required = false) Integer limit) {
		return service.find(AlarmQuery.resolve(active, droneId, limit));
	}

	@Operation(summary = "Alarmı onayla",
			description = "Alarmı operatör tarafından görüldü olarak işaretler. Kapanmış alarmlar da onaylanabilir. "
					+ "Alarm zaten onaylıysa değiştirilmeden döndürülür.")
	@ApiResponse(responseCode = "200", description = "Onaylanmış alarm",
			content = @Content(schema = @Schema(implementation = AlarmDto.class)))
	@ApiResponse(responseCode = "400", description = "id bir tam sayı değil",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "Bu kimlikte alarm yok",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@PostMapping("/{id}/ack")
	public AlarmDto acknowledge(
			@Parameter(description = "Alarmın kimliği", example = "1")
			@PathVariable long id) {
		return service.acknowledge(id);
	}

}
