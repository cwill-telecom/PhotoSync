# PhotoSync ProGuard rules
-keepattributes *Annotation*
-keepclassmembers class * extends androidx.room.RoomDatabase {
    abstract <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
