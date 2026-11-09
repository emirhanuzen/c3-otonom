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
| `TAKEOFF` | `alt` (m, eve göre, 30..300; verilmezse 60) | Sırayla: `PARAM_SET` `TKOFF_ALT` = `alt`; `COMMAND_LONG` `MAV_CMD_DO_SET_MODE`, mod TAKEOFF; `COMMAND_LONG` `MAV_CMD_COMPONENT_ARM_DISARM`, param1 = 1 | `PARAM_VALUE` aynı değer, mod `COMMAND_ACK` ACCEPTED, arm `COMMAND_ACK` ACCEPTED |
| `DISARM` | - | `COMMAND_LONG` `MAV_CMD_COMPONENT_ARM_DISARM`, param1 = 0, param2 = 0 (force yok; otopilot havadayken reddeder) | `COMMAND_ACK` ACCEPTED |
| `FAULT_BATTERY_DRAIN` | - | `PARAM_SET` `SIM_BATT_CAP_AH` = 0.3 | `PARAM_VALUE` aynı değer |
| `FAULT_GPS_LOSS` | `enabled` (bool; `true` = GPS kaybı) | `PARAM_SET` `SIM_GPS1_ENABLE` (0/1) veya, o yoksa, `SIM_GPS_DISABLE` (1/0) | `PARAM_VALUE` aynı değer |
| `FAULT_LOW_ALTITUDE` | - | `PARAM_SET` `ALT_HOLD_RTL` = 2000 (cm), ardından RTL | İkisi de başarılı |
| `FAULT_LINK_DROP` | `seconds` (1..60) | Hiçbir şey | Adaptör o İHA'nın telemetrisini bu süre boyunca yayınlamaz; hemen ACCEPTED |
| `FAULT_RESET` | - | `SIM_BATT_CAP_AH` = 10, GPS açık, `ALT_HOLD_RTL` = 10000; süren link drop iptal edilir | Bütün parametreler doğrulanır |

- `FAULT_*` tipleri yalnızca SITL'de anlamlıdır, çünkü simülatörün `SIM_*` parametrelerini değiştirir.
- Mod numaraları ArduPlane'e göredir (AUTO = 10, LOITER = 12, TAKEOFF = 13).
- `TAKEOFF` adımları birbirini bekler: `TKOFF_ALT` doğrulanmadan mod, mod onaylanmadan arm gönderilmez. ArduPlane TAKEOFF modunda arm edilince kalkar. Bir adım başarısız olursa sonraki adımlar gönderilmez.
- Arm reddedilirse (`REJECTED`) `detail`, komut başlamadan en fazla 3 saniye öncesinden komutun sonuna kadar o İHA'dan gelen ve `PreArm:` ile başlayan `STATUSTEXT` metinlerini, tekrarsız ve `; ` ile ayrılmış olarak içerir; örneğin `arming refused by the aircraft: MAV_RESULT_FAILED; PreArm: GPS not healthy`. ArduPilot bu metinleri red `COMMAND_ACK`'inden sonra gönderdiği için adaptör sonucu yayınlamadan önce 1 saniye daha bekler; bu bekleme `TIMEOUT` üretmez.
- `TAKEOFF` ve `DISARM` bu sürümde geriye uyumlu olarak eklendi; `schemaVersion` 1 kalır. Bu tipleri tanımayan eski bir adaptör onları `REJECTED` ("unknown command type") ile cevaplar.
- GPS parametresinin adı İHA'ya `PARAM_REQUEST_READ` ile sorulur (ArduPilot 4.6'da `SIM_GPS_DISABLE` yerini `SIM_GPS1_ENABLE`'a bıraktı) ve adaptör çalıştığı sürece İHA başına önbellekte tutulur.

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
| `TIMEOUT` | Beklenen cevap (`COMMAND_ACK` / `PARAM_VALUE`) 5 saniye içinde gelmedi. Birden çok adımlı komutlarda (`TAKEOFF`, `FAULT_LOW_ALTITUDE`, `FAULT_RESET`) süre her adım için ayrı işler |

- `detail` insan için kısa bir açıklamadır; içeriğine göre karar verilmemelidir.
- `commandId` geçerli bir UUID değilse sonuçta `null` olur. `droneId` okunamayan mesajlara sonuç gönderilemez (routing key yoktur); adaptör bunları yalnızca loglar.
- `completedAt` adaptörün saatine göre UTC'dir.

## Teslim garantisi

- Komutlar **en fazla bir kez** (at-most-once) uygulanır: adaptör mesajı alır almaz ack eder, sonra uygular. Adaptör tam bu arada çökerse komut uygulanmaz ve sonucu gelmez; çekirdek bunu sonuç gelmemesi olarak ele almalıdır (örneğin kendi zaman aşımıyla).
- Kuyrukta 10 saniyeden uzun bekleyen komutu RabbitMQ siler (`x-message-ttl`); adaptöre ulaşan komut da 10 saniyeden eskiyse `EXPIRED` olur. Böylece bağlantı kesintisinden sonra eski komutlar uygulanmaz.
- Bir İHA'nın komutları sırayla, birer birer uygulanır; farklı İHA'ların komutları paralel ilerler.
- Sonuçlar publisher confirms ile gönderilir. RabbitMQ'ya ulaşılamazken sonuçlar bellekte en fazla 1000 adet bekler; taşarsa en eskisi atılır.
