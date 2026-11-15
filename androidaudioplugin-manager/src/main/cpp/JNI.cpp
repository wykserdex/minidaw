#include <jni.h>
#include <algorithm>
#include <cmath>

#include "PluginPlayer.h"

extern "C"
JNIEXPORT void JNICALL
Java_org_androidaudioplugin_manager_PluginPlayer_loadAudioResourceNative(JNIEnv *env, jobject thiz,
                                                                         jlong player,
                                                                         jbyteArray bytes,
                                                                         jstring filename) {
    if (!player || !bytes || !filename)
        return;
    auto length = env->GetArrayLength(bytes);
    if (length <= 0 || length > 64 * 1024 * 1024)
        return;
    auto data = env->GetByteArrayElements(bytes, nullptr);
    if (!data)
        return;
    auto name = env->GetStringUTFChars(filename, nullptr);
    if (!name) {
        env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
        return;
    }
    try {
        ((aap::PluginPlayer*) player)->setAudioSource((uint8_t*) data, length, name);
    } catch (...) {
        // Loading failures leave the previous source intact.
    }
    env->ReleaseStringUTFChars(filename, name);
    env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
}

extern "C"
JNIEXPORT void JNICALL
Java_org_androidaudioplugin_manager_PluginPlayer_addMidiEventsNative(JNIEnv *env, jobject thiz,
                                                                     jlong player, jbyteArray bytes,
                                                                     jint offset, jint length,
                                                                     jlong timestampInNanoseconds) {
    if (!player || !bytes)
        return;
    auto size = env->GetArrayLength(bytes);
    if (offset < 0 || length < 0 || offset > size || length > size - offset)
        return;
    auto data = env->GetByteArrayElements(bytes, nullptr);
    if (!data)
        return;
    // timeout would not be reliable from non-RT language environment though...
    try {
        ((aap::PluginPlayer*) player)->addMidiEvents((uint8_t*) data + offset, length, timestampInNanoseconds);
    } catch (...) {
        env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
        return;
    }
    env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_org_androidaudioplugin_manager_PluginPlayer_createNewPluginPlayer(JNIEnv *env, jclass clazz,
                                                                       jint sampleRate,
                                                                       jint framesPerCallback,
                                                                       jint channelCount) {
    aap::PluginPlayerConfiguration configuration{sampleRate, framesPerCallback, channelCount};
    return (jlong) (void*) new aap::PluginPlayer(configuration);
}

extern "C"
JNIEXPORT void JNICALL
Java_org_androidaudioplugin_manager_PluginPlayer_deletePluginPlayer(JNIEnv *env, jobject thiz,
                                                                    jlong native) {
    auto player = (aap::PluginPlayer*) native;
    player->pauseProcessing();
    delete player;
}

extern "C"
JNIEXPORT void JNICALL
Java_org_androidaudioplugin_manager_PluginPlayer_startProcessingNative(JNIEnv *env, jobject thiz,
                                                                       jlong player) {
    ((aap::PluginPlayer*) player)->startProcessing();
}

extern "C"
JNIEXPORT void JNICALL
Java_org_androidaudioplugin_manager_PluginPlayer_pauseProcessingNative(JNIEnv *env, jobject thiz,
                                                                       jlong player) {
    ((aap::PluginPlayer*) player)->pauseProcessing();
}

extern "C"
JNIEXPORT void JNICALL
Java_org_androidaudioplugin_manager_PluginPlayer_playPreloadedAudioNative(JNIEnv *env, jobject thiz,
                                                                          jlong player) {
    ((aap::PluginPlayer*) player)->getGraph().playAudioData();
}

extern "C"
JNIEXPORT void JNICALL
Java_org_androidaudioplugin_manager_PluginPlayer_setPluginNative(JNIEnv *env, jobject thiz,
                                                                 jlong player, jlong nativeClient,
                                                                 jint instanceId) {
    auto client = (aap::PluginClient*) nativeClient;
    auto instance = client->getInstanceById(instanceId);
    ((aap::PluginPlayer*) player)->getGraph().setPlugin((aap::RemotePluginInstance*) instance);
}

extern "C"
JNIEXPORT void JNICALL
Java_org_androidaudioplugin_manager_PluginPlayer_addPluginNative(JNIEnv *env, jobject thiz,
                                                                 jlong player, jlong nativeClient,
                                                                 jint instanceId) {
    auto client = (aap::PluginClient*) nativeClient;
    auto instance = client->getInstanceById(instanceId);
    ((aap::PluginPlayer*) player)->getGraph().addPlugin((aap::RemotePluginInstance*) instance);
}

extern "C"
JNIEXPORT void JNICALL
Java_org_androidaudioplugin_manager_PluginPlayer_addPluginToSumBusNative(JNIEnv *env, jobject thiz,
                                                                         jlong player, jlong nativeClient,
                                                                         jint instanceId) {
    auto client = (aap::PluginClient*) nativeClient;
    auto instance = client->getInstanceById(instanceId);
    ((aap::PluginPlayer*) player)->getGraph().addPluginToSumBus(
            (aap::RemotePluginInstance*) instance);
}

extern "C"
JNIEXPORT void JNICALL
Java_org_androidaudioplugin_manager_PluginPlayer_addMasterEffectNative(JNIEnv *, jobject,
                                                                      jlong player, jlong nativeClient,
                                                                      jint instanceId) {
    if (!player || !nativeClient)
        return;
    auto client = (aap::PluginClient*) nativeClient;
    auto instance = client->getInstanceById(instanceId);
    ((aap::PluginPlayer*) player)->getGraph().addMasterEffect((aap::RemotePluginInstance*) instance);
}

extern "C"
JNIEXPORT void JNICALL
Java_org_androidaudioplugin_manager_PluginPlayer_setMasterGainNative(JNIEnv *, jobject,
                                                                    jlong player, jfloat gain) {
    if (player)
        ((aap::PluginPlayer*) player)->getGraph().setMasterGain(gain);
}

extern "C"
JNIEXPORT jint JNICALL
Java_org_androidaudioplugin_manager_PluginPlayer_addAudioTrackNative(JNIEnv *, jobject, jlong player) {
    return player ? ((aap::PluginPlayer*) player)->getGraph().addAudioTrack() : -1;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_org_androidaudioplugin_manager_PluginPlayer_loadAudioTrackNative(JNIEnv* env, jobject,
                                                                     jlong player, jint trackId,
                                                                     jbyteArray bytes, jstring filename) {
    if (!player || !bytes || !filename)
        return JNI_FALSE;
    auto length = env->GetArrayLength(bytes);
    // Reject oversized input before JNI may allocate a copy of the byte array.
    if (length <= 0 || length > 64 * 1024 * 1024)
        return JNI_FALSE;
    auto data = env->GetByteArrayElements(bytes, nullptr);
    if (!data)
        return JNI_FALSE;
    auto name = env->GetStringUTFChars(filename, nullptr);
    if (!name) {
        env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
        return JNI_FALSE;
    }
    bool loaded = false;
    try {
        loaded = ((aap::PluginPlayer*) player)->getGraph().loadAudioTrack(
                trackId, (uint8_t*) data, length, name);
    } catch (...) {
        // Do not allow native decoding/control failures to escape through JNI.
    }
    env->ReleaseStringUTFChars(filename, name);
    env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
    return loaded ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_org_androidaudioplugin_manager_PluginPlayer_setAudioTrackPlacementsNative(JNIEnv* env, jobject,
                                                                              jlong player, jint trackId,
                                                                              jlongArray starts) {
    if (!player || trackId < 0 || !starts || env->ExceptionCheck())
        return JNI_FALSE;
    const auto length = env->GetArrayLength(starts);
    if (env->ExceptionCheck() || length < 0 ||
        static_cast<size_t>(length) > aap::AudioTrackNode::max_placements)
        return JNI_FALSE;
    std::array<jlong, aap::AudioTrackNode::max_placements> values{};
    if (length > 0)
        env->GetLongArrayRegion(starts, 0, length, values.data());
    if (env->ExceptionCheck())
        return JNI_FALSE;
    for (jsize i = 0; i < length; ++i)
        if (values[i] < 0 || static_cast<uint64_t>(values[i]) > aap::AudioTrackNode::max_placement_start)
            return JNI_FALSE;
    try {
        // Only the control caller allocates; the callback reads the published schedule.
        std::vector<uint64_t> placements(values.begin(), values.begin() + length);
        return ((aap::PluginPlayer*) player)->getGraph().setAudioTrackPlacements(trackId, placements)
               ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C"
JNIEXPORT jlong JNICALL
Java_org_androidaudioplugin_manager_PluginPlayer_getAudioTrackDurationFramesNative(JNIEnv *, jobject,
                                                                                  jlong player, jint trackId) {
    if (!player || trackId < 0)
        return 0;
    try {
        return static_cast<jlong>(((aap::PluginPlayer*) player)->getGraph().getAudioTrackDurationFrames(trackId));
    } catch (...) {
        return 0;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_org_androidaudioplugin_manager_PluginPlayer_setAudioTrackGainNative(JNIEnv *, jobject,
                                                                       jlong player, jint trackId,
                                                                       jfloat gain) {
    if (player)
        ((aap::PluginPlayer*) player)->getGraph().setAudioTrackGain(trackId, gain);
}

extern "C"
JNIEXPORT jfloat JNICALL
Java_org_androidaudioplugin_manager_PluginPlayer_getAudioTrackPeakNative(JNIEnv *, jobject,
                                                                       jlong player, jint trackId) {
    return player ? ((aap::PluginPlayer*) player)->getGraph().getAudioTrackPeak(trackId) : 0.0f;
}

extern "C"
JNIEXPORT jint JNICALL
Java_org_androidaudioplugin_manager_PluginPlayer_addBuiltinInstrumentNative(JNIEnv *, jobject,
                                                                           jlong player, jint kind) {
    if (!player || kind < 0 || kind > 2)
        return -1;
    try {
        return ((aap::PluginPlayer*) player)->getGraph().addBuiltinInstrument(kind);
    } catch (...) {
        return -1;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_org_androidaudioplugin_manager_PluginPlayer_builtinNoteNative(JNIEnv *, jobject,
                                                                   jlong player, jint id, jint pitch,
                                                                   jfloat velocity, jboolean down) {
    if (!player || id < 0 || pitch < 0 || pitch > 127 || !std::isfinite(velocity))
        return;
    try {
        ((aap::PluginPlayer*) player)->getGraph().builtinNote(
                id, pitch, std::clamp(velocity, 0.0f, 1.0f), down == JNI_TRUE);
    } catch (...) {
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_org_androidaudioplugin_manager_PluginPlayer_loadBuiltinSampleNative(JNIEnv* env, jobject,
                                                                         jlong player, jint id, jint pitch,
                                                                         jbyteArray bytes, jstring filename) {
    if (!player || id < 0 || pitch < 0 || pitch > 127 || !bytes || !filename)
        return JNI_FALSE;
    auto length = env->GetArrayLength(bytes);
    if (length <= 0 || length > 64 * 1024 * 1024)
        return JNI_FALSE;
    auto data = env->GetByteArrayElements(bytes, nullptr);
    if (!data)
        return JNI_FALSE;
    auto name = env->GetStringUTFChars(filename, nullptr);
    if (!name) {
        env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
        return JNI_FALSE;
    }
    bool loaded = false;
    try {
        loaded = ((aap::PluginPlayer*) player)->getGraph().loadBuiltinSample(
                id, pitch, (uint8_t*) data, length, name);
    } catch (...) {
    }
    env->ReleaseStringUTFChars(filename, name);
    env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
    return loaded ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_org_androidaudioplugin_manager_PluginPlayer_setBuiltinGainNative(JNIEnv *, jobject,
                                                                      jlong player, jint id, jfloat gain) {
    if (!player || id < 0)
        return;
    try {
        ((aap::PluginPlayer*) player)->getGraph().setBuiltinGain(id, gain);
    } catch (...) {
    }
}

extern "C"
JNIEXPORT jfloat JNICALL
Java_org_androidaudioplugin_manager_PluginPlayer_getBuiltinPeakNative(JNIEnv *, jobject,
                                                                      jlong player, jint id) {
    if (!player || id < 0)
        return 0.0f;
    try {
        return ((aap::PluginPlayer*) player)->getGraph().getBuiltinPeak(id);
    } catch (...) {
        return 0.0f;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_org_androidaudioplugin_manager_PluginPlayer_setPluginMidiGroupsNative(JNIEnv *env, jobject thiz,
                                                                          jlong player, jlong nativeClient,
                                                                          jint instanceId, jint groupMask) {
    auto client = (aap::PluginClient*) nativeClient;
    auto instance = client->getInstanceById(instanceId);
    ((aap::PluginPlayer*) player)->getGraph().setPluginMidiGroups(
            (aap::RemotePluginInstance*) instance, (uint32_t) groupMask);
}

extern "C"
JNIEXPORT void JNICALL
Java_org_androidaudioplugin_manager_PluginPlayer_setPluginGainNative(JNIEnv *, jobject,
                                                                     jlong player, jlong nativeClient,
                                                                     jint instanceId, jfloat gain) {
    auto client = (aap::PluginClient*) nativeClient;
    auto instance = client->getInstanceById(instanceId);
    ((aap::PluginPlayer*) player)->getGraph().setPluginGain(
            (aap::RemotePluginInstance*) instance, gain);
}

extern "C"
JNIEXPORT jfloat JNICALL
Java_org_androidaudioplugin_manager_PluginPlayer_getPluginPeakNative(JNIEnv *, jobject,
                                                                     jlong player, jlong nativeClient,
                                                                     jint instanceId) {
    auto client = (aap::PluginClient*) nativeClient;
    auto instance = client->getInstanceById(instanceId);
    return ((aap::PluginPlayer*) player)->getGraph().getPluginPeak(
            (aap::RemotePluginInstance*) instance);
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_org_androidaudioplugin_manager_PluginPlayer_getPeakLevelsNative(JNIEnv *env, jobject,
                                                                     jlong player) {
    auto &graph = ((aap::PluginPlayer*) player)->getGraph();
    float levels[2] = {graph.getPeakLevel(0), graph.getPeakLevel(1)};
    auto result = env->NewFloatArray(2);
    env->SetFloatArrayRegion(result, 0, 2, levels);
    return result;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_org_androidaudioplugin_manager_PluginPlayer_getTransportFramesNative(JNIEnv *, jobject,
                                                                          jlong player) {
    return ((aap::PluginPlayer*) player)->getGraph().getProcessedFrames();
}

extern "C"
JNIEXPORT void JNICALL
Java_org_androidaudioplugin_manager_PluginPlayer_enableAudioRecorderNative(JNIEnv *,
                                                                           jobject thiz,
                                                                           jlong player) {
    ((aap::PluginPlayer*) player)->enableAudioRecorder();
}
extern "C"
JNIEXPORT void JNICALL
Java_org_androidaudioplugin_manager_PluginPlayer_setPresetIndexNative(JNIEnv *env, jobject thiz,
                                                                      jlong player, jint index) {
    ((aap::PluginPlayer*) player)->setPresetIndex(index);
}

extern "C"
JNIEXPORT jint JNICALL
Java_org_androidaudioplugin_manager_PluginPlayer_readMidiOutputNative(JNIEnv *env, jobject thiz,
                                                                      jlong player, jbyteArray bytes) {
    if (!bytes)
        return 0;
    auto length = env->GetArrayLength(bytes);
    if (length <= 0)
        return 0;
    jboolean isCopy{false};
    auto data = (uint8_t*) env->GetByteArrayElements(bytes, &isCopy);
    auto result = ((aap::PluginPlayer*) player)->readMidiOutput(data, length);
    env->ReleaseByteArrayElements(bytes, (jbyte*) data, 0);
    return result;
}
