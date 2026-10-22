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
