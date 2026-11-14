#include "AudioGraph.h"
#include <aap/unstable/logging.h>
#include <algorithm>
#include <cmath>
#include <limits>
#include <thread>
#if ANDROID
#include <android/trace.h>
#endif

aap::SimpleLinearAudioGraph::ControlScope::ControlScope(SimpleLinearAudioGraph& owner) :
        graph(owner), lock(owner.control_mutex) {
    // Sequential consistency pairs the callback's increment/recheck with this drain.
    graph.control_gate.store(true);
    while (graph.callbacks_in_flight.load() != 0)
        std::this_thread::yield();
}

aap::SimpleLinearAudioGraph::ControlScope::~ControlScope() {
    graph.control_gate.store(!graph.isProcessing());
}

aap::SimpleLinearAudioGraph::~SimpleLinearAudioGraph() {
    ControlScope control{*this};
    is_processing.store(false, std::memory_order_relaxed);
    pause_nodes();
    output.getDevice()->setAudioCallback(nullptr, nullptr);
}

void aap::SimpleLinearAudioGraph::silence(AudioBuffer* audioData) {
    audioData->audio.clear();
    memset(audioData->midi_in, 0, audioData->midi_capacity);
    memset(audioData->midi_out, 0, audioData->midi_capacity);
    for (auto& level : peak_levels)
        level.store(0.0f, std::memory_order_relaxed);
}

void aap::SimpleLinearAudioGraph::processAudio(AudioBuffer *audioData, int32_t numFrames) {
    if (control_gate.load()) {
        silence(audioData);
        return;
    }
    callbacks_in_flight.fetch_add(1);
    struct CallbackScope {
        std::atomic<int32_t>& count;
        ~CallbackScope() { count.fetch_sub(1); }
    } callback{callbacks_in_flight};
    if (control_gate.load()) {
        silence(audioData);
        return;
    }

    struct timespec timeSpecBegin{}, timeSpecEnd{};
#if ANDROID
    if (ATrace_isEnabled()) {
        ATrace_beginSection("AAP::SimpleLinearAudioGraph_processAudio");
        clock_gettime(CLOCK_REALTIME, &timeSpecBegin);
    }
#endif

    // Input nodes may be absent, or sources may produce only part of the last block.
    audioData->audio.clear();
    for (auto node : nodes) {
        if (node == &output)
            apply_master_gain_and_meter(audioData, numFrames);
        if (!node->shouldSkip())
            node->processAudio(audioData, numFrames);
    }

    frames_processed.fetch_add(numFrames, std::memory_order_relaxed);

#if ANDROID
    if (ATrace_isEnabled()) {
        clock_gettime(CLOCK_REALTIME, &timeSpecEnd);
        ATrace_setCounter("AAP::SimpleLinearAudioGraph_processAudio",
                          (timeSpecEnd.tv_sec - timeSpecBegin.tv_sec) * 1000000000 + timeSpecEnd.tv_nsec - timeSpecBegin.tv_nsec);
        ATrace_endSection();
    }
#endif

}

void aap::SimpleLinearAudioGraph::apply_master_gain_and_meter(AudioBuffer* audioData, int32_t numFrames) {
    const auto gain = master_gain.load(std::memory_order_relaxed);
    auto view = audioData->audio.getView();
    auto frames = std::min(static_cast<uint32_t>(std::max(0, numFrames)), view.getNumFrames());
    for (uint32_t ch = 0; ch < view.getNumChannels(); ++ch) {
        auto data = view.getChannel(ch).data.data;
        float peak = 0.0f;
        for (uint32_t frame = 0; frame < frames; ++frame) {
            data[frame] = gain == 0.0f ? 0.0f : data[frame] * gain;
            peak = std::max(peak, std::fabs(data[frame]));
        }
        if (ch < max_metered_channels)
            peak_levels[ch].store(peak, std::memory_order_relaxed);
    }
    for (uint32_t ch = view.getNumChannels(); ch < max_metered_channels; ++ch)
        peak_levels[ch].store(0.0f, std::memory_order_relaxed);
}

void aap::SimpleLinearAudioGraph::setPlugin(aap::RemotePluginInstance *instance) {
    ControlScope control{*this};
    set_plugin_locked(instance);
}

void aap::SimpleLinearAudioGraph::set_plugin_locked(RemotePluginInstance* instance) {
    plugin.setPlugin(instance);
    midi_input.setPlugin(instance);
    if (isProcessing())
        plugin.start();
    rebuild_nodes();
}

