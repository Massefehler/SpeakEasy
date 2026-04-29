# SpeakEasy ProGuard/R8-Regeln (Release-Build)
# proguard-android-optimize.txt deckt AndroidX/Material/ViewBinding bereits ab;
# die folgenden Regeln sichern speziell den JNI-Pfad zu libspeakeasy_opus.so.

# OpusJni hält die Java-Counterparts unserer C-Funktionen. Class-Name + Methoden
# müssen im Bytecode unverändert bleiben, sonst findet RegisterNatives sie nicht.
-keep class com.speakeasy.intercom.OpusJni { *; }

# Sicherheitsnetz: jede Klasse mit native-Methoden behält Methodenname und
# Signatur (falls künftige Module weitere JNI-Brücken bekommen).
-keepclasseswithmembernames class * { native <methods>; }

# Stack-Traces im Crash-Log lesbar halten — nützlich, wenn Tester per "Mit Logs
# senden" Crash-Reports schicken. Mapping wird vom Play-Console-Upload zusätzlich
# zur Deobfuscation ausgewertet (mapping.txt).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
