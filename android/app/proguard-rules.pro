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
