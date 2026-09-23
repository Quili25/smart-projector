# Keep the wire-format types: they are constructed reflectively by neither side, but
# enum ordinals and names are part of the protocol contract and must not be renamed
# in a way that breaks a mixed-version pairing during debugging.
-keepclassmembers enum mx.quili.smartprojector.protocol.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# MediaCodec callbacks are invoked from native code.
-keepclassmembers class * extends android.media.MediaCodec$Callback {
    <methods>;
}
