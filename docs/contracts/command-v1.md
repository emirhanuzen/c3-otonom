# Komut sözleşmesi, sürüm 1

Çekirdekten İHA'ya giden komutların ve adaptörün döndürdüğü sonuçların RabbitMQ sözleşmesi. Uygulayan taraf: `edge-adapter` (`command_schema.py`, `command_executor.py`, `command_channel.py`).

## Topoloji

| Öğe | Değer |
|---|---|
| Exchange | `otonom.command`, `topic`, durable |
| Komut routing key | `command.<droneId>` |
| Sonuç routing key | `result.<droneId>` |
| Adaptörün kuyruğu | `edge.commands`, durable, `x-message-ttl` = 10000 ms, `command.#` ile bağlı |

- Adaptör exchange'i ve `edge.commands` kuyruğunu kendisi tanımlar. Kuyruğu başka biri tanımlayacaksa argümanları aynı olmalıdır, yoksa RabbitMQ tanımı reddeder.
- Sonuç kuyruğunu çekirdek tanımlar (örneğin `result.#` ile bağlı bir kuyruk). Sonuçlar `mandatory` ile gönderilir; bağlı kuyruk yoksa sonuç loglanır ve atılır.
- Bütün mesajlar UTF-8 JSON'dır, `content_type` `application/json`. Sonuçlar persistent'tır.

## Komut mesajı

```json
{
  "schemaVersion": 1,
  "commandId": "3f2b8c1e-7d4a-4b6e-9f10-2a3b4c5d6e7f",
  "droneId": 3,
  "type": "GOTO",
  "params": {"lat": 38.6063, "lon": 39.2914, "alt": 120},
  "issuedAt": "2026-11-05T12:00:00.000Z"
}
```

| Alan | Tür | Açıklama |
|---|---|---|
| `schemaVersion` | int | Her zaman `1` |
| `commandId` | UUID metni | Komutun kimliği; sonuç bu kimlikle döner |
| `droneId` | pozitif int | Hedef İHA (MAVLink System ID) |
| `type` | metin | Aşağıdaki tiplerden biri |
| `params` | nesne | Tipe göre parametreler; parametresiz tiplerde `{}` veya hiç yazılmaz. Bilinmeyen ek alanlar yok sayılır |
| `issuedAt` | ISO 8601 | Komutun üretildiği an; saat dilimi (`Z` veya `+03:00`) zorunludur |

## Komut tipleri

