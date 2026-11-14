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

# Crypto Wallet (web3j crypto + BouncyCastle): classes and resources (the BIP-39 word list) are looked up by name
-keep class org.web3j.** { *; }
-dontwarn org.web3j.**
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# WalletConnect / Reown AppKit (from the Reown Android docs)
-keepattributes *Annotation*
-keep class com.sun.jna.** { *; }
-keepclassmembers class com.sun.jna.** {
    native <methods>;
    *;
}
-keep class uniffi.** { *; }
-dontwarn uniffi.**
-dontwarn com.sun.jna.**
-keep class com.reown.** { *; }
-dontwarn com.reown.**
