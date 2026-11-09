# Edge Adapter

ArduPlane SITL filosundan MAVLink telemetrisini okur, her İHA için JSON anlık görüntüsüne çevirir ve RabbitMQ'daki `otonom.telemetry` topic exchange'ine yayınlar. Ters yönde `otonom.command` exchange'inden komut alır, İHA'ya MAVLink ile uygular ve sonucunu yayınlar.

## Gereksinimler

- WSL Ubuntu içinde Python 3.10 veya üstü
- Çalışan ArduPlane SITL filosu
- Çalışan RabbitMQ (proje kökündeki `docker-compose.yml`)
- Proje kökünde doldurulmuş bir `.env` dosyası (`.env.example` dosyasından kopyalanabilir)

## Kurulum

WSL Ubuntu terminalinde:

```sh
sudo apt install python3-venv          # venv modülü yoksa bir kez
cd /mnt/c/Users/Emirhan/Desktop/otonom-c3/edge-adapter
python3 -m venv ~/venv-edge
source ~/venv-edge/bin/activate
pip install -r requirements.txt
```

Sanal ortam, Windows dosya sistemindeki (`/mnt/c`) yavaşlık ve izin sorunlarından kaçınmak için WSL ev dizininde (`~/venv-edge`) tutulur.

## Çalıştırma

1. RabbitMQ'yu başlat (proje kökünde): `docker compose up -d rabbitmq`
2. SITL filosunu başka bir terminalde başlat: `sim_vehicle.py -v ArduPlane --count 10 --auto-sysid`
3. Adaptörü çalıştır:

```sh
cd /mnt/c/Users/Emirhan/Desktop/otonom-c3/edge-adapter
source ~/venv-edge/bin/activate
python -m edge_adapter.main
```

Durdurmak için Ctrl+C.

## Testler

Testler SITL ya da RabbitMQ gerektirmez. Geliştirme bağımlılıklarını bir kez kur, sonra `edge-adapter/` klasöründen çalıştır:

```sh
cd /mnt/c/Users/Emirhan/Desktop/otonom-c3/edge-adapter
source ~/venv-edge/bin/activate
pip install -r requirements-dev.txt
pytest                                   # bütün testler
pytest tests/test_schema.py              # tek dosya
pytest tests/test_mavlink_mapper.py -k heading   # adında "heading" geçen testler
```

## Katmanlar

```
mavlink_reader  ->  mavlink_mapper  ->  schema  ->  outbox          ->  publisher
(ham MAVLink)       (DroneTelemetry)    (JSON)      (yerel tampon)      (RabbitMQ)
```

- `telemetry.py`: protokolden bağımsız alan modeli (`DroneTelemetry`); dış kütüphane import etmez.
- `mavlink_mapper.py`: MAVLink'e özel bütün bilgi (birim dönüşümleri, 255/65535 "bilinmiyor" değerleri, uçuş modu ve armed çözümü).
- `schema.py`: `DroneTelemetry`'den JSON anlık görüntüsü üretir; MAVLink'i bilmez.

Komut yolu ters yöndedir:

```
command_channel  ->  command_schema  ->  command_executor  ->  mavlink_reader bağlantısı
(RabbitMQ)           (Command)           (MAVLink adımları)     (İHA)
```

- `command.py`: komut ve sonuç alan modeli, parametre doğrulama; dış kütüphane import etmez.
- `command_schema.py`: komut/sonuç JSON'u (sözleşme: `docs/contracts/command-v1.md`).
- `command_executor.py`: komutu MAVLink mesajlarına çevirir, `COMMAND_ACK` / `PARAM_VALUE` cevaplarını bekleyen komutla eşleştirir, zaman aşımını izler; reddedilen kalkışın sebebi için `PreArm:` ile başlayan `STATUSTEXT` mesajlarını toplar.
- `command_channel.py`: ayrı bir RabbitMQ bağlantısıyla `edge.commands` kuyruğunu tüketir ve sonuçları yayınlar.

Thread yoktur: komutlar da ana döngüde işlenir ve İHA'nın okunduğu bağlantının üzerinden gönderilir.

Başlama sırası önemli değil. Adaptör, açık olmayan İHA'lara 5 saniyede bir, RabbitMQ'ya ise artan aralıklarla (en fazla 30 saniye) yeniden bağlanmayı dener.

## Konfigürasyon

Değerler ortam değişkenlerinden okunur. Proje kökündeki `.env` dosyası otomatik yüklenir; kabukta tanımlı değişkenler `.env` dosyasındakileri ezer.

