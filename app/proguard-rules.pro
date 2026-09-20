# Vosk reaches its native library through JNA, which finds classes by name at runtime.
# Shrinking is off for this build, but these are the rules the day it is turned on.
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
