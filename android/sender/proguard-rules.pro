-keepclassmembers enum mx.quili.smartprojector.protocol.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keepclassmembers class * extends android.media.MediaCodec$Callback {
    <methods>;
}
