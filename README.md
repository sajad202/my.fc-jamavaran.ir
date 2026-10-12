# اپلیکیشن آکادمی جام‌آوران

## بیلد رایگان با GitHub Actions
1. محتویات این پوشه را در یک ریپازیتوری GitHub آپلود کنید.
2. تب Actions → بیلد خودکار → از Artifacts فایل APK را دانلود کنید.

## بیلد با Android Studio
Open این پوشه → Build → Build Bundle(s)/APK(s) → Build APK(s)

## امضا برای استور
    keytool -genkey -v -keystore keystore.jks -keyalg RSA -keysize 2048 -validity 10000 -alias upload

## پوش نوتیفیکیشن
google-services.json را از کنسول فایربیس (با همین Package Name) داخل پوشه app/ بگذارید.