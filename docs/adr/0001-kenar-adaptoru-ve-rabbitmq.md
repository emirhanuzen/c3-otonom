# ADR-001: Kenar adaptörü (Python) ile çekirdek (Spring Boot) arasında RabbitMQ kullanımı

## Durum

Kabul edildi (2026-10-22)

## Bağlam

- İHA'lar (ArduPlane SITL) ikili (binary) MAVLink protokolüyle haberleşir.
- Çekirdek sistem Java 21 ve Spring Boot ile yazılacak.
- MAVLink için referans kütüphane Python'daki pymavlink'tir. `experiments/` altındaki deneylerde 10 İHA'lık bir filodan eş zamanlı telemetri başarıyla okundu.
- Hedef yük 10 İHA x 10 Hz, yani yaklaşık 100 mesaj/saniyedir. İleride gerçek uçak verisi (ADS-B) gibi başka kaynaklar da eklenecek.
- Bir bileşen çöktüğünde telemetri kaybolmamalı, bileşenler birbirinden bağımsız olarak yeniden başlatılabilmelidir.

## Karar

- MAVLink'i okuyan, Python ile yazılmış bir **kenar adaptörü** olacak. Adaptör MAVLink mesajlarını versiyonlu bir JSON şemasına (`schemaVersion` alanı içeren) çevirip RabbitMQ'ya yayınlayacak.
- Spring Boot çekirdeği bu mesajları Spring AMQP ile RabbitMQ'dan tüketecek. Çekirdek MAVLink'ten habersiz olacak.
- Komutlar ters yönde aynı yoldan akacak: çekirdek → RabbitMQ → adaptör → İHA.
- İHA kimliği MAVLink System ID'sinden alınacak ve aynı İHA'ya ait mesajların sırası korunacak.

## Teslim garantileri

### Adaptör tarafı

- Adaptör telemetriyi **en az bir kez** (at-least-once) teslim eder:
  - Her anlık görüntü önce bellekteki sınırlı bir yerel tampona (outbox) yazılır.
  - RabbitMQ **publisher confirms** ile mesajı onaylamadan tampondan çıkmaz. Reddedilen (nack) ya da bağlantı koptuğu için onaylanmayan mesaj, sırası bozulmadan tampona geri konur ve bağlantı gelince yeniden gönderilir.
  - Mesajlar `mandatory` bayrağıyla gönderilir. Hiçbir kuyruğa yönlendirilemeyen mesaj sessizce kaybolmaz; adaptör tarafından sayılır ve loglanır. Gidecek kuyruk olmadığı için tekrar denenmez.
- `seq` ve `timestamp` mesaj üretilirken atanır. Tampondan geç gönderilen mesaj, ölçüldüğü anı ve sırasını korur.
- `seq` her adaptör oturumunda 0'dan başlar. Adaptör her başladığında rastgele bir oturum kimliği (`sessionId`, UUID) üretir ve her mesaja ekler; bu değer süreç çalıştığı sürece değişmez.
- Tampon sınırlıdır (varsayılan 6000 mesaj, 10 İHA x 10 Hz ile yaklaşık 1 dakika). Taşarsa en eski mesajlar atılır; çünkü taze telemetri eskisinden değerlidir. Atılan mesajlar tüketicide ilgili İHA'nın `seq` dizisinde boşluk olarak görünür, böylece kayıp fark edilir.
- **Bilinen sınırlama:** Tampon yalnızca bellektedir. Adaptör süreci çökerse henüz onaylanmamış mesajlar kaybolur.

### Tüketici tarafı (ileride Spring Boot)

- En az bir kez teslim, aynı mesajın birden fazla gelebileceği anlamına gelir (örneğin onay yolda kaybolup mesaj yeniden gönderildiğinde).
- Tüketici **manuel ack** kullanır: mesajı ancak işleyip kaydettikten sonra onaylar. Çökme durumunda onaylanmamış mesaj kuyrukta kalır ve yeniden teslim edilir.
- İşleme **tekrar edilebilir** (idempotent) olur: benzersizlik `(droneId, sessionId, seq)` üçlüsü üzerinden yapılır; `seq` yeniden başlatmada sıfırlandığı için tek başına `(droneId, seq)` yetmez. Daha önce işlenmiş bir üçlü geldiğinde mesaj yok sayılır ve yalnızca onaylanır.

### Geç gelen telemetri

- Kesinti sonrasında tampondan gelen mesajlar geçmişe aittir.
- Geç gelen telemetri yine de veritabanına kaydedilir; uçuş geçmişi eksiksiz kalır.
- Mesajın yaşı (`timestamp` ile alınma anı arasındaki fark) belirli bir eşiği (yaklaşık 2 saniye) aşıyorsa mesaj **canlı kural motoruna girmez**. Böylece eski bir konum ya da yakıt değeri yüzünden yanlış anomali uyarısı ya da otonom karar üretilmez.

## Değerlendirilen alternatifler

1. **Her şeyi Java ile yazmak.** Tek dil kullanılmış olurdu, ancak Java'daki MAVLink kütüphaneleri pymavlink kadar olgun ve iyi belgelenmiş değil. Bu yüzden seçilmedi.
2. **Adaptörün veriyi doğrudan HTTP ile Spring Boot'a göndermesi.** Daha basit olurdu, ancak çekirdek çöktüğünde telemetri kaybolur ve iki bileşen birbirine sıkı bağlanır. Dayanıklılık gereksinimini karşılamadığı için seçilmedi.
3. **RabbitMQ yerine Apache Kafka.** Kafka yüksek hacimli olay akışları için tasarlanmıştır ve işletmesi ağırdır; 100 mesaj/saniyelik yük için gereksizdir. Ayrıca tek tek İHA'lara yönlendirilen komutlar için RabbitMQ'nun yönlendirme (routing) modeli daha uygundur. Bu yüzden seçilmedi.

## Sonuçlar

### Olumlu

- Çekirdek dış protokollerden yalıtılır.
- Yeni veri kaynakları yalnızca yeni bir adaptör yazılarak eklenir.
- Çekirdek kapalıyken mesajlar kuyrukta bekler, kaybolmaz.
- Her bileşen kendi ekosistemine en uygun dilde yazılır.

### Olumsuz / bedeller

- İki dil ve iki ayrı derleme süreci yönetilmelidir.
- JSON şemasının iki tarafta tutarlı tutulması gerekir.
- İşletilmesi gereken ek bir altyapı bileşeni (RabbitMQ) eklenir.
- Mesaj başına birkaç milisaniyelik ek gecikme oluşur.
