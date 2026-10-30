# R8 برای نسخه‌ی نهایی: فقط کتابخانه‌ها (کامپوز، کاتلین، اندرویدایکس) بهینه و کوچک می‌شوند.

# کد خود برنامه دست نخورد: hev-socks5-tunnel تابع‌های JNI را با اسم کلاس ثبت می‌کند (TProxyService)،
# فونت‌ها با بازتاب از R$font خوانده می‌شوند، سرویس‌ها و Workerها از مانیفست و WorkManager ساخته می‌شوند.
-keep class cafe.kankipro.vpn.** { *; }

# موتور امنزیا / وایرگارد (JNI، سرویس VPN خودش، و آمار ترافیک که با getMethod خوانده می‌شود)
-keep class org.amnezia.** { *; }
-keep class com.wireguard.** { *; }
-keep class com.zaneschepke.** { *; }
-dontwarn org.amnezia.**
-dontwarn com.wireguard.**
-dontwarn com.zaneschepke.**

# نشانه‌هایی که بازتاب و کاتلین لازم دارند
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable

# مبهم‌سازی قوی‌تر: کلاس‌هایی که نگه داشته نشده‌اند (کتابخانه‌ها) به ریشه منتقل و دسترسی‌هاشان بازتر می‌شود
# تا R8 بیشتر ادغام و تغییرِ نام بدهد. کد cafe.kankipro.vpn و موتورها بالا نگه داشته شده‌اند، پس دست نمی‌خورند.
-allowaccessmodification
-repackageclasses ''
