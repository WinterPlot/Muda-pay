-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# osmdroid (agent locator map)
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# OpenCV (KYC scanner): JNI looks these classes up by name
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**
