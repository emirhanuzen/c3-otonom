package com.otonomc3.core.mission.api;

import java.net.URI;
import java.util.List;

import com.otonomc3.core.command.dto.CommandDto;
import com.otonomc3.core.mission.dto.MissionAssignRequestDto;
import com.otonomc3.core.mission.dto.MissionAssignmentDto;
import com.otonomc3.core.mission.dto.MissionAutofixDto;
import com.otonomc3.core.mission.dto.MissionDto;
import com.otonomc3.core.mission.dto.MissionLaunchDto;
import com.otonomc3.core.mission.dto.MissionLaunchRequestDto;
import com.otonomc3.core.mission.dto.MissionRequestDto;
import com.otonomc3.core.mission.dto.MissionValidationDto;
import com.otonomc3.core.mission.service.MissionLaunchService;
import com.otonomc3.core.mission.service.MissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Missions: planned on the map, checked against the no-fly zones and assigned to drones. */
@Tag(name = "Görevler",
		description = "Haritada planlanan görevler (noktalar, irtifa 30-500 m, isteğe bağlı döngü). Her bacak, rota "
				+ "planlamasındaki gibi 150 m payla genişletilmiş etkin yasak bölgelere göre doğrulanır; engelli bacaklar "
				+ "otomatik olarak bölgeleri dolaşan ara noktalarla düzeltilebilir. Atanan görev İHA'ya MISSION_UPLOAD "
				+ "komutuyla yüklenir ve AUTO modda uçulur. Her değişiklik /topic/missions kanalına gönderilir.")
@RestController
public class MissionController {

	private static final String PROBLEM = "application/problem+json";

	private final MissionService service;
	private final MissionLaunchService launchService;

	public MissionController(MissionService service, MissionLaunchService launchService) {
		this.service = service;
		this.launchService = launchService;
	}

	@Operation(summary = "Görevler", description = "Kayıtlı görevler, kimliğe göre sıralı.")
	@ApiResponse(responseCode = "200", description = "Görevler (boş olabilir)",
			content = @Content(array = @ArraySchema(schema = @Schema(implementation = MissionDto.class))))
	@GetMapping("/api/missions")
	public List<MissionDto> findAll() {
		return service.findAll();
	}

	@Operation(summary = "İHA'lara atanmış görevler",
			description = "Her İHA'ya en son atanan görev ve İHA'nın o görevde bildirdiği son öğe.")
	@ApiResponse(responseCode = "200", description = "Atamalar, İHA'ya göre sıralı",
			content = @Content(array = @ArraySchema(schema = @Schema(implementation = MissionAssignmentDto.class))))
	@GetMapping("/api/missions/assignments")
	public List<MissionAssignmentDto> assignments() {
		return service.assignments();
	}

	@Operation(summary = "Görev başlatmaları", description = "Her İHA'nın son görev başlatması (bellekte tutulur).")
	@ApiResponse(responseCode = "200", description = "Başlatmalar, İHA'ya göre sıralı",
			content = @Content(array = @ArraySchema(schema = @Schema(implementation = MissionLaunchDto.class))))
	@GetMapping("/api/missions/launches")
	public List<MissionLaunchDto> launches() {
		return launchService.findAll();
	}

