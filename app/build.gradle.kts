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

// The panel's addresses are never in the source: the build takes them from KANKI_PANEL_HOSTS (CI secret) or the
// Gradle property kankiPanelHosts (local.properties is not read for it). Entries may be split by commas, semicolons,
// spaces or new lines; each one becomes its origin "https://host[:port]" (the same rules as PanelLink.origin in the
// app: a missing scheme or http:// becomes https://, any path such as /admin, /panel, /sub, /sub/me and a trailing /
// is dropped, a user part or a bad host is refused). Without them the app still works with a full subscription link.
fun panelOrigin(entry: String): String? {
    var s = entry.filterNot { it.isISOControl() || it in '\u200b'..'\u200f' || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' || it == '\ufeff' }
        .trim().trim('"', '\'', '<', '>', ' ')
    if (s.isEmpty()) return null
    val scheme = s.indexOf("://")
    if (scheme >= 0) {
        val p = s.substring(0, scheme).lowercase()
        if (p != "https" && p != "http") return null
        s = s.substring(scheme + 3)
    }
    val end = s.indexOfFirst { it == '/' || it == '?' || it == '#' || it == '\\' }
    val auth = if (end >= 0) s.substring(0, end) else s
    if (auth.isEmpty() || '@' in auth || ' ' in auth) return null
    val colon = auth.lastIndexOf(':')
    val host = (if (colon >= 0) auth.substring(0, colon) else auth).lowercase().trimEnd('.')
    val port = if (colon >= 0) auth.substring(colon + 1).toIntOrNull() ?: return null else 443
    val hostRe = Regex("^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?)*$")
    if (port !in 1..65535 || host.length > 253 || !hostRe.matches(host)) return null
    return if (port == 443) "https://$host" else "https://$host:$port"
}
val panelHostsRaw: String = System.getenv("KANKI_PANEL_HOSTS") ?: (project.findProperty("kankiPanelHosts") as String?) ?: ""
val panelHosts: String = panelHostsRaw.split(',', ';', ' ', '\n', '\r', '\t').mapNotNull { panelOrigin(it) }.distinct().joinToString(",")
// a secret that is set but gives no address would make a build that cannot log in with a code: stop the build
if (panelHostsRaw.isNotBlank() && panelHosts.isEmpty()) {
    throw GradleException("KANKI_PANEL_HOSTS is set but has no valid https://host[:port] entry")
}
logger.lifecycle("Kanki panel hosts in this build: ${panelHosts.split(',').count { it.isNotEmpty() }}")
// Optional SPKI pins of the panel's certificates (KANKI_PANEL_PINS: comma separated base64 SHA-256 of the public key;
// list the backup key too, so a renewed certificate keeps working). Empty = the system's CAs and the hostname only.
val panelPins: String = (System.getenv("KANKI_PANEL_PINS") ?: (project.findProperty("kankiPanelPins") as String?) ?: "")
    .split(",").map { it.trim() }.filter { Regex("^[A-Za-z0-9+/]{43}=$").matches(it) }.joinToString(",")

android {
    namespace = "cafe.kankipro.vpn"
    compileSdk = 36

    defaultConfig {
        applicationId = "cafe.kankipro.vpn"
        minSdk = 26
        targetSdk = 35
        versionCode = 120
        versionName = "1.45.1"
        buildConfigField("String", "PANEL_HOSTS", "\"$panelHosts\"")
        buildConfigField("String", "PANEL_PINS", "\"$panelPins\"")
    }
    // یک APK جدا برای هر پردازنده (arm64 و armv7)، تا هر گوشی فقط هسته‌های خودش را بگیرد (حدود ۳۰ مگ).
    // نسخه‌ی شبیه‌ساز ساخته نمی‌شود؛ فقط همین دو پردازنده‌ی گوشی‌های واقعی.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
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
            // R8: کد کاتلین/کامپوز نسخه‌ی نهایی بهینه و کوچک می‌شود (اجرای روان‌تر و فایل کوچک‌تر).
            // کد خود برنامه و موتورهای اتصال (JNI و هسته‌های Go) دست نمی‌خورند (proguard-rules.pro). منابع
            // بی‌استفاده هم حذف می‌شوند؛ فونت‌ها (با اسم پیدا می‌شوند) و صدای ربات در res/raw/keep.xml نگه داشته شده‌اند.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            isDebuggable = false
            signingConfig = if (canSignRelease) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    // عکس صفحه‌ها بدون گوشی (Shots.kt، Robolectric): منابع و فونت‌ها در تست هم باشند و RenderNodeها
    // (لایه‌های Compose) واقعاً کشیده شوند
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { t ->
                t.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                t.maxHeapSize = "3g"
            }
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // libhysteria.so در واقع برنامه‌ی hysteria است؛ دست‌کاری (strip) نشود
            keepDebugSymbols += "**/libhysteria.so"
            // libxray.so هم برنامه‌ی xray است
            keepDebugSymbols += "**/libxray.so"
            // libkmix.so هم برنامه‌ی هسته‌ی KANKI MIX است
            keepDebugSymbols += "**/libkmix.so"
            // libawg.so هم برنامه‌ی هسته‌ی WireGuard/AmneziaWG گیمینگ است
            keepDebugSymbols += "**/libawg.so"
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

    // فقط برای عکس صفحه‌ها (Shots.kt)؛ داخل فایل نصبی نمی‌رود
    testImplementation(composeBom)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
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

        // Vazirmatn is in the repository (res/font/vazir_*.ttf)
        val g1 = "https://raw.githubusercontent.com/google/fonts/main/ofl"
        val g2 = "https://github.com/google/fonts/raw/main/ofl"
        grab(File(fontDir, "poppins_semibold.ttf"), "$g1/poppins/Poppins-SemiBold.ttf", "$g2/poppins/Poppins-SemiBold.ttf")
        grab(File(fontDir, "poppins_bold.ttf"), "$g1/poppins/Poppins-Bold.ttf", "$g2/poppins/Poppins-Bold.ttf")
        grab(File(fontDir, "orbitron.ttf"), "$g1/orbitron/Orbitron%5Bwght%5D.ttf", "$g2/orbitron/Orbitron%5Bwght%5D.ttf")
        grab(
            File(assetDir, "countries.geojson"),
            "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_50m_admin_0_countries.geojson",
            "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_110m_admin_0_countries.geojson",
            "https://raw.githubusercontent.com/martynafford/natural-earth-geojson/master/110m/cultural/ne_110m_admin_0_countries.json",
        )
    }
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(fetchAssets) }
