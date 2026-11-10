package com.example.myapplicationfirstapp.data;

import android.content.res.AssetManager;

import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;

/**
 * 用真正的 null AssetManager 创建识别器。
 *
 * 为什么需要这个 Java 文件：sherpa-onnx 的 Kotlin 构造函数把 AssetManager 声明成非空，
 * 但我们把模型下到了 filesDir（绝对路径）。原生层看到「绝对路径 + 非空 assetManager」
 * 会直接打印 "Please set assetManager to null" 然后 exit(-1)——整个进程静默消失，
 * 连 Java 异常都没有，排查起来非常费劲。
 *
 * Java 不检查 Kotlin 的空标注，所以在这里绕过去，把 null 交给 native。
 */
public final class AsrFactory {

    private AsrFactory() {
    }

    public static OfflineRecognizer create(OfflineRecognizerConfig config) {
        return new OfflineRecognizer((AssetManager) null, config);
    }
}
