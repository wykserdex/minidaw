#ifndef AAP_CORE_AUDIOGRAPHNODE_H
#define AAP_CORE_AUDIOGRAPHNODE_H

#include <atomic>
#include <mutex>
#include "AudioDevice.h"
#include "AAPMidiEventTranslator.h"
#include <aap/core/host/plugin-instance.h>
#include <aap/unstable/utility.h>
#ifndef CMIDI2_H_INCLUDED // it is only a workaround to avoid reference resolution failure at aap-juce-* repos.
#include <cmidi2.h>
#endif

namespace aap {
    class AudioGraph;

    class AudioGraphNode {

    protected:
        AudioGraph* graph;

        explicit AudioGraphNode(AudioGraph* ownerGraph) : graph(ownerGraph) {}

    public:
        virtual ~AudioGraphNode() = default;
        virtual bool shouldSkip() { return false; }
        virtual void start() = 0;
        virtual void pause() = 0;
        virtual void processAudio(AudioBuffer* audioData, int32_t numFrames) = 0;
    };

    /**
     * AudioDeviceInputNode outputs the audio input from the device callback at processing.
     *
     * Single producer, multiple consumer: more than one node could retrieve the same input buffer.
     *
     * It internally uses ring buffer for audio inputs. (TODO)
     *
     * In case of failed audio processing, it may resend the same output chunk.
     * It does not check if the input was already consumed or not.
     *
     */
    class AudioDeviceInputNode : public AudioGraphNode {
        AudioDeviceIn* input;
        int32_t consumer_position{0};
        bool permission_granted{false};

    public:
        AudioDeviceInputNode(AudioGraph* ownerGraph, AudioDeviceIn* input) :
                AudioGraphNode(ownerGraph),
                input(input) {
        }
        ~AudioDeviceInputNode() override;

        AudioDeviceIn* getDevice() { return input; }

        bool shouldSkip() override;

        void start() override;
        void pause() override;
        void processAudio(AudioBuffer* audioData, int32_t numFrames) override;

        void setPermissionGranted();
    };

    /**
     * AudioDeviceOutputNode outputs its input directly to the device at processing.
     *
     * Single producer (app), single consumer (audio device callback).
     *
     * On a live audio processor, the Oboe audio device callback would kick the overall
     * AudioGraph processing, and this node is typically laid out at the end of one of the chains.
     *
     * AudioGraph itself does not handle output copying - it might just store the processing
     * results on an AudioDestinationNode and let the client app retrieve the buffer.
     */
    class AudioDeviceOutputNode : public AudioGraphNode {
        AudioDeviceOut* output;
        int32_t consumer_position{0};

    public:
        AudioDeviceOutputNode(AudioGraph* ownerGraph, AudioDeviceOut* output) :
                AudioGraphNode(ownerGraph),
                output(output) {
        }
        ~AudioDeviceOutputNode() override;

        AudioDeviceOut* getDevice() { return output; }

        void start() override;
        void pause() override;
        void processAudio(AudioBuffer* audioData, int32_t numFrames) override;
    };

    class AudioPluginNode : public AudioGraphNode {
        RemotePluginInstance* plugin{nullptr};
        std::mutex control_mutex{};
        std::atomic<bool> reconfiguring{false};
        std::atomic<int32_t> processing{0};
        std::atomic<float> audio_gain{1.0f};
        std::atomic<float> last_block_peak{0.0f};
        // UMP groups this node is fed, one bit per group; 0xFFFF keeps the original broadcast.
        std::atomic<uint32_t> midi_group_mask{0xFFFF};
        // Set before publication: generators receive silence and add their output to the bus.
        bool sum_into_bus{false};
        void onBusesChanged(uint32_t flags);

        class ControlScope {
            AudioPluginNode& node;
            std::unique_lock<std::mutex> lock;
        public:
            explicit ControlScope(AudioPluginNode& node);
            ~ControlScope();
        };

    public:
        AudioPluginNode(AudioGraph* ownerGraph, RemotePluginInstance* instance) :
                AudioGraphNode(ownerGraph) {
            setPlugin(instance);
        }
        ~AudioPluginNode() override;

        void setPlugin(RemotePluginInstance* instance);

        RemotePluginInstance* getPlugin() const { return plugin; }

        void setMidiGroupMask(uint32_t mask) { midi_group_mask.store(mask, std::memory_order_relaxed); }