| Değişken | Varsayılan | Açıklama |
|---|---|---|
| `RABBITMQ_HOST` | `localhost` | RabbitMQ sunucusu |
| `RABBITMQ_PORT` | `5672` | AMQP portu |
| `RABBITMQ_USER` | (zorunlu) | RabbitMQ kullanıcısı |
| `RABBITMQ_PASSWORD` | (zorunlu) | RabbitMQ şifresi |
| `FLEET_SIZE` | `10` | İHA sayısı |
| `SITL_BASE_PORT` | `5762` | İlk SITL örneğinin SERIAL1 TCP portu |
| `SITL_PORT_STEP` | `10` | Ardışık SITL örnekleri arasındaki port farkı |
| `PUBLISH_RATE_HZ` | `10` | İHA başına saniyedeki anlık görüntü sayısı |
| `STALE_AFTER_S` | `3` | Bu kadar saniye veri gelmeyen İHA için yayın yapılmaz |
| `OUTBOX_MAX_MESSAGES` | `6000` | Yerel tamponun kapasitesi; dolunca en eski mesaj atılır |
| `OUTBOX_FLUSH_BATCH` | `200` | Tek döngüde tampondan gönderilecek en fazla mesaj |
| `RABBITMQ_MAX_BACKOFF_S` | `5` | RabbitMQ'ya yeniden bağlanma beklemesinin üst sınırı (saniye) |

## Mesaj formatı

Routing key `telemetry.<droneId>`, `content_type` `application/json`, mesajlar persistent.

Adaptör kuyruk tanımlamaz. Mesajlar `mandatory` ile gönderilir; exchange'e bağlı bir kuyruk yoksa RabbitMQ mesajı geri döndürür, adaptör bunu "unroutable" olarak sayar ve loglar, tekrar denemez.

## Teslim garantisi

Anlık görüntüler önce bellekteki sınırlı bir tampona (outbox) yazılır ve RabbitMQ onayladıktan (publisher confirms) sonra tampondan çıkar. RabbitMQ kapalıyken mesajlar tamponda bekler, bağlantı gelince sırayla gönderilir; geç giden mesajlar kendi `seq` ve `timestamp` değerlerini korur. Tampon dolarsa en eski mesajlar atılır. Adaptör süreci çökerse tampondaki mesajlar kaybolur. Ayrıntılar: `docs/adr/0001-kenar-adaptoru-ve-rabbitmq.md`.

10 saniyelik özet log satırı, İHA başına yayınlanan mesajların yanında tamponda bekleyen, taşma yüzünden atılan ve unroutable olan mesaj sayılarını gösterir.

```json
{
  "schemaVersion": 2,
  "droneId": 1,
  "sessionId": "0b6f1c2e-5d4a-4e8b-9a37-2f1d6c8e4b90",
  "seq": 42,
  "timestamp": "2026-10-24T12:34:56.789Z",
  "position": {"lat": 38.6063, "lon": 39.2914, "altMsl": 1012.3, "altRel": 100.2, "heading": 87.5},
  "velocity": {"groundSpeed": 22.1, "airSpeed": 21.8, "climbRate": 0.3},
  "battery": {"percent": 97, "voltage": 12.58},
  "gps": {"fixType": 3, "satellites": 10},
  "status": {"mode": "AUTO", "armed": true}
}
```

- Birimler: metre, m/s, derece, volt.
- Henüz gelmemiş ya da otopilotun "bilinmiyor" olarak bildirdiği alanlar `null` olur.
- `fixType`, MAVLink `GPS_FIX_TYPE` değeridir (0 GPS yok, 1 fix yok, 2 2D, 3 3D, ...).
- `sessionId`, adaptör her başladığında bir kez üretilen rastgele UUID'dir; adaptör çalıştığı sürece değişmez.
- `seq` her adaptör oturumunda her İHA için 0'dan başlar ve mesaj üretilirken atanır; tampon taşması ya da unroutable yüzünden kaybolan mesajlar tüketicide `seq` boşluğu olarak görünür.

## Komutlar

Sözleşmenin tamamı: `docs/contracts/command-v1.md`.

- Adaptör `otonom.command` exchange'ini ve `edge.commands` kuyruğunu (durable, `x-message-ttl` 10 sn, `command.#`) tanımlar ve telemetriden ayrı bir bağlantıyla tüketir. Bağlantı koparsa telemetri yayıncısıyla aynı backoff ile yeniden bağlanır.
- Mesaj alınır alınmaz ack edilir, sonra uygulanır (at-most-once): adaptör çökerse komut ikinci kez çalışmaz.
- `issuedAt` 10 saniyeden eskiyse komut uygulanmaz ve `EXPIRED` döner. Tanımsız tip, eksik parametre ya da bilinmeyen İHA `REJECTED` döner. İHA 5 saniye içinde cevap vermezse `TIMEOUT` döner.
- Sonuçlar `result.<droneId>` routing key'iyle publisher confirms kullanılarak yayınlanır.
- Her komut tek satır loglanır:

```
Command 3f2b8c1e-... drone=3 type=RTL status=ACCEPTED: MAV_CMD_NAV_RETURN_TO_LAUNCH accepted
```

`FAULT_LINK_DROP` İHA'ya bir şey göndermez; adaptör o İHA'nın anlık görüntülerini istenen süre boyunca üretmez, böylece çekirdek bağlantı kopmasını görür. Bu sırada `seq` ilerlemez.
