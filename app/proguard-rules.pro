# Mantém as classes com métodos nativos (JNI) intactas no R8.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Tipos do engine são instanciados/refletidos pelo shim.
-keep class dev.catarina.assistant.engine.** { *; }

# Vosk: as classes conversam com o JNI e o R8 não enxerga essas referências.
-keep class org.vosk.** { *; }