	@Operation(summary = "Tek görev")
	@ApiResponse(responseCode = "200", description = "Görev", content = @Content(schema = @Schema(implementation = MissionDto.class)))
	@ApiResponse(responseCode = "404", description = "Bu kimlikte görev yok",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@GetMapping("/api/missions/{id}")
	public MissionDto find(@PathVariable long id) {
		return service.find(id);
	}

	@Operation(summary = "Görev oluştur")
	@ApiResponse(responseCode = "201", description = "Görev kaydedildi",
			content = @Content(schema = @Schema(implementation = MissionDto.class)))
	@ApiResponse(responseCode = "400", description = "Ad boş ya da 100 karakterden uzun, 2'den az veya 100'den çok "
			+ "nokta, ya da irtifası 30-500 m dışında bir nokta",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@PostMapping("/api/missions")
	public ResponseEntity<MissionDto> create(@RequestBody MissionRequestDto request) {
		MissionDto mission = service.create(request);
		return ResponseEntity.created(URI.create("/api/missions/" + mission.id())).body(mission);
	}

	@Operation(summary = "Görevi güncelle", description = "Ad, noktalar ve döngü birlikte değiştirilir. Görevin atandığı "
			+ "İHA yüklenmiş olanı uçmaya devam eder; değişiklik bir sonraki atamayla gider.")
	@ApiResponse(responseCode = "200", description = "Görev güncellendi",
			content = @Content(schema = @Schema(implementation = MissionDto.class)))
	@ApiResponse(responseCode = "400", description = "Oluşturmadaki kurallar",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "Bu kimlikte görev yok",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@PutMapping("/api/missions/{id}")
	public MissionDto update(@PathVariable long id, @RequestBody MissionRequestDto request) {
		return service.update(id, request);
	}

	@Operation(summary = "Görevi sil", description = "Görevin İHA atamaları da silinir.")
	@ApiResponse(responseCode = "204", description = "Görev silindi")
	@ApiResponse(responseCode = "404", description = "Bu kimlikte görev yok",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@DeleteMapping("/api/missions/{id}")
	public ResponseEntity<Void> delete(@PathVariable long id) {
		service.delete(id);
		return ResponseEntity.noContent().build();
	}

	@Operation(summary = "QGC görev dosyasını içe aktar",
			description = "Gövde bir QGC WPL 110 metnidir (.waypoints). NAV_WAYPOINT öğeleri sırayla görevin noktaları "
					+ "olur; home (öğe 0) ve NAV_TAKEOFF atlanır, DO_JUMP varsa görev döngü olur.")
	@ApiResponse(responseCode = "201", description = "Görev kaydedildi",
			content = @Content(schema = @Schema(implementation = MissionDto.class)))
	@ApiResponse(responseCode = "400", description = "Dosya QGC WPL 110 değil ya da görev kurallarına uymuyor",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@PostMapping(path = "/api/missions/import", consumes = { MediaType.TEXT_PLAIN_VALUE,
			MediaType.APPLICATION_OCTET_STREAM_VALUE })
	public ResponseEntity<MissionDto> importQgc(
			@Parameter(description = "Görevin adı; verilmezse \"İçe aktarılan görev\"", example = "Ay")
			@RequestParam(required = false) String name,
			@RequestBody String text) {
		MissionDto mission = service.importQgc(text, name);
		return ResponseEntity.created(URI.create("/api/missions/" + mission.id())).body(mission);
	}

	@Operation(summary = "Kayıtlı görevi doğrula",
			description = "Her bacağı (İHA verildiyse İHA'nın konumundan ilk noktaya gideni, döngüde son noktadan ilk "
					+ "noktaya döneni de) 150 m payla genişletilmiş etkin yasak bölgelere göre denetler.")
	@ApiResponse(responseCode = "200", description = "Doğrulama",
			content = @Content(schema = @Schema(implementation = MissionValidationDto.class)))
	@ApiResponse(responseCode = "404", description = "Görev yok ya da bu İHA'dan hiç mesaj alınmadı",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "İHA'nın konumu bilinmiyor",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@PostMapping("/api/missions/{id}/validate")
	public MissionValidationDto validate(@PathVariable long id,
			@Parameter(description = "Verilirse İHA'nın konumundan ilk noktaya giden bacak da denetlenir", example = "1")
			@RequestParam(required = false) Integer droneId) {
		return service.validate(id, droneId);
	}

	@Operation(summary = "Kaydedilmemiş görevi doğrula", description = "Kayıtlı görevin doğrulaması gibi; ad gerekmez.")
	@ApiResponse(responseCode = "200", description = "Doğrulama",
			content = @Content(schema = @Schema(implementation = MissionValidationDto.class)))
	@ApiResponse(responseCode = "400", description = "Noktalar görev kurallarına uymuyor",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@PostMapping("/api/missions/validate")
	public MissionValidationDto validateDraft(@RequestBody MissionRequestDto draft,
			@RequestParam(required = false) Integer droneId) {
		return service.validateDraft(draft, droneId);
	}

	@Operation(summary = "Kayıtlı görevi otomatik düzelt",
			description = "Engellenen her bacağa rota planlayıcının bölgeleri dolaşan ara noktalarını ekler; yeni "
					+ "noktaların irtifası bacağın iki ucundaki irtifaların büyüğüdür. Varsayılan olarak yalnızca önizleme "
					+ "döner; save=true ile kaydedilir. Bölge içindeki noktalar düzeltilmez (409).")
	@ApiResponse(responseCode = "200", description = "Düzeltilmiş görev",
			content = @Content(schema = @Schema(implementation = MissionAutofixDto.class)))
	@ApiResponse(responseCode = "404", description = "Bu kimlikte görev yok",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "Bölge içinde nokta var (waypointsInZone), bir bacak için güvenli "
			+ "rota yok ya da görev 100 noktayı aşardı",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@PostMapping("/api/missions/{id}/autofix")
	public MissionAutofixDto autofix(@PathVariable long id,
			@Parameter(description = "true: düzeltilmiş görevi kaydet") @RequestParam(defaultValue = "false") boolean save) {
		return service.autofix(id, save);
	}

	@Operation(summary = "Kaydedilmemiş görevi otomatik düzelt", description = "Önizleme; hiçbir şey kaydedilmez.")
	@ApiResponse(responseCode = "200", description = "Düzeltilmiş görev",
			content = @Content(schema = @Schema(implementation = MissionAutofixDto.class)))
	@ApiResponse(responseCode = "400", description = "Noktalar görev kurallarına uymuyor",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "Kayıtlı görevin düzeltmesindeki gibi",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@PostMapping("/api/missions/autofix")
	public MissionAutofixDto autofixDraft(@RequestBody MissionRequestDto draft) {
		return service.autofixDraft(draft);
	}

	@Operation(summary = "Görevi İHA'ya ata",
			description = "Görev İHA'nın konumundan başlayarak doğrulanır; temizse MISSION_UPLOAD komutu (ilk noktadan, "
					+ "AUTO) gönderilir ve görev İHA'ya atanır. Komut, POST /api/drones/{droneId}/commands gibi 202 ile döner.")
	@ApiResponse(responseCode = "202", description = "MISSION_UPLOAD gönderildi (veya gönderilemedi: FAILED)",
			content = @Content(schema = @Schema(implementation = CommandDto.class)))
	@ApiResponse(responseCode = "404", description = "Görev yok ya da bu İHA'dan hiç mesaj alınmadı",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "Görev yasak bölgelerden geçiyor (legs ve waypointsInZone ile), "
			+ "İHA'nın konumu bilinmiyor, İHA çevrimiçi değil ya da yerde",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@PostMapping("/api/missions/{id}/assign")
	public ResponseEntity<CommandDto> assign(@PathVariable long id, @Valid @RequestBody MissionAssignRequestDto request) {
		CommandDto command = service.assign(id, request.droneId());
		return ResponseEntity.accepted().location(URI.create("/api/commands/" + command.id())).body(command);
	}

	@Operation(summary = "Görevi tek tuşla başlat",
			description = "Görev İHA'nın konumundan başlayarak doğrulanır. İHA havadaysa atama gibi MISSION_UPLOAD "
					+ "gönderilir. Yerdeyse önce ilk noktanın irtifasına (30-300 m arasına sıkıştırılır) TAKEOFF gönderilir; "
					+ "İHA armed ve göreli irtifası hedefin 10 m altına ulaşınca MISSION_UPLOAD (AUTO) gelir. Kalkış 120 sn "
					+ "içinde tamamlanmazsa ya da bir adım reddedilirse başlatma FAILED olur. İlerleme (VALIDATING, "
					+ "TAKING_OFF, UPLOADING, RUNNING, FAILED) /topic/missions kanalına {launch: ...} olarak gönderilir.")
	@ApiResponse(responseCode = "202", description = "Başlatma sürüyor",
			content = @Content(schema = @Schema(implementation = MissionLaunchDto.class)))
	@ApiResponse(responseCode = "404", description = "Görev yok ya da bu İHA'dan hiç mesaj alınmadı",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "Görev yasak bölgelerden geçiyor (legs, waypointsInZone), bu İHA "
			+ "için zaten bir başlatma sürüyor, İHA çevrimiçi değil ya da komut reddedildi",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@PostMapping("/api/missions/{id}/launch")
	public ResponseEntity<MissionLaunchDto> launch(@PathVariable long id,
			@Valid @RequestBody MissionLaunchRequestDto request) {
		return ResponseEntity.accepted().body(launchService.launch(id, request.droneId()));
	}

}
