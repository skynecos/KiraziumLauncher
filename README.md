<p align="center">
  <img src="app_pojavlauncher/src/main/res/drawable-nodpi/ic_kirazium_launcher.webp" width="180" alt="Kirazium Launcher Logo">
</p>

<h1 align="center">Kirazium Launcher</h1>

<p align="center">
  Kirazium için özelleştirilmiş Android Minecraft: Java Edition launcher'ı.
</p>

<p align="center">
  <b>Sunucu:</b> play.kirazium.com
</p>

---

## Kirazium Launcher

Kirazium Launcher, Android cihazlarda Minecraft: Java Edition çalıştırmak için geliştirilen ve Kirazium sunucusuna özel olarak özelleştirilen bir launcher projesidir.

Projenin amacı; kurulumu mümkün olduğunca otomatik hale getirmek, Kirazium'a doğrudan erişim sağlamak ve özellikle düşük donanımlı Android cihazlarda daha iyi bir oyun deneyimi sunmaktır.

### Özellikler

- Kirazium'a özel arayüz ve marka tasarımı
- Varsayılan Kirazium sunucu entegrasyonu
- Normal ve düşük grafik profilleri
- Düşük donanımlı telefonlar için performans odaklı ayarlar
- OptiFine ve özel model uyumluluğu
- Minecraft Java Edition'ı Android üzerinde çalıştırma
- Fabric/mod desteği

> Proje aktif geliştirme aşamasındadır. Bazı özellikler değişebilir veya geliştirilebilir.

## Derleme

Projeyi klonladıktan sonra launcher'ı şu komutla derleyebilirsiniz:

```bash
./gradlew :app_pojavlauncher:assembleDebug
```

Windows üzerinde:

```bat
gradlew.bat :app_pojavlauncher:assembleDebug
```

Oluşan APK, Gradle build çıktıları altında bulunur.

## Kaynak ve Lisans

Kirazium Launcher, açık kaynaklı [MojoLauncher](https://github.com/MojoLauncher/MojoLauncher) ve onun temel aldığı [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher) çalışmaları üzerine geliştirilmiştir.

Bu repository, upstream projelerin lisans şartlarını korur. Ayrıntılar için [LICENSE](LICENSE) dosyasına bakabilirsiniz.

## Üçüncü Taraf Bileşenler

Projede OpenJDK, LWJGL, GLFW, Mesa ve diğer açık kaynak bileşenler kullanılmaktadır. İlgili bileşenlerin kendi lisansları geçerlidir.

---

<p align="center">
  <b>Kirazium Launcher</b><br>
  Android'de Kirazium deneyimi için geliştiriliyor.
</p>
