package com.otonomc3.core.geofence.api;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import com.otonomc3.core.geofence.dto.GeofenceCreateRequestDto;
import com.otonomc3.core.geofence.dto.GeofenceFeatureCollectionDto;
import com.otonomc3.core.geofence.dto.GeofenceFeatureDto;
import com.otonomc3.core.geofence.dto.GeofenceImportResultDto;
import com.otonomc3.core.geofence.dto.GeofenceUpdateRequestDto;
import com.otonomc3.core.geofence.service.GeofenceImporter;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** No-fly zones (geofences). */
@Tag(name = "Yasak bölgeler",
		description = "İHA'ların girmemesi gereken bölgeler (geofence). Üç türü vardır: PERMANENT (OpenStreetMap'ten "
				+ "içe aktarılan askeri alanlar ve havalimanları; API'den değiştirilemez ve silinemez), TEMPORARY "
				+ "(geçici) ve MISSION (görev). Bir bölge aktifse ve şu an validFrom/validUntil aralığındaysa etkindir. "
				+ "Kural motoru her saniye ONLINE İHA'ları etkin bölgelerle karşılaştırır: bölgenin içindeki İHA için "
				+ "GEOFENCE_VIOLATION (kritik), mevcut yön ve hızıyla 30 saniye içinde bir bölgeye girecek İHA için "
				+ "GEOFENCE_PREDICTED (uyarı) alarmı üretir. Her değişiklikten sonra ve bir bölge etkin/etkisiz hale "
				+ "geldiğinde (10 saniyede bir kontrol edilir) güncel liste WebSocket üzerinden /topic/geofences "
				+ "kanalına FeatureCollection olarak gönderilir.")
@RestController
@RequestMapping("/api/geofences")
public class GeofenceController {

	private final GeofenceService service;
	private final GeofenceImporter importer;
	private final Clock clock;

	public GeofenceController(GeofenceService service, GeofenceImporter importer, Clock clock) {
		this.service = service;
		this.importer = importer;
		this.clock = clock;
	}

	@Operation(summary = "Yasak bölge listesi",
			description = "Bütün bölgeleri (etkin olmayanlar dahil) GeoJSON FeatureCollection olarak, kimliğe göre "
					+ "sıralı döndürür. properties.effective, bölgenin verilen anda etkin olup olmadığını söyler.")
	@ApiResponse(responseCode = "200", description = "Bölgeler (features boş olabilir)",
			content = @Content(schema = @Schema(implementation = GeofenceFeatureCollectionDto.class)))
	@ApiResponse(responseCode = "400", description = "at bir ISO 8601 anı değil",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@GetMapping
	public GeofenceFeatureCollectionDto findAll(
			@Parameter(description = "effective'in hesaplanacağı an, ISO 8601 (örnek: 2026-11-08T12:00:00Z). "
					+ "Verilmezse şimdiki an.")
			@RequestParam(required = false) Instant at) {
		return service.findAll(at != null ? at : clock.instant());
	}

	@Operation(summary = "Yasak bölge ekle",
			description = "Yeni, aktif bir TEMPORARY veya MISSION bölgesi kaydeder. Poligonun her halkası kapalı olmalı "
					+ "ve en az 4 noktadan oluşmalı; poligon geçerli olmalı (PostGIS ST_IsValid: örneğin kendini "
					+ "kesmemeli) ve en fazla 50 km² alan kaplamalıdır. validFrom ve validUntil isteğe bağlıdır; "
					+ "ikisi de verilirse validUntil daha sonra olmalıdır.")
	@ApiResponse(responseCode = "201", description = "Bölge kaydedildi",
			content = @Content(schema = @Schema(implementation = GeofenceFeatureDto.class)))
	@ApiResponse(responseCode = "400",
			description = "Geçersiz istek: ad boş ya da 100 karakterden uzun, poligon eksik, halka kapalı değil, "
					+ "4'ten az nokta, koordinat aralık dışında, poligon geçersiz veya 50 km²'den büyük, kind PERMANENT, "
					+ "validUntil validFrom'dan sonra değil",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@PostMapping
	public ResponseEntity<GeofenceFeatureDto> create(@Valid @RequestBody GeofenceCreateRequestDto request) {
		GeofenceFeatureDto created = service.create(request.name(), request.polygon(), request.kind(),
				request.validFrom(), request.validUntil());
		return ResponseEntity.created(URI.create("/api/geofences/" + created.id())).body(created);
	}

	@Operation(summary = "Bölgeyi değiştir",
			description = "active, validFrom ve validUntil değiştirilebilir; verilmeyen alan aynı kalır, validFrom/"
					+ "validUntil için null o sınırı kaldırır. Pasif bölgeler haritada kalır ama alarm üretmez. "
					+ "PERMANENT bölgeler değiştirilemez.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
			content = @Content(schema = @Schema(implementation = GeofenceUpdateRequestDto.class)))
	@ApiResponse(responseCode = "200", description = "Güncellenmiş bölge",
			content = @Content(schema = @Schema(implementation = GeofenceFeatureDto.class)))
	@ApiResponse(responseCode = "400",
			description = "Hiç alan yok, bilinmeyen alan, active true/false değil, zaman ISO 8601 değil, "
					+ "validUntil validFrom'dan sonra değil veya id bir tam sayı değil",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "Bölge PERMANENT",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "Bu kimlikte bölge yok",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@PatchMapping("/{id}")
	public GeofenceFeatureDto update(
			@Parameter(description = "Bölgenin kimliği", example = "1")
			@PathVariable long id,
			// A map, so a field left out and a field set to null can be told apart.
			@RequestBody Map<String, Object> changes) {
		return service.update(id, changes);
	}

	@Operation(summary = "Bölgeyi sil", description = "PERMANENT bölgeler silinemez.")
	@ApiResponse(responseCode = "204", description = "Bölge silindi")
	@ApiResponse(responseCode = "400", description = "id bir tam sayı değil",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "Bölge PERMANENT",
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

	@Operation(summary = "OpenStreetMap bölgelerini içe aktar",
			description = "Gövdedeki GeoJSON FeatureCollection'ı, açılıştaki otonom.geofence.seed-file ile aynı "
					+ "şekilde PERMANENT bölge olarak içe aktarır: her Polygon/MultiPolygon parçası ayrı bölge olur, "
					+ "aeroway=aerodrome olanlar otonom.geofence.airport-buffer-m kadar genişletilir. Bölgeler "
					+ "(OSM, feature id) ile eşleşir; aynı veri tekrar gönderilirse hiçbir şey değişmez. "
					+ "Yalnızca simülasyon açıkken (sim profili) kullanılabilir.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
			description = "GeoJSON FeatureCollection (örneğin Overpass dışa aktarımı)",
			content = @Content(mediaType = "application/json", schema = @Schema(type = "object")))
	@ApiResponse(responseCode = "200", description = "İçe aktarmanın sonucu",
			content = @Content(schema = @Schema(implementation = GeofenceImportResultDto.class)))
	@ApiResponse(responseCode = "400", description = "Gövde bir GeoJSON FeatureCollection değil",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "Simülasyon kapalı",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@PostMapping(path = "/import", consumes = { "application/json", "application/geo+json" })
	public GeofenceImportResultDto importOsm(@RequestBody String featureCollection) {
		return importer.importFromApi(featureCollection);
	}

}
