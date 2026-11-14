#include "AudioGraph.h"
#include <aap/unstable/logging.h>
#include <algorithm>
#include <cmath>
#if ANDROID
#include <android/trace.h>
#endif

aap::SimpleLinearAudioGraph::~SimpleLinearAudioGraph() {
    for (auto node : nodes)
        node->pause(); // and leave destructors do the job
}

void aap::SimpleLinearAudioGraph::processAudio(AudioBuffer *audioData, int32_t numFrames) {
    struct timespec timeSpecBegin{}, timeSpecEnd{};
#if ANDROID
    if (ATrace_isEnabled()) {
        ATrace_beginSection("AAP::SimpleLinearAudioGraph_processAudio");
        clock_gettime(CLOCK_REALTIME, &timeSpecBegin);
    }
#endif

    for (auto node : nodes)
        if (!node->shouldSkip())
            node->processAudio(audioData, numFrames);

    auto view = audioData->audio.getView();
    auto channels = std::min((int32_t) view.getNumChannels(), max_metered_channels);
    for (int32_t ch = 0; ch < channels; ch++) {
        auto data = view.getChannel(ch).data.data;
        float peak = 0.0f;
        for (int32_t i = 0; i < numFrames; i++)
            peak = std::max(peak, std::fabs(data[i]));
        peak_levels[ch].store(peak, std::memory_order_relaxed);
    }

#if ANDROID
    if (ATrace_isEnabled()) {
        clock_gettime(CLOCK_REALTIME, &timeSpecEnd);
        ATrace_setCounter("AAP::SimpleLinearAudioGraph_processAudio",
                          (timeSpecEnd.tv_sec - timeSpecBegin.tv_sec) * 1000000000 + timeSpecEnd.tv_nsec - timeSpecBegin.tv_nsec);
        ATrace_endSection();
    }
#endif

}

void aap::SimpleLinearAudioGraph::setPlugin(aap::RemotePluginInstance *instance) {
    midi_input.setPlugin(instance);
    plugin.setPlugin(instance);
    rebuild_nodes();
}

// The bus head is the primary node, so generators added later only have to mix into its output.
void aap::SimpleLinearAudioGraph::rebuild_nodes() {
    nodes.clear();
    nodes.emplace_back(&input);
    nodes.emplace_back(&audio_data);
    nodes.emplace_back(&midi_input);
    if (!plugin.shouldSkip())
        nodes.emplace_back(&plugin);
    for (auto &node : sum_nodes)
        nodes.emplace_back(node.get());
    for (auto &node : chain_nodes)
        nodes.emplace_back(node.get());
    nodes.emplace_back(&midi_output);
    nodes.emplace_back(&output);
}

// Appends a plugin node right before the MIDI destination and the audio output device nodes,
// so that its output is what actually reaches the device.
void aap::SimpleLinearAudioGraph::addPlugin(aap::RemotePluginInstance *instance) {
    if (!instance)
        return;
    if (plugin.shouldSkip()) {
        setPlugin(instance);
        return;
    }
    auto node = std::make_unique<AudioPluginNode>(this, instance);
    auto position = (int) sum_nodes.size() + (int) chain_nodes.size();
    chain_nodes.push_back(std::move(node));
    rebuild_nodes();
    aap::a_log_f(AAP_LOG_LEVEL_INFO, AAP_MANAGER_LOG_TAG,
                 "plugin node added at %d of %d nodes", position, (int) nodes.size());
}

void aap::SimpleLinearAudioGraph::addPluginToSumBus(aap::RemotePluginInstance *instance) {
    if (!instance)
        return;
    if (plugin.shouldSkip()) {
        setPlugin(instance);
        return;
    }
    auto node = std::make_unique<AudioPluginNode>(this, instance);
    node->setSumIntoBus(true);
    auto position = (int) sum_nodes.size() + (int) chain_nodes.size();
    sum_nodes.push_back(std::move(node));
    rebuild_nodes();
    aap::a_log_f(AAP_LOG_LEVEL_INFO, AAP_MANAGER_LOG_TAG,
                 "sum bus node added at %d of %d nodes", position, (int) nodes.size());
}

void aap::SimpleLinearAudioGraph::setPluginMidiGroups(aap::RemotePluginInstance *instance,
                                                     uint32_t group_mask) {
    if (!instance)
        return;
    if (plugin.getPlugin() == instance) {
        plugin.setMidiGroupMask(group_mask);
        return;
    }
    for (auto &node : sum_nodes)
        if (node->getPlugin() == instance) {
            node->setMidiGroupMask(group_mask);
            return;
        }
    for (auto &node : chain_nodes)
        if (node->getPlugin() == instance) {
            node->setMidiGroupMask(group_mask);
            return;
        }
    aap::a_log_f(AAP_LOG_LEVEL_WARN, AAP_MANAGER_LOG_TAG,
                 "no plugin node for instance %d; MIDI group mask %04X not applied",
                 instance->getInstanceId(), group_mask);
}

void
aap::SimpleLinearAudioGraph::setAudioSource(uint8_t *data, int dataLength, const char *filename) {
    audio_data.setAudioSource(data, dataLength, filename);
}

void aap::SimpleLinearAudioGraph::addMidiEvent(uint8_t *data, int32_t length, int64_t timestampInNanoseconds) {
    midi_input.addMidiEvent(data, length, timestampInNanoseconds);
}

void aap::SimpleLinearAudioGraph::playAudioData() {
    audio_data.setPlaying(true);
}

void aap::SimpleLinearAudioGraph::startProcessing() {
    if (isProcessing())
        return;
    is_processing = true;
    for (auto node : nodes)
        node->start();
}

void aap::SimpleLinearAudioGraph::pauseProcessing() {
    if (!isProcessing())
        return;
    is_processing = false;
    for (auto node : nodes)
        node->pause();
    for (auto &level : peak_levels)
        level.store(0.0f, std::memory_order_relaxed); // a stopped transport should not freeze the last block
}

aap::SimpleLinearAudioGraph::SimpleLinearAudioGraph(int32_t sampleRate, uint32_t framesPerCallback, int32_t channelsInAudioBus) :
        AudioGraph(sampleRate, framesPerCallback, channelsInAudioBus),
        input(this, AudioDeviceManager::getInstance()->ensureDefaultInputOpened(sampleRate,
                                                                                framesPerCallback,
                                                                                channelsInAudioBus)),
        output(this, AudioDeviceManager::getInstance()->ensureDefaultOutputOpened(sampleRate,
                                                                                  framesPerCallback,
                                                                                  channelsInAudioBus)),
        plugin(this, nullptr),
        audio_data(this),
        midi_input(this, nullptr, sampleRate, framesPerCallback, CMIDI2_PROTOCOL_TYPE_MIDI2, AAP_PLUGIN_PLAYER_DEFAULT_MIDI_RING_BUFFER_SIZE),
        midi_output(this, AAP_PLUGIN_PLAYER_DEFAULT_MIDI_RING_BUFFER_SIZE) {
    // The audio thread iterates `nodes` while the UI thread rebuilds it, so it is reserved once and
    // must never reallocate: 6 fixed nodes leave room for 26 plugin nodes.
    nodes.reserve(32);
    rebuild_nodes();

    output.getDevice()->setAudioCallback(audio_callback, this);
}

void aap::SimpleLinearAudioGraph::enableAudioRecorder() {
    input.setPermissionGranted();
}

void aap::SimpleLinearAudioGraph::setPresetIndex(int index) {
    plugin.setPresetIndex(index);
}
