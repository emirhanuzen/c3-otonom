package com.otonomc3.core.geofence.api;

import java.net.URI;

import com.otonomc3.core.geofence.dto.GeofenceCreateRequestDto;
import com.otonomc3.core.geofence.dto.GeofenceFeatureCollectionDto;
import com.otonomc3.core.geofence.dto.GeofenceFeatureDto;
import com.otonomc3.core.geofence.dto.GeofenceUpdateRequestDto;
import com.otonomc3.core.geofence.service.GeofenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** No-fly zones (geofences). */
@Tag(name = "Yasak bölgeler",
		description = "İHA'ların girmemesi gereken bölgeler (geofence). Kural motoru her saniye ONLINE İHA'ları aktif "
				+ "bölgelerle karşılaştırır: bölgenin içindeki İHA için GEOFENCE_VIOLATION (kritik), mevcut yön ve "
				+ "hızıyla 30 saniye içinde bir bölgeye girecek İHA için GEOFENCE_PREDICTED (uyarı) alarmı üretir. "
				+ "Her değişiklikten sonra güncel bölge listesi WebSocket üzerinden /topic/geofences kanalına "
				+ "FeatureCollection olarak gönderilir.")
@RestController
@RequestMapping("/api/geofences")
public class GeofenceController {

	private final GeofenceService service;

	public GeofenceController(GeofenceService service) {
		this.service = service;
	}

	@Operation(summary = "Yasak bölge listesi",
			description = "Aktif ve pasif bütün bölgeleri GeoJSON FeatureCollection olarak, kimliğe göre sıralı döndürür.")
	@ApiResponse(responseCode = "200", description = "Bölgeler (features boş olabilir)",
			content = @Content(schema = @Schema(implementation = GeofenceFeatureCollectionDto.class)))
	@GetMapping
	public GeofenceFeatureCollectionDto findAll() {
		return service.findAll();
	}

	@Operation(summary = "Yasak bölge ekle",
			description = "Yeni, aktif bir bölge kaydeder. Poligonun her halkası kapalı olmalı ve en az 4 noktadan "
					+ "oluşmalı; poligon geçerli olmalı (PostGIS ST_IsValid: örneğin kendini kesmemeli) ve en fazla "
					+ "50 km² alan kaplamalıdır.")
	@ApiResponse(responseCode = "201", description = "Bölge kaydedildi",
			content = @Content(schema = @Schema(implementation = GeofenceFeatureDto.class)))
	@ApiResponse(responseCode = "400",
			description = "Geçersiz istek: ad boş ya da 100 karakterden uzun, poligon eksik, halka kapalı değil, "
					+ "4'ten az nokta, koordinat aralık dışında, poligon geçersiz veya 50 km²'den büyük",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@PostMapping
	public ResponseEntity<GeofenceFeatureDto> create(@Valid @RequestBody GeofenceCreateRequestDto request) {
		GeofenceFeatureDto created = service.create(request.name(), request.polygon());
		return ResponseEntity.created(URI.create("/api/geofences/" + created.id())).body(created);
	}

	@Operation(summary = "Bölgeyi aktif/pasif yap",
			description = "Pasif bölgeler haritada kalır ama alarm üretmez.")
	@ApiResponse(responseCode = "200", description = "Güncellenmiş bölge",
			content = @Content(schema = @Schema(implementation = GeofenceFeatureDto.class)))
	@ApiResponse(responseCode = "400", description = "active eksik veya id bir tam sayı değil",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "Bu kimlikte bölge yok",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@PatchMapping("/{id}")
	public GeofenceFeatureDto update(
			@Parameter(description = "Bölgenin kimliği", example = "1")
			@PathVariable long id,
			@Valid @RequestBody GeofenceUpdateRequestDto request) {
		return service.setActive(id, request.active());
	}

	@Operation(summary = "Bölgeyi sil")
	@ApiResponse(responseCode = "204", description = "Bölge silindi")
	@ApiResponse(responseCode = "400", description = "id bir tam sayı değil",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "Bu kimlikte bölge yok",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(
			@Parameter(description = "Bölgenin kimliği", example = "1")
			@PathVariable long id) {
		service.delete(id);
		return ResponseEntity.noContent().build();
	}

}
