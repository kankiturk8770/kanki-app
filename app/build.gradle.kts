import java.io.File
import java.net.HttpURLConnection
import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---------------------------------------------------------------- امضای نسخه‌ی نهایی (release)
// روی گیت‌هاب: فایل کلید و رمزش از Secrets می‌آید (فایل .github/workflows/build.yml).
// روی کامپیوتر خودت: اگر کلید نبود، نسخه‌ی release با کلید دیباگ امضا می‌شود تا ساخت خطا ندهد
// (فقط برای تست؛ برای انتشار از APK ساخته‌شده روی گیت‌هاب استفاده کن).
val releaseKeystore = file("kanki-release.jks")
val releaseKeyPass: String? = System.getenv("KANKI_KEY_PASS")
val canSignRelease = releaseKeystore.exists() && !releaseKeyPass.isNullOrEmpty()

android {
    namespace = "cafe.kankipro.vpn"
    compileSdk = 36

    defaultConfig {
        applicationId = "cafe.kankipro.vpn"
        minSdk = 26
        targetSdk = 35
        versionCode = 25
        versionName = "1.7.0"
    }
    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseKeyPass
                keyAlias = "kanki"
                keyPassword = releaseKeyPass
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = if (canSignRelease) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // libhysteria.so در واقع برنامه‌ی hysteria است؛ دست‌کاری (strip) نشود
            keepDebugSymbols += "**/libhysteria.so"
        }
        resources { excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES", "META-INF/{AL2.0,LGPL2.1}", "META-INF/INDEX.LIST") }
    }
}

dependencies {
    // موتور اتصال امنزیا (وایرگارد معمولی را هم پشتیبانی می‌کند)
    implementation("com.zaneschepke:amneziawg-android:2.3.7")

    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

// ---------------------------------------------------------------- دانلود خودکار فونت‌ها و نقشه
// فونت‌ها و نقشه‌ی کشورها داخل مخزن نگه‌داشته نمی‌شوند. اگر قبل از ساخت وجود نداشته باشند،
// این تسک آن‌ها را دانلود می‌کند (روی اندروید استودیو). روی گیت‌هاب مرحله‌ی build.yml
// همین کار را از قبل انجام داده و این تسک چیزی برای دانلود پیدا نمی‌کند.
// اگر دانلود نشد، ساخت متوقف نمی‌شود و برنامه با فونت و نقشه‌ی پیش‌فرض اجرا می‌شود.
val fetchAssets by tasks.registering {
    group = "kanki"
    description = "فونت‌ها و نقشه‌ی کشورها را اگر نبودند دانلود می‌کند"
    val fontDir = file("src/main/res/font")
    val assetDir = file("src/main/assets")
    doLast {
        fun grab(dest: File, vararg urls: String) {
            if (dest.exists() && dest.length() > 0) return
            dest.parentFile.mkdirs()
            for (u in urls) {
                try {
                    val c = URL(u).openConnection() as HttpURLConnection
                    c.connectTimeout = 15000
                    c.readTimeout = 30000
                    c.inputStream.use { input -> dest.outputStream().use { out -> input.copyTo(out) } }
                    if (dest.length() > 0) return
                } catch (e: Exception) {
                    logger.warn("دانلود نشد: $u (${e.message})")
                }
            }
            dest.delete()
            logger.warn("هشدار: ${dest.name} دانلود نشد؛ برنامه با پیش‌فرض اجرا می‌شود")
        }

        val v1 = "https://raw.githubusercontent.com/rastikerdar/vazirmatn/master/fonts/ttf"
        val v2 = "https://cdn.jsdelivr.net/gh/rastikerdar/vazirmatn@v33.003/fonts/ttf"
        val g1 = "https://raw.githubusercontent.com/google/fonts/main/ofl"
        val g2 = "https://github.com/google/fonts/raw/main/ofl"
        grab(File(fontDir, "vazir_regular.ttf"), "$v1/Vazirmatn-Regular.ttf", "$v2/Vazirmatn-Regular.ttf")
        grab(File(fontDir, "vazir_bold.ttf"), "$v1/Vazirmatn-Bold.ttf", "$v2/Vazirmatn-Bold.ttf")
        grab(File(fontDir, "vazir_black.ttf"), "$v1/Vazirmatn-Black.ttf", "$v2/Vazirmatn-Black.ttf")
        grab(File(fontDir, "poppins_semibold.ttf"), "$g1/poppins/Poppins-SemiBold.ttf", "$g2/poppins/Poppins-SemiBold.ttf")
        grab(File(fontDir, "poppins_bold.ttf"), "$g1/poppins/Poppins-Bold.ttf", "$g2/poppins/Poppins-Bold.ttf")
        grab(File(fontDir, "orbitron.ttf"), "$g1/orbitron/Orbitron%5Bwght%5D.ttf", "$g2/orbitron/Orbitron%5Bwght%5D.ttf")
        grab(
            File(assetDir, "countries.geojson"),
            "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_110m_admin_0_countries.geojson",
            "https://raw.githubusercontent.com/martynafford/natural-earth-geojson/master/110m/cultural/ne_110m_admin_0_countries.json",
        )
    }
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(fetchAssets) }
