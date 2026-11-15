#ifndef AAP_CORE_AUDIOGRAPH_H
#define AAP_CORE_AUDIOGRAPH_H

#include <cstdint>
#include <cassert>
#include <array>
#include <atomic>
#include <memory>
#include <mutex>
#include <vector>

#include "LocalDefinitions.h"
#include "AudioDeviceManager.h"
#include "AudioGraphNode.h"

namespace aap {
    AAP_OPEN_CLASS class AudioGraph {
        int32_t sample_rate;
        int32_t frames_per_callback;
        int32_t num_channels;

    public:
        explicit AudioGraph(int32_t sampleRate, int32_t framesPerCallback, int32_t channelsInAudioBus) :
                sample_rate(sampleRate),
                frames_per_callback(framesPerCallback),
                num_channels(channelsInAudioBus) {
        }

        virtual void processAudio(AudioBuffer* audioData, int32_t numFrames) = 0;

        int32_t getSampleRate() { return sample_rate; }

        int32_t getFramesPerCallback() { return frames_per_callback; }

        int32_t getChannelsInAudioBus() { return num_channels; }
    };

    class SimpleLinearAudioGraph : public AudioGraph {
        static constexpr int32_t max_metered_channels = 8;

        AudioDeviceInputNode input;
        AudioDeviceOutputNode output;
        AudioPluginNode plugin;
        AudioDataSourceNode audio_data;
        MidiSourceNode midi_input;
        MidiDestinationNode midi_output;
        std::vector<AudioGraphNode*> nodes{};
        std::vector<std::unique_ptr<AudioPluginNode>> sum_nodes{};
        std::vector<std::unique_ptr<AudioPluginNode>> chain_nodes{};
        std::vector<std::unique_ptr<AudioTrackNode>> audio_tracks{};
        std::vector<std::unique_ptr<BuiltinInstrumentNode>> builtin_instruments{};
        static constexpr uint64_t max_decoded_audio_bytes = 64ULL * 1024 * 1024;
        uint64_t decoded_audio_bytes{0};
        std::atomic<float> master_gain{1.0f};
        std::mutex control_mutex{};
        std::atomic<bool> control_gate{true};
        std::atomic<int32_t> callbacks_in_flight{0};
        std::atomic<bool> is_processing{false};
        std::array<std::atomic<float>, max_metered_channels> peak_levels{};
        std::atomic<int64_t> frames_processed{0};

        class ControlScope {
            SimpleLinearAudioGraph& graph;
            std::unique_lock<std::mutex> lock;
        public:
            explicit ControlScope(SimpleLinearAudioGraph& graph);
            ~ControlScope();
        };

        static void audio_callback(void* callbackContext, AudioBuffer* audioData, int32_t numFrames) {
            ((SimpleLinearAudioGraph*) callbackContext)->processAudio(audioData, numFrames);
        }

        void rebuild_nodes();
        void set_plugin_locked(RemotePluginInstance* instance);
        AudioPluginNode* find_plugin_node(RemotePluginInstance* instance);
        AudioTrackNode* find_audio_track(int trackId);
        BuiltinInstrumentNode* find_builtin_instrument(int id);
        void silence(AudioBuffer* audioData);
        void apply_master_gain_and_meter(AudioBuffer* audioData, int32_t numFrames);
        void pause_nodes();

    public:
        SimpleLinearAudioGraph(int32_t sampleRate, uint32_t framesPerCallback, int32_t channelsInAudioBus);
        virtual ~SimpleLinearAudioGraph();

        void setPlugin(RemotePluginInstance* instance);

        // Appends another plugin node to the chain, processed after the existing ones.
        void addPlugin(RemotePluginInstance* instance);

        // Always adds a silence-fed generator to the bus, before the serial chain.
        void addPluginToSumBus(RemotePluginInstance* instance);

        // Always appends a serial effect, even without a primary plugin or generator.
        void addMasterEffect(RemotePluginInstance* instance);
        void setMasterGain(float gain);

        int addAudioTrack();
        bool loadAudioTrack(int trackId, uint8_t* bytes, int dataLength, const char* filename);
        bool setAudioTrackPlacements(int trackId, const std::vector<uint64_t>& starts);
        uint64_t getAudioTrackDurationFrames(int trackId);
        void setAudioTrackGain(int trackId, float gain);
        float getAudioTrackPeak(int trackId);

        int addBuiltinInstrument(int kind);
        void builtinNote(int id, int pitch, float velocity, bool down);
        bool loadBuiltinSample(int id, int pitch, uint8_t* bytes, int dataLength, const char* filename);
        void setBuiltinGain(int id, float gain);
        float getBuiltinPeak(int id);

        // Routes only the UMP groups set in the mask to the node that hosts this instance.
        // 0xFFFF broadcasts to every group (the original behaviour) and 0 mutes its MIDI input.
        void setPluginMidiGroups(RemotePluginInstance* instance, uint32_t group_mask);

        void setPluginGain(RemotePluginInstance* instance, float gain);
        float getPluginPeak(RemotePluginInstance* instance);

        void setAudioSource(uint8_t *data, int dataLength, const char *filename);

        void processAudio(AudioBuffer *audioData, int32_t numFrames) override;

        void addMidiEvent(uint8_t *data, int32_t dataLength, int64_t timestampInNanoseconds);

        void playAudioData();

        bool isProcessing() { return is_processing.load(std::memory_order_relaxed); }

        // Peak of the last processed block for one channel; 0 for channels the bus does not have.
        float getPeakLevel(int32_t channel) {
            return channel < 0 || channel >= max_metered_channels
                   ? 0.0f : peak_levels[channel].load(std::memory_order_relaxed);
        }

        int64_t getProcessedFrames() { return frames_processed.load(std::memory_order_relaxed); }

        void startProcessing();

        void pauseProcessing();

        void enableAudioRecorder();

        void setPresetIndex(int index);

        int32_t readMidiOutput(uint8_t* dst, int32_t dstCapacity) {
            return midi_output.readMidiEvents(dst, dstCapacity);
        }
    };

    // Not planned to implement so far.
    class BasicAudioGraph : public AudioGraph {
    public:
        BasicAudioGraph(int32_t sampleRate, int32_t framesPerCallback, int32_t channelsInAudioBus) :
                AudioGraph(sampleRate, framesPerCallback, channelsInAudioBus) {
        }

        void attachNode(AudioGraphNode* sourceNode, int32_t sourceOutputBusIndex, AudioGraphNode* destinationNode, int32_t destinationInputBusIndex) {}
        void detachNode(AudioGraphNode* sourceNode, int32_t sourceOutputBusIndex) {}
    };
}

#endif //AAP_CORE_AUDIOGRAPH_H
