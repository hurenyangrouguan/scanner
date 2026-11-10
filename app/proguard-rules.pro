# 保留 OpenCV
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# 保留 CameraX 相关（一般不需要，但 release 混淆时保险）
-keep class androidx.camera.** { *; }
