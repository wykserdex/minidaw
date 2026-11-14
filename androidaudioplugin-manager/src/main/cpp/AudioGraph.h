#ifndef AAP_CORE_AUDIOGRAPH_H
#define AAP_CORE_AUDIOGRAPH_H

#include <cstdint>
#include <cassert>
#include <array>
#include <atomic>
#include <memory>
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
        // Generators that share one summing bus, then the serial inserts. Reserved once in the
        // constructor: the audio thread iterates `nodes` while the UI thread rebuilds it.
        std::vector<std::unique_ptr<AudioPluginNode>> sum_nodes{};
        std::vector<std::unique_ptr<AudioPluginNode>> chain_nodes{};
        bool is_processing{false};
        // Peak of the most recent callback per channel: one relaxed store per block on the audio
        // thread, read from the UI thread. It holds the last block, not an average.
        std::array<std::atomic<float>, max_metered_channels> peak_levels{};

        static void audio_callback(void* callbackContext, AudioBuffer* audioData, int32_t numFrames) {
            ((SimpleLinearAudioGraph*) callbackContext)->processAudio(audioData, numFrames);
        }

        void rebuild_nodes();

    public:
        SimpleLinearAudioGraph(int32_t sampleRate, uint32_t framesPerCallback, int32_t channelsInAudioBus);
        virtual ~SimpleLinearAudioGraph();

        void setPlugin(RemotePluginInstance* instance);

        // Appends another plugin node to the chain, processed after the existing ones.
        void addPlugin(RemotePluginInstance* instance);

        // Adds a generator to the summing bus: it is fed silence and mixed into the first plugin's
        // output instead of replacing it. The bus runs before the serial chain.
        void addPluginToSumBus(RemotePluginInstance* instance);

        // Routes only the UMP groups set in the mask to the node that hosts this instance.
        // 0xFFFF broadcasts to every group (the original behaviour) and 0 mutes its MIDI input.
        void setPluginMidiGroups(RemotePluginInstance* instance, uint32_t group_mask);

        void setAudioSource(uint8_t *data, int dataLength, const char *filename);

        void processAudio(AudioBuffer *audioData, int32_t numFrames) override;

        void addMidiEvent(uint8_t *data, int32_t dataLength, int64_t timestampInNanoseconds);

        void playAudioData();

        bool isProcessing() { return is_processing; }

        // Peak of the last processed block for one channel; 0 for channels the bus does not have.
        float getPeakLevel(int32_t channel) {
            return channel < 0 || channel >= max_metered_channels
                   ? 0.0f : peak_levels[channel].load(std::memory_order_relaxed);
        }

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