| Tip | Parametreler | İHA'ya giden | Başarı ölçütü |
|---|---|---|---|
| `RTL` | - | `COMMAND_LONG` `MAV_CMD_NAV_RETURN_TO_LAUNCH` | `COMMAND_ACK` ACCEPTED |
| `RESUME_MISSION` | - | `COMMAND_LONG` `MAV_CMD_DO_SET_MODE`, mod AUTO | `COMMAND_ACK` ACCEPTED |
| `LOITER` | - | `COMMAND_LONG` `MAV_CMD_DO_SET_MODE`, mod LOITER | `COMMAND_ACK` ACCEPTED |
| `GOTO` | `lat` (-90..90), `lon` (-180..180), `alt` (m, eve göre, >= 0) | `COMMAND_INT` `MAV_CMD_DO_REPOSITION`, frame `MAV_FRAME_GLOBAL_RELATIVE_ALT`, GUIDED'a geçiş bayrağıyla | `COMMAND_ACK` ACCEPTED |
| `ROUTE` | `waypoints` (`[[lat, lon, alt], ...]`, 1..100 nokta; `alt` m, eve göre, >= 0; sonuncusu hedef) | Görev (mission) yüklemesi, ardından `MISSION_SET_CURRENT` seq = 1, ardından `COMMAND_LONG` `MAV_CMD_DO_SET_MODE`, mod AUTO (aşağıya bakın) | `MISSION_ACK` `MAV_MISSION_ACCEPTED`, `MISSION_CURRENT` seq = 1, mod `COMMAND_ACK` ACCEPTED |
| `MISSION_UPLOAD` | `waypoints` (`ROUTE` gibi, 1..100 nokta), `loop` (bool, verilmezse `false`), `startSeq` (tam sayı, 1..nokta sayısı, verilmezse 1) | Görev yüklemesi (`ROUTE` gibi; öğeler aşağıda), ardından `MISSION_SET_CURRENT` seq = `startSeq`, ardından `COMMAND_LONG` `MAV_CMD_DO_SET_MODE`, mod AUTO | `MISSION_ACK` `MAV_MISSION_ACCEPTED`, `MISSION_CURRENT` seq = `startSeq`, mod `COMMAND_ACK` ACCEPTED |
| `LAND` | `approach` (`[lat, lon, alt]`, alt m eve göre, >= 0), `land` (`[lat, lon]`, home) | Görev yüklemesi `[home yer tutucu, DO_LAND_START, NAV_WAYPOINT yaklaşma, NAV_LAND]`, ardından `MISSION_SET_CURRENT` seq = 1, ardından mod AUTO (aşağıya bakın) | `MISSION_ACK` `MAV_MISSION_ACCEPTED`, `MISSION_CURRENT` seq = 1 veya 2, mod `COMMAND_ACK` ACCEPTED |
| `TAKEOFF` | `alt` (m, eve göre, 30..300; verilmezse 60) | Sırayla: `PARAM_SET` `TKOFF_ALT` = `alt`; `COMMAND_LONG` `MAV_CMD_DO_SET_MODE`, mod TAKEOFF; `COMMAND_LONG` `MAV_CMD_COMPONENT_ARM_DISARM`, param1 = 1 | `PARAM_VALUE` aynı değer, mod `COMMAND_ACK` ACCEPTED, arm `COMMAND_ACK` ACCEPTED |
| `DISARM` | - | `COMMAND_LONG` `MAV_CMD_COMPONENT_ARM_DISARM`, param1 = 0, param2 = 0 (force yok; otopilot havadayken reddeder) | `COMMAND_ACK` ACCEPTED |
| `FENCE_UPLOAD` | `version` (boş olmayan metin, en fazla 64 karakter), `action` (`RTL` veya `REPORT`), `polygons` (`[[[lat, lon], ...], ...]`; poligon başına en az 3 köşe, toplam en fazla 500 köşe) | Sırayla: fence listesi (aşağıya bakın); `polygons` boşsa yalnızca `PARAM_SET` `FENCE_ENABLE` = 0, değilse `PARAM_SET` `FENCE_ACTION` (RTL = 1, REPORT = 0); `PARAM_REQUEST_READ` `FENCE_TYPE`, poligon biti (4) yoksa `PARAM_SET` `FENCE_TYPE` = eski değer \| 4; `PARAM_SET` `FENCE_ENABLE` = 1 | `MISSION_ACK` `MAV_MISSION_ACCEPTED` ve bütün parametrelerde `PARAM_VALUE` aynı değer |
| `FAULT_BATTERY_DRAIN` | - | `PARAM_SET` `SIM_BATT_CAP_AH` = 0.3 | `PARAM_VALUE` aynı değer |
| `FAULT_GPS_LOSS` | `enabled` (bool; `true` = GPS kaybı) | `PARAM_SET` `SIM_GPS1_ENABLE` (0/1) veya, o yoksa, `SIM_GPS_DISABLE` (1/0) | `PARAM_VALUE` aynı değer |
| `FAULT_LOW_ALTITUDE` | - | `PARAM_SET` `ALT_HOLD_RTL` = 2000 (cm), ardından RTL | İkisi de başarılı |
| `FAULT_LINK_DROP` | `seconds` (1..60) | Hiçbir şey | Adaptör o İHA'nın telemetrisini bu süre boyunca yayınlamaz; hemen ACCEPTED |
| `FAULT_RESET` | - | `SIM_BATT_CAP_AH` = 10, GPS açık, `ALT_HOLD_RTL` = 10000, ardından `COMMAND_LONG` `MAV_CMD_BATTERY_RESET` (param1 = 0xFF bütün bataryalar, param2 = 100 %); süren link drop iptal edilir | Bütün parametreler doğrulanır. Batarya sıfırlaması reddedilirse komut yine ACCEPTED olur, `detail` `battery reset not supported (<sonuç>)` içerir (test sayfası "SITL yeniden başlatılınca batarya dolar" notunu gösterir) |

