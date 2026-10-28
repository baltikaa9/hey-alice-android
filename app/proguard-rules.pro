# ONNX Runtime calls into these classes from native code.
-keep class ai.onnxruntime.** { *; }

# Shizuku creates the user service by reflection in a separate process.
-keep class io.github.serifdrms.heyclaude.shizuku.AssistKeyService {
    public <init>(...);
}
