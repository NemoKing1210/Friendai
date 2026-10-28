package dev.friendai.client.audio;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class NativeResourcesTest {
    @Test
    void nativesForSupportedPlatformsAreOnTheClasspath() {
        for (String path : List.of(
                "sherpa-onnx/native/win-x64/onnxruntime.dll",
                "sherpa-onnx/native/win-x64/sherpa-onnx-jni.dll",
                "sherpa-onnx/native/linux-x64/libonnxruntime.so",
                "sherpa-onnx/native/linux-x64/libsherpa-onnx-jni.so",
                "sherpa-onnx/native/osx-x64/libonnxruntime.dylib",
                "sherpa-onnx/native/osx-x64/libsherpa-onnx-jni.dylib",
                "sherpa-onnx/native/osx-aarch64/libonnxruntime.dylib",
                "sherpa-onnx/native/osx-aarch64/libsherpa-onnx-jni.dylib")) {
            assertNotNull(getClass().getClassLoader().getResource(path), path);
        }
    }
}