- `FAULT_*` tipleri yalnızca SITL'de anlamlıdır, çünkü simülatörün `SIM_*` parametrelerini değiştirir.
- Mod numaraları ArduPlane'e göredir (AUTO = 10, LOITER = 12, TAKEOFF = 13).
- `TAKEOFF` adımları birbirini bekler: `TKOFF_ALT` doğrulanmadan mod, mod onaylanmadan arm gönderilmez. ArduPlane TAKEOFF modunda arm edilince kalkar. Bir adım başarısız olursa sonraki adımlar gönderilmez.
- Arm reddedilirse (`REJECTED`) `detail`, komut başlamadan en fazla 3 saniye öncesinden komutun sonuna kadar o İHA'dan gelen ve `PreArm:` ile başlayan `STATUSTEXT` metinlerini, tekrarsız ve `; ` ile ayrılmış olarak içerir; örneğin `arming refused by the aircraft: MAV_RESULT_FAILED; PreArm: GPS not healthy`. ArduPilot bu metinleri red `COMMAND_ACK`'inden sonra gönderdiği için adaptör sonucu yayınlamadan önce 1 saniye daha bekler; bu bekleme `TIMEOUT` üretmez.
- `TAKEOFF` ve `DISARM` bu sürümde geriye uyumlu olarak eklendi; `schemaVersion` 1 kalır. Bu tipleri tanımayan eski bir adaptör onları `REJECTED` ("unknown command type") ile cevaplar.
- `FENCE_UPLOAD` da geriye uyumlu olarak eklendi; `schemaVersion` 1 kalır. Eski bir adaptör onu `REJECTED` ("unknown command type") ile cevaplar.
- `ROUTE` da geriye uyumlu olarak eklendi; `schemaVersion` 1 kalır. Eski bir adaptör onu `REJECTED` ("unknown command type") ile cevaplar.
- `MISSION_UPLOAD` da geriye uyumlu olarak eklendi; `schemaVersion` 1 kalır. Eski bir adaptör onu `REJECTED` ("unknown command type") ile cevaplar.
- `LAND` da geriye uyumlu olarak eklendi; `schemaVersion` 1 kalır. Eski bir adaptör onu `REJECTED` ("unknown command type") ile cevaplar.
- GPS parametresinin adı İHA'ya `PARAM_REQUEST_READ` ile sorulur (ArduPilot 4.6'da `SIM_GPS_DISABLE` yerini `SIM_GPS1_ENABLE`'a bıraktı) ve adaptör çalıştığı sürece İHA başına önbellekte tutulur.

## Yasak bölgeleri dolaşan rota (`ROUTE`)

