# LiveKit-Android consumer ProGuard/R8 rules.
# These ship inside the AAR and are applied automatically to any consuming app.
# Manifest-declared components (Provider/Service/Receiver) are kept by AGP automatically.

# --- Public API surface ---
-keep public class com.morainet.livekit.LiveKit { public *; }
-keep public class com.morainet.livekit.LiveKitConfig { *; }
-keep public class com.morainet.livekit.LiveKitCountdown { public *; }
-keep public class com.morainet.livekit.model.** { *; }
-keep public interface com.morainet.livekit.model.LiveKitObserver { *; }

# --- Store SPI (consumers may implement or swap) ---
-keep public interface com.morainet.livekit.store.ILiveKitStore { *; }
-keep public class com.morainet.livekit.store.MmkvLiveKitStore { public *; }

# MMKV is optional (compileOnly); ignore if the consumer didn't add it.
-dontwarn com.tencent.mmkv.**
