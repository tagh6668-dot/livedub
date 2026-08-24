# LiveDub — دوبله زنده فارسی

اپلیکیشن اندروید برای دوبله همزمان صدای دستگاه به فارسی با استفاده از مدل
`gemini-3.5-live-translate-preview` (Gemini Live API).

## قابلیت‌ها
- دکمه استارت/استاپ در اپ؛ صدا به‌صورت زنده ترجمه و پخش می‌شود
- کلید API شخصی Gemini داخل خود اپ وارد می‌شود (در SharedPreferences ذخیره می‌شود)
- اسلایدر نسبت صدای دوبله به صدای اصلی (۰ تا ۱۰۰٪)
  - صدای خروجی دوبله با ضریب تنظیم‌شده scale می‌شود
  - صدای اصلی بقیه اپ‌ها (یوتیوب و…) با ضریب معکوس کاهش می‌یابد (API مخفی IPlayer.setVolume — همان API «تنظیم صدا برای هر برنامه» MIUI)
- نمایش متن ورودی و دوبله (transcript) زنده
- زبان مقصد: فارسی (`fa`) — قابل تغییر در `DubService.kt`

## نحوه کار
1. میکروفن/صدای دستگاه → PCM 16kHz mono → WebSocket به Live API
2. سرور → PCM 24kHz دوبله فارسی → AudioTrack
3. نسبت صدا روی نمونه‌های 16-bit اعمال می‌شود

## ساخت
GitHub Action: `.github/workflows/build.yml` — APK debug در artifacts هر run.