        void setGain(float gain);
        float getPeak() const { return last_block_peak.load(std::memory_order_relaxed); }

        void setSumIntoBus(bool value) { sum_into_bus = value; }

        void start() override;
        void pause() override;
        bool shouldSkip() override;
        void processAudio(AudioBuffer* audioData, int32_t numFrames) override;

        // FIXME: this should be generalized to invoke arbitrary extension functions.
        void setPresetIndex(int index);
    };

    class AudioDataSourceNode : public AudioGraphNode {
        bool active{false};
        bool playing{false};
        // Data and playback state are changed only under the graph's ControlScope.
        std::unique_ptr<AudioBuffer> audio_data{nullptr};

        int32_t current_frame_offset{0};

    public:
        explicit AudioDataSourceNode(AudioGraph* ownerGraph);
        ~AudioDataSourceNode() override;

        void start() override;
        void pause() override;
        bool shouldSkip() override;
        virtual bool shouldConsumeButBypass() { return playing && !active; }
        void processAudio(AudioBuffer* audioData, int32_t numFrames) override;

        bool hasData() { return audio_data != nullptr && current_frame_offset < audio_data->audio.getNumFrames(); };

        void setPlaying(bool newPlayingState);


        /// returns the size in frames that were successfully read. 0 if it is empty.
        int32_t read(AudioBuffer* dst, int32_t numFrames);

        /// Decodes WAV, MP3, Ogg or FLAC. Failure leaves the old data intact.
        /// The caller must hold the graph's ControlScope, never call from the audio callback.
        bool setAudioSource(uint8_t *data, int dataLength, const char *filename);
    };

    class AudioTrackNode : public AudioGraphNode {
        using Clip = choc::buffer::ChannelArrayBuffer<float>;
        std::unique_ptr<Clip> clip{};
        uint32_t current_frame_offset{0};
        bool active{false};
        std::atomic<float> audio_gain{1.0f};
        std::atomic<float> last_block_peak{0.0f};

    public:
        explicit AudioTrackNode(AudioGraph* ownerGraph) : AudioGraphNode(ownerGraph) {}

        void start() override;
        void pause() override;
        void processAudio(AudioBuffer* audioData, int32_t numFrames) override;
        void setGain(float gain);
        float getPeak() const { return last_block_peak.load(std::memory_order_relaxed); }
        uint64_t getDecodedBytes() const;

        // Publication/replacement and decoding require the graph's ControlScope.
        // The budget includes decoding buffers, while the previous clip remains alive.
        bool load(uint8_t* bytes, int dataLength, const char* filename, uint64_t byteBudget);
    };


    class MidiSourceNode : public AudioGraphNode {
        uint8_t* buffer;
        int32_t capacity;

        NanoSleepLock midi_buffer_mutex{};
        AAPMidiEventTranslator translator;

        int32_t sample_rate{0};
        int32_t aap_frame_size{1024};
        struct timespec last_aap_process_time{0, 0};

    public:
        MidiSourceNode(AudioGraph* ownerGraph,
                       RemotePluginInstance* instance,
                       int32_t sampleRate,
                       int32_t audioNumFramesPerCallback,
                       int32_t initialMidiProtocol = CMIDI2_PROTOCOL_TYPE_MIDI2,
                       int32_t internalBufferSize = AAP_PLUGIN_PLAYER_DEFAULT_MIDI_RING_BUFFER_SIZE);

        ~MidiSourceNode() override;

        void setPlugin(RemotePluginInstance* instance) { translator.setPlugin(instance); }

        void addMidiEvent(uint8_t *data, int32_t length, int64_t timestampInNanoseconds);

        void start() override;
        void pause() override;
        void processAudio(AudioBuffer* audioData, int32_t numFrames) override;
    };

    class MidiDestinationNode : public AudioGraphNode {
        uint8_t* buffer;
        int32_t capacity;

        NanoSleepLock midi_buffer_mutex{};

    public:
        explicit MidiDestinationNode(AudioGraph* ownerGraph, int32_t internalBufferSize = AAP_PLUGIN_PLAYER_DEFAULT_MIDI_RING_BUFFER_SIZE);

        ~MidiDestinationNode() override;

        int32_t readMidiEvents(uint8_t* dst, int32_t dstCapacity);

        void start() override;
        void pause() override;
        void processAudio(AudioBuffer* audioData, int32_t numFrames) override;
    };
}



#endif //AAP_CORE_AUDIOGRAPHNODE_H