Çekirdek bir `GOTO`'yu göndermeden hemen önce yasak bölgelere göre planlar (`RoutePlanner`): İHA'nın konumundan hedefe uzanan doğru parçasının `otonom.route.corridor-m` (varsayılan 10 km, geography üzerinde `ST_DWithin`) yakınındaki etkin bölgeler, hedef ne kadar uzak olursa olsun, `otonom.route.clearance-m` (varsayılan 150 m) genişletilir. Düz çizgi hiçbirine değmiyorsa `GOTO` olduğu gibi gider; değiyorsa yerine bölgeleri dolaşan bir `ROUTE` gönderilir (ara noktalar ve hedef, hepsi `GOTO`'nun `alt` değeriyle).

```json
{
  "schemaVersion": 1,
  "commandId": "5d2e7a1b-3c4f-4a5b-8c6d-7e8f9a0b1c2d",
  "droneId": 3,
  "type": "ROUTE",
  "params": {"waypoints": [[38.6102, 39.2851, 120], [38.6131, 39.2990, 120], [38.6063, 39.2914, 120]]},
  "issuedAt": "2026-11-09T12:00:00.000Z"
}
```

- Görev, mission protokolüyle `mission_type` = `MAV_MISSION_TYPE_MISSION` (0) olarak yüklenir (`MISSION_COUNT`, otopilotun her `MISSION_REQUEST_INT` / `MISSION_REQUEST` isteğine bir `MISSION_ITEM_INT`, sonunda `MISSION_ACK`). `waypoints` n nokta ise görev n + 1 öğedir:
  - seq 0: ArduPilot'un home yeri için yer tutucu, `MAV_CMD_NAV_WAYPOINT`, frame `MAV_FRAME_GLOBAL`, İHA'nın son `GLOBAL_POSITION_INT` konumu ve MSL irtifası (bilinmiyorsa ilk nokta, irtifa 0). Otopilot kendi home'unu korur.
  - seq 1..n-1: ara noktalar, `MAV_CMD_NAV_WAYPOINT`, frame `MAV_FRAME_GLOBAL_RELATIVE_ALT`.
  - seq n: hedef, `MAV_CMD_NAV_LOITER_UNLIM`, frame `MAV_FRAME_GLOBAL_RELATIVE_ALT`; uçak görevi bitirmek yerine hedefte daire çizer.
  - Bütün öğelerde current = 0, autocontinue = 1, param1-4 = 0 (otopilotun varsayılan kabul ve loiter yarıçapları); x/y = lat/lon * 10^7.
- `MISSION_ACK` kabul değilse `REJECTED` ve mod değiştirilmez. Kabulse `MISSION_SET_CURRENT` seq = 1 gönderilir ve seq = 1 bildiren `MISSION_CURRENT` beklenir, ardından mod AUTO istenir.
- Her öğe isteği, `MISSION_ACK`, `MISSION_CURRENT` ve mod `COMMAND_ACK` ayrı ayrı 5 saniye içinde gelmelidir; gelmezse `TIMEOUT`.
- **`ROUTE`, İHA'daki önceki görevin yerine geçer.** O görev silinir; sonradan `RESUME_MISSION` bu rotayı (hedefte bekleme dahil) uçar, eski görevi değil.
- Fence öğeleri (`mission_type` = 1) ayrı bir listedir; `ROUTE` onlara dokunmaz.

## Planlanmış görev (`MISSION_UPLOAD`)

Çekirdek, haritada planlanan bir görevi İHA'ya atarken (`POST /api/missions/{id}/assign`) ve aşağıdaki durumda `RESUME_MISSION` yerine gönderir. "İHA yerde" kontrolü `ROUTE` gibi uygulanır.

```json
{
  "schemaVersion": 1,
  "commandId": "7b3c9d2e-1f4a-4c5b-9d6e-8f7a6b5c4d3e",
  "droneId": 3,
  "type": "MISSION_UPLOAD",
  "params": {"waypoints": [[38.6125, 39.2915, 100], [38.6110, 39.2974, 100], [38.6140, 39.2974, 120]], "loop": true, "startSeq": 1},
  "issuedAt": "2026-11-09T12:00:00.000Z"
}
```

- Yükleme, öğe istekleri, `MISSION_ACK` ve zaman aşımları `ROUTE` ile aynıdır. `waypoints` n nokta ise:
  - seq 0: home yer tutucu (`ROUTE` gibi).
  - `loop` = `false`: seq 1..n-1 `MAV_CMD_NAV_WAYPOINT`, seq n `MAV_CMD_NAV_LOITER_UNLIM` (son noktada daire çizer); görev n + 1 öğedir.
  - `loop` = `true`: seq 1..n `MAV_CMD_NAV_WAYPOINT`, seq n + 1 `MAV_CMD_DO_JUMP`, frame `MAV_FRAME_MISSION`, param1 = 1 (hedef öğe), param2 = -1 (sonsuz tekrar), x/y/z = 0; görev n + 2 öğedir.
  - Bütün öğelerde current = 0, autocontinue = 1; nokta öğelerinde param1-4 = 0.
- `MISSION_ACK` kabulse `MISSION_SET_CURRENT` seq = `startSeq` gönderilir ve seq = `startSeq` bildiren `MISSION_CURRENT` beklenir, ardından mod AUTO istenir. Detail: `looped mission of 3 waypoint(s) uploaded, item 1 current, mode AUTO set`.
- `startSeq` görev öğesi numarasıdır: 1 ilk nokta, k k'inci nokta.
- **`RESUME_MISSION` (çekirdek davranışı):** İHA'ya atanmış bir görev varsa ve İHA'daki görev o değilse (o İHA'nın en yeni `MISSION_UPLOAD`/`ROUTE`/`GOTO` komutu bir `ROUTE` ya da `GOTO`, veya `PENDING`/`ACCEPTED` olmayan bir `MISSION_UPLOAD` ise), çekirdek `RESUME_MISSION` yerine atanmış görevi `MISSION_UPLOAD` olarak gönderir; `startSeq`, İHA'nın o görevde en son bildirdiği `missionSeq`'tir (bilinmiyorsa 1). Atanmış görev yoksa ya da İHA'da zaten o varsa `RESUME_MISSION` olduğu gibi gider.

## Home'a iniş (`LAND`)

Parametreleri çekirdek hesaplar (`LandingService`); istemcinin gönderdiği parametreler kullanılmaz. "İHA yerde" kontrolü `ROUTE` gibi uygulanır.

```json
{
  "schemaVersion": 1,
  "commandId": "8c4d1e2f-3a5b-4c6d-9e7f-1a2b3c4d5e6f",
  "droneId": 3,
  "type": "LAND",
  "params": {"approach": [38.6017081, 39.2850000, 60], "land": [38.6125000, 39.2850000]},
  "issuedAt": "2026-11-09T12:00:00.000Z"
}
```

- **Home**: telemetrinin `home` alanı (otopilotun `HOME_POSITION`'ı; 0, 0 home sayılmaz), o yoksa çekirdeğin İHA'yı o adaptör oturumunda yerde (disarmed ya da göreli irtifa < 10 m) gördüğü ilk konum. İkisi de yoksa 409 "İHA'nın home noktası bilinmiyor".
- **Yaklaşma noktası**: home'dan iniş yönünün (`otonom.landing.heading-deg`, varsayılan 0 = kuzeye doğru iniş, rüzgâr yok varsayımı) tersine `otonom.landing.approach-distance-m` (1200 m) geride, `otonom.landing.approach-alt-m` (60 m) irtifada.
- İHA'nın konumundan yaklaşma noktasına (yaklaşma bacağı) ve yaklaşma noktasından home'a (iniş bacağı) giden doğru parçaları, rota planlamasındaki gibi 150 m payla genişletilmiş etkin yasak bölgelere göre denetlenir; değen bacak 409 (`Landing blocked`, `reason` `APPROACH_BLOCKED` / `LANDING_BLOCKED`, `zones`) ile reddedilir.
- Adaptör görevi `ROUTE` gibi yükler (zaman aşımları aynı): seq 0 home yer tutucu; seq 1 `MAV_CMD_DO_LAND_START` (x/y = home); seq 2 `MAV_CMD_NAV_WAYPOINT` yaklaşma, frame `MAV_FRAME_GLOBAL_RELATIVE_ALT`; seq 3 `MAV_CMD_NAV_LAND` home'da, alt 0. Ardından `MISSION_SET_CURRENT` seq = 1. `DO_LAND_START` bir navigasyon komutu olmadığından ArduPilot hemen yaklaşma noktasını (seq 2) current bildirebilir; `MISSION_CURRENT` 1 veya 2 kabul edilir. Sonra mod AUTO.
- `LAND`, `ROUTE` gibi İHA'daki görevin yerine geçer. Teker koyduktan sonra otopilot kendisi disarm eder (`LAND_DISARMDELAY`); harita, ACCEPTED bir `LAND`'den sonra `armed=false` görünce "İndi" bildirimi gösterir.

## Otopilot bölgeleri (`FENCE_UPLOAD`)

Çekirdek, yasak bölgeleri İHA'nın otopilotuna yükler (onboard geofence); bağlantı kopsa bile otopilot bölgeye girince kendisi `action`'ı uygular. Komutu çekirdeğin `FenceSyncService`'i gönderir; "İHA yerde" kontrolünden muaftır, yerde ve havada gönderilebilir.

```json
{
  "schemaVersion": 1,
  "commandId": "9a1c2e3f-4b5d-4e6f-8a7b-1c2d3e4f5a6b",
  "droneId": 3,
  "type": "FENCE_UPLOAD",
  "params": {
    "version": "3fa2b4c1d9e07a65",
    "action": "RTL",
    "polygons": [[[38.6000, 39.3000], [38.6000, 39.3100], [38.6100, 39.3100], [38.6100, 39.3000]]]
  },
  "issuedAt": "2026-11-09T12:00:00.000Z"
}
```

- `polygons` her biri bir dışlama (exclusion) poligonudur; köşeler `[lat, lon]` sırasıyla, halka açık verilir (son köşe ilkinin tekrarı değildir; tekrar edilmişse adaptör atar).
- Fence listesi MAVLink mission protokolüyle, `mission_type` = `MAV_MISSION_TYPE_FENCE` (1) ile yüklenir: adaptör `MISSION_COUNT` (köşe sayısı) gönderir, otopilot öğeleri tek tek `MISSION_REQUEST_INT` (eski yazılımda `MISSION_REQUEST`) ile ister, adaptör her köşe için bir `MISSION_ITEM_INT` cevaplar: komut `MAV_CMD_NAV_FENCE_POLYGON_VERTEX_EXCLUSION` (5002), frame `MAV_FRAME_GLOBAL`, param1 = o köşenin poligonundaki köşe sayısı, x/y = lat/lon * 10^7. Aynı öğe yeniden istenirse yeniden gönderilir. Otopilot `MISSION_ACK` ile bitirir.
- `polygons` boşsa öğe gönderilmez; fence listesi `MISSION_CLEAR_ALL` (`mission_type` = 1) ile temizlenir, `MISSION_ACK` beklenir ve ardından fence kapatılır: `PARAM_SET` `FENCE_ENABLE` = 0, `PARAM_VALUE` ile doğrulanır. Poligonsuz etkin bir poligon fence'i arm'ı engelleyebileceği için açık bırakılmaz; `FENCE_ACTION` ve `FENCE_TYPE`'a dokunulmaz. Detail: `fence <version>: fence items cleared, FENCE_ENABLE=0`.
- Öğe isteklerinin, `MISSION_ACK`'in ve her `PARAM_VALUE`'nun her biri ayrı ayrı 5 saniye içinde gelmelidir; gelmezse `TIMEOUT`. `MISSION_ACK` `MAV_MISSION_ACCEPTED` değilse `REJECTED` (detail sonucun adını içerir, örneğin `MAV_MISSION_NO_SPACE`) ve parametreler gönderilmez. Otopilot bütün öğeleri istemeden kabul ederse ya da aralık dışı bir öğe isterse `FAILED`.
- `FENCE_TYPE`'ın diğer bitleri (azami irtifa, daire...) korunur.
- `version` adaptörde yalnızca `detail`'e yazılır (`fence 3fa2b4c1d9e07a65: 1 polygon(s) with 4 vertices uploaded, FENCE_ACTION=1, FENCE_TYPE=7, FENCE_ENABLE=1`); çekirdek hangi listenin yüklü olduğunu bununla takip eder.
- Mission protokolünün `mission_type` alanı MAVLink 2 ister; adaptör pymavlink'i MAVLink 2 lehçesiyle yükler (`MAVLINK20=1`).

### İlgili telemetri alanları

Telemetri anlık görüntüsünün `status` nesnesine, şema sürümü değişmeden (2) iki alan eklendi. Tüketiciler bilinmeyen alanları yok saydığı için geriye uyumludur.

| Alan | Tür | Kaynak |
|---|---|---|
| `status.fenceBreached` | bool veya `null` | MAVLink `FENCE_STATUS.breach_status` (1 = otopilot şu an bir fence'i ihlal ediyor) |
| `status.fenceBreachCount` | int veya `null` | `FENCE_STATUS.breach_count` (otopilot açıldığından beri ihlal sayısı) |
| `home.lat`, `home.lon`, `home.altMsl` | sayı veya `null` | MAVLink `HOME_POSITION` (`latitude`/`longitude` * 10^-7, `altitude` mm -> m, MSL); adaptör ilk heartbeat'te `MAV_CMD_SET_MESSAGE_INTERVAL` ile 5 saniyede bir ister. `home` nesnesi geriye uyumlu olarak eklendi; `LAND`'in hedefidir |
| `status.missionSeq` | int veya `null` | `MISSION_CURRENT.seq`: otopilotun şu an uçtuğu görev öğesi (0 = home, 1 = ilk nokta); hiç gelmediyse `null`. Aynı şekilde geriye uyumlu olarak eklendi. Çekirdek, İHA atanmış görevini AUTO'da uçarken bunu değiştikçe `drone_mission.last_seq`'e yazar |

- ArduPilot `FENCE_STATUS`'u yalnızca fence etkinken gönderir; hiç gelmediyse iki alan da `null`'dır. Adaptör ilk heartbeat'te veri akışının yanında `MAV_CMD_SET_MESSAGE_INTERVAL` ile `FENCE_STATUS`'u (162) saniyede bir ister.
- Çekirdek `fenceBreached = true` için `FENCE_BREACH` (CRITICAL) alarmı açar: "Otopilot yasak bölge koruması devreye girdi".

## Sonuç mesajı

```json
{
  "schemaVersion": 1,
  "commandId": "3f2b8c1e-7d4a-4b6e-9f10-2a3b4c5d6e7f",
  "droneId": 3,
  "status": "ACCEPTED",
  "detail": "repositioning to 38.6063000, 39.2914000 at 120 m",
  "completedAt": "2026-11-05T12:00:00.412Z"
}
```

| `status` | Anlamı |
|---|---|
| `ACCEPTED` | İHA komutu onayladı (`COMMAND_ACK` ACCEPTED ya da `PARAM_VALUE` istenen değer) veya komut adaptörde uygulandı (`FAULT_LINK_DROP`) |
| `REJECTED` | Geçersiz mesaj (şema, tanımsız tip, eksik veya aralık dışı parametre), bilinmeyen İHA ya da İHA'nın reddettiği komut (`COMMAND_ACK` ACCEPTED dışında) |
| `FAILED` | Komut uygulanamadı: MAVLink bağlantısı koptu ya da parametre istenen değeri almadı |
| `EXPIRED` | `issuedAt` uygulama anında 10 saniyeden eski; komut İHA'ya gönderilmedi |
| `TIMEOUT` | Beklenen cevap (`COMMAND_ACK` / `PARAM_VALUE` / görev ya da fence öğe isteği / `MISSION_ACK` / `MISSION_CURRENT`) 5 saniye içinde gelmedi. Birden çok adımlı komutlarda (`TAKEOFF`, `ROUTE`, `FENCE_UPLOAD`, `FAULT_LOW_ALTITUDE`, `FAULT_RESET`) süre her adım (ve her öğe) için ayrı işler |

- `detail` insan için kısa bir açıklamadır; içeriğine göre karar verilmemelidir.
- `commandId` geçerli bir UUID değilse sonuçta `null` olur. `droneId` okunamayan mesajlara sonuç gönderilemez (routing key yoktur); adaptör bunları yalnızca loglar.
- `completedAt` adaptörün saatine göre UTC'dir.

## Teslim garantisi

- Komutlar **en fazla bir kez** (at-most-once) uygulanır: adaptör mesajı alır almaz ack eder, sonra uygular. Adaptör tam bu arada çökerse komut uygulanmaz ve sonucu gelmez; çekirdek bunu sonuç gelmemesi olarak ele almalıdır (örneğin kendi zaman aşımıyla).
- Kuyrukta 10 saniyeden uzun bekleyen komutu RabbitMQ siler (`x-message-ttl`); adaptöre ulaşan komut da 10 saniyeden eskiyse `EXPIRED` olur. Böylece bağlantı kesintisinden sonra eski komutlar uygulanmaz.
- Bir İHA'nın komutları sırayla, birer birer uygulanır; farklı İHA'ların komutları paralel ilerler.
- Sonuçlar publisher confirms ile gönderilir. RabbitMQ'ya ulaşılamazken sonuçlar bellekte en fazla 1000 adet bekler; taşarsa en eskisi atılır.