void aap::SimpleLinearAudioGraph::rebuild_nodes() {
    nodes.clear();
    nodes.emplace_back(&input);
    nodes.emplace_back(&audio_data);
    nodes.emplace_back(&midi_input);
    if (!plugin.shouldSkip())
        nodes.emplace_back(&plugin);
    for (auto &node : audio_tracks)
        nodes.emplace_back(node.get());
    for (auto &node : sum_nodes)
        nodes.emplace_back(node.get());
    for (auto &node : chain_nodes)
        nodes.emplace_back(node.get());
    nodes.emplace_back(&midi_output);
    nodes.emplace_back(&output);
}

void aap::SimpleLinearAudioGraph::addPlugin(aap::RemotePluginInstance *instance) {
    if (!instance)
        return;
    ControlScope control{*this};
    if (plugin.shouldSkip()) {
        set_plugin_locked(instance);
        return;
    }
    auto node = std::make_unique<AudioPluginNode>(this, instance);
    if (isProcessing())
        node->start();
    auto position = (int) sum_nodes.size() + (int) chain_nodes.size();
    chain_nodes.push_back(std::move(node));
    rebuild_nodes();
    aap::a_log_f(AAP_LOG_LEVEL_INFO, AAP_MANAGER_LOG_TAG,
                 "plugin node added at %d of %d nodes", position, (int) nodes.size());
}

void aap::SimpleLinearAudioGraph::addPluginToSumBus(aap::RemotePluginInstance *instance) {
    if (!instance)
        return;
    ControlScope control{*this};
    nodes.reserve(nodes.size() + 1);
    sum_nodes.reserve(sum_nodes.size() + 1);
    auto node = std::make_unique<AudioPluginNode>(this, instance);
    node->setSumIntoBus(true);
    if (isProcessing())
        node->start();
    auto position = (int) sum_nodes.size() + (int) chain_nodes.size();
    sum_nodes.push_back(std::move(node));
    rebuild_nodes();
    aap::a_log_f(AAP_LOG_LEVEL_INFO, AAP_MANAGER_LOG_TAG,
                 "sum bus node added at %d of %d nodes", position, (int) nodes.size());
}

void aap::SimpleLinearAudioGraph::addMasterEffect(RemotePluginInstance* instance) {
    if (!instance)
        return;
    ControlScope control{*this};
    nodes.reserve(nodes.size() + 1);
    chain_nodes.reserve(chain_nodes.size() + 1);
    auto node = std::make_unique<AudioPluginNode>(this, instance);
    if (isProcessing())
        node->start();
    chain_nodes.push_back(std::move(node));
    rebuild_nodes();
}

void aap::SimpleLinearAudioGraph::setMasterGain(float gain) {
    master_gain.store(std::isfinite(gain) ? std::max(0.0f, gain) : 0.0f,
                      std::memory_order_relaxed);
}

int aap::SimpleLinearAudioGraph::addAudioTrack() {
    ControlScope control{*this};
    if (audio_tracks.size() >= static_cast<size_t>(std::numeric_limits<int>::max()))
        return -1;
    // Reserve before publication, so allocation failure cannot leave a partial node list.
    try {
        auto node = std::make_unique<AudioTrackNode>(this);
        nodes.reserve(nodes.size() + 1);
        audio_tracks.reserve(audio_tracks.size() + 1);
        if (isProcessing())
            node->start();
        auto id = static_cast<int>(audio_tracks.size());
        audio_tracks.push_back(std::move(node));
        rebuild_nodes();
        return id;
    } catch (...) {
        return -1;
    }
}

aap::AudioTrackNode* aap::SimpleLinearAudioGraph::find_audio_track(int trackId) {
    return trackId >= 0 && static_cast<size_t>(trackId) < audio_tracks.size() ?
           audio_tracks[trackId].get() : nullptr;
}

bool aap::SimpleLinearAudioGraph::loadAudioTrack(int trackId, uint8_t* bytes, int dataLength,
                                               const char* filename) {
    ControlScope control{*this};
    auto track = find_audio_track(trackId);
    if (!track)
        return false;
    const auto previousBytes = track->getDecodedBytes();
    // Count the old clip as well until a replacement has decoded successfully.
    if (!track->load(bytes, dataLength, filename, max_audio_track_bytes - decoded_audio_track_bytes))
        return false;
    decoded_audio_track_bytes = decoded_audio_track_bytes - previousBytes + track->getDecodedBytes();
    return true;
}

