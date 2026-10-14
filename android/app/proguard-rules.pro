# us2us release rules. Libraries ship their own consumer rules (libsignal's JNI entry points,
# SQLCipher, OkHttp, Compose); these cover what reflection or native code reaches in the app.

# Line numbers in crash reports, without file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Protocol buffers (lite) in :core: the runtime finds message fields by name.
-keep class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }

# SQLCipher: its native code looks up these classes and members by name.
-keep class net.zetetic.database.** { *; }
-keep class net.zetetic.database.sqlcipher.** { *; }

# libsignal: its native code finds more of its classes and members by name than its consumer rules
# keep. With them renamed, the first message to a new contact never leaves "Sending" (no error).
-keepnames class org.signal.libsignal.** { *; }
-keepnames interface org.signal.libsignal.** { *; }
-keepnames class org.rustls.** { *; }
