# Niko — Android uygulaması (sürekli dinleyen sesli asistan)

Başına "Niko" diyerek konuşursun: "Niko anneyi ara", "Niko yarın sabah 7'ye alarm kur",
"Niko anneme mesaj at geliyorum", "Niko YouTube'u aç", "Niko el fenerini aç"...

## Kurulum (Android Studio gerekmez) — yaklaşık 10 dakika
1. github.com'da ücretsiz hesap aç, **New repository** (Public) oluştur.
2. Bu klasörün **içindekileri** repoya yükle (Add file → Upload files). `.github` klasörü de gitmeli.
3. Repo → **Actions** sekmesi → "Niko APK derle" çalışır (3-6 dk). Yeşil tik olunca bitti.
4. Telefonda repo → **Releases** → "Niko (son sürüm)" → `app-debug.apk` indir.
   (Release gelmezse: Actions → son çalışma → Artifacts → Niko-APK.)
5. APK'yı aç → "Bilinmeyen kaynaklardan yükleme"ye izin ver → Yükle.
   Play Protect uyarırsa "Yine de yükle" (uygulamayı sen yaptın, Play'de yayınlı değil).
6. Niko'yu aç → izinleri ver (mikrofon, telefon, rehber, SMS, konum, bildirim).

## Sürekli dinleme
- "Sürekli dinlemeyi başlat" → bildirim çubuğunda "Niko dinliyor" çıkar. Ekran kilitliyken de dinler.
- Yalnızca **"Niko"** dediğinde tepki verir (televizyonu/ortam sesini yok sayar). Cevaptan sonra 12 sn "Niko" demeden devam edebilirsin.
- Ayarlar → "Sohbet modu" açarsan "Niko" demeden her şeyi dinler.
- Takılırsa kendini yeniden başlatır (bekçi). "Niko uyu" dersen durur.
- Ayarlar → **Pil optimizasyonunu kapat** (aksi halde telefon arka planda öldürebilir).

## Güvenlik
- Arama, SMS ve konum gönderme komutlarından sonra 3,5 sn **"iptal" deme penceresi** vardır.
- "Niko acil durum" → acil kişine konumunu SMS atar ve 112'yi arar (yine iptal edebilirsin).

## Bilmen gerekenler (dürüst sınırlar)
- Bu APK'yı ben derlemedim; GitHub derler. Kod burada derleme denetiminden geçti ama **gerçek telefonda denenmedi**.
- Arka plandayken Android başka uygulama açmayı engelleyebilir (YouTube, harita, alarm...). Engellenirse
  bildirim bırakır, dokununca açılır. Ayarlardaki "Üstte göster izni" çoğu telefonda bunu çözer; yeni Android'lerde kısıtlı olabilir.
- Arama, SMS, el feneri, ses, pil, saat, konum gönderme arka planda da çalışır.
- Konuşma tanıma Google'ın servisini kullanır: **internet** ve Google uygulaması gerekir. Bazı telefonlarda dinleme "bip" sesi çıkabilir (Ayarlar'da kısma seçeneği var).
- Sürekli dinleme pil harcar. İstediğin zaman durdurabilirsin.
- Bilgi soruları için (isteğe bağlı) Ayarlar'a Niko web sunucunun adresini ve PIN'ini gir; yoksa yerel cevaplarla sınırlı kalır.
- Telefondaki rehber ve konum **sunucuya gönderilmez**; yalnızca sohbet cümlesi (sunucu ayarlıysa) gider.
