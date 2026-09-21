# App-owned dynamic entry points and verified gaps in dependency consumer rules.
# Retrofit, Gson, kotlinx.serialization, Room, WorkManager and OkHttp contribute
# consumer rules. Do not blanket-keep the app or suppress all missing classes.

# The optimized Java-WebSocket 1.5.3 server loop failed ART verification on
# Android 16: WebSocketServer.run() has an instruction outside a catch-all
# while a monitor is held. ExtensionBridgeServer loads this superclass at
# startup. Preserve this class's bytecode structure; names may still change.
# Recheck with tools/release-smoke before removing this targeted workaround.
-keep,allowobfuscation class org.java_websocket.server.WebSocketServer { *; }

# vision_engine.cpp uses exported JNI names and constructs this result by its
# exact class name and constructor descriptor (IZFIIIIIFFI)V. It does not access
# the Kotlin result fields by name; those can still be optimized and renamed.
-keepclasseswithmembernames,includedescriptorclasses class com.autonion.automationcompanion.core.vision.VisionNativeBridge {
    native <methods>;
}
-keep class com.autonion.automationcompanion.core.vision.MatchResultNative {
    public <init>(int, boolean, float, int, int, int, int, int, float, float, int);
}

# firebase-components 16.1.0 keeps ComponentRegistrar classes but not their
# members. ML Kit discovers the three registrars named in the manifest via
# Class.forName(...).getDeclaredConstructor().newInstance(); R8 shrinks the
# no-arg constructors, discovery fails silently, and MlKitContext.get(zzo)
# returns null -> NPE in OcrEngine's TextRecognition.getClient().
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    <init>();
}

# ONNX Runtime 1.22.0 has native-to-Java lookups and no bundled consumer rules.
# Required by https://onnxruntime.ai/docs/build/android.html
-keep class ai.onnxruntime.** { *; }

# MediaPipe 0.10.35 calls this Java callback from native code by method name.
# Native methods themselves are covered by the Android default JNI rules.
-keep interface com.google.mediapipe.tasks.genai.llminference.LlmTaskRunnerDelegate {
    void onAsyncResponse(byte[]);
}
-keepclassmembers class * implements com.google.mediapipe.tasks.genai.llminference.LlmTaskRunnerDelegate {
    public void onAsyncResponse(byte[]);
}

# protobuf-javalite 4.26.1 does not bundle consumer rules. Its MessageSchema
# resolves GeneratedMessageLite fields using names in generated schema strings
# (including MediaPipe LLM options). Retain those fields and their exact names.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# Omni chatbot uses Jackson through LangChain4j 0.36.2 and openai4j 0.23.0.
# These versions have no consumer rules. JSON DTO property names, constructors
# and builder methods are discovered reflectively. Limit keeps to the Ollama
# integration and the OpenAI chat/shared DTOs used by this app.
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keepattributes Signature,InnerClasses,EnclosingMethod
-keep class dev.langchain4j.model.ollama.** { *; }
-keep class dev.ai4j.openai4j.chat.** { *; }
-keep class dev.ai4j.openai4j.shared.** { *; }
-keepclassmembers class com.fasterxml.jackson.databind.PropertyNamingStrategies$SnakeCaseStrategy {
    public <init>();
}
-keep,allowobfuscation class com.fasterxml.jackson.core.type.TypeReference
-keep,allowobfuscation class * extends com.fasterxml.jackson.core.type.TypeReference