void aap::SimpleLinearAudioGraph::setAudioTrackGain(int trackId, float gain) {
    const std::lock_guard<std::mutex> lock{control_mutex};
    if (auto track = find_audio_track(trackId))
        track->setGain(gain);
}

float aap::SimpleLinearAudioGraph::getAudioTrackPeak(int trackId) {
    const std::lock_guard<std::mutex> lock{control_mutex};
    auto track = find_audio_track(trackId);
    return track ? track->getPeak() : 0.0f;
}

aap::AudioPluginNode* aap::SimpleLinearAudioGraph::find_plugin_node(RemotePluginInstance* instance) {
    if (!instance)
        return nullptr;
    if (plugin.getPlugin() == instance)
        return &plugin;
    for (auto& node : sum_nodes)
        if (node->getPlugin() == instance)
            return node.get();
    for (auto& node : chain_nodes)
        if (node->getPlugin() == instance)
            return node.get();
    return nullptr;
}

void aap::SimpleLinearAudioGraph::setPluginMidiGroups(aap::RemotePluginInstance *instance,
                                                     uint32_t group_mask) {
    if (!instance)
        return;
    const std::lock_guard<std::mutex> lock{control_mutex};
    if (auto node = find_plugin_node(instance)) {
        node->setMidiGroupMask(group_mask);
        return;
    }
    aap::a_log_f(AAP_LOG_LEVEL_WARN, AAP_MANAGER_LOG_TAG,
                 "no plugin node for instance %d; MIDI group mask %04X not applied",
                 instance->getInstanceId(), group_mask);
}

void aap::SimpleLinearAudioGraph::setPluginGain(RemotePluginInstance* instance, float gain) {
    const std::lock_guard<std::mutex> lock{control_mutex};
    if (auto node = find_plugin_node(instance))
        node->setGain(gain);
}

float aap::SimpleLinearAudioGraph::getPluginPeak(RemotePluginInstance* instance) {
    const std::lock_guard<std::mutex> lock{control_mutex};
    auto node = find_plugin_node(instance);
    return node ? node->getPeak() : 0.0f;
}

void
aap::SimpleLinearAudioGraph::setAudioSource(uint8_t *data, int dataLength, const char *filename) {
    ControlScope control{*this};
    audio_data.setAudioSource(data, dataLength, filename);
}

void aap::SimpleLinearAudioGraph::addMidiEvent(uint8_t *data, int32_t length, int64_t timestampInNanoseconds) {
    const std::lock_guard<std::mutex> lock{control_mutex};
    midi_input.addMidiEvent(data, length, timestampInNanoseconds);
}

void aap::SimpleLinearAudioGraph::playAudioData() {
    ControlScope control{*this};
    audio_data.setPlaying(true);
}

void aap::SimpleLinearAudioGraph::startProcessing() {
    ControlScope control{*this};
    if (isProcessing())
        return;
    frames_processed.store(0, std::memory_order_relaxed);
    try {
        for (auto node : nodes)
            node->start();
    } catch (...) {
        pause_nodes();
        throw;
    }
    is_processing.store(true, std::memory_order_relaxed);
}

void aap::SimpleLinearAudioGraph::pause_nodes() {
    // Stop the callback source while the gate remains closed, before pausing plugins.
    output.pause();
    for (auto node : nodes)
        if (node != &output)
            node->pause();
    for (auto& level : peak_levels)
        level.store(0.0f, std::memory_order_relaxed);
}

void aap::SimpleLinearAudioGraph::pauseProcessing() {
    ControlScope control{*this};
    is_processing.store(false, std::memory_order_relaxed);
    pause_nodes();
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
    for (auto& level : peak_levels)
        level.store(0.0f, std::memory_order_relaxed);
    rebuild_nodes();

    output.getDevice()->setAudioCallback(audio_callback, this);
}

void aap::SimpleLinearAudioGraph::enableAudioRecorder() {
    ControlScope control{*this};
    input.setPermissionGranted();
}

void aap::SimpleLinearAudioGraph::setPresetIndex(int index) {
    const std::lock_guard<std::mutex> lock{control_mutex};
    plugin.setPresetIndex(index);
}
