#include "AudioGraphNode.h"
#include "AudioGraph.h"
#include <choc/audio/choc_AudioFileFormat_WAV.h>
#include <choc/audio/choc_AudioFileFormat_MP3.h>
#include <choc/audio/choc_AudioFileFormat_Ogg.h>
#include <choc/audio/choc_AudioFileFormat_FLAC.h>
#include <choc/audio/choc_SincInterpolator.h>
#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstring>
#include <limits>
#include <string>

namespace {
    constexpr uint64_t maxDecodedBytes = 64ULL * 1024 * 1024;
    using DecodedClip = choc::buffer::ChannelArrayBuffer<float>;

    // Readers borrow JNI memory only for the duration of the synchronous load.
    class SeekableByteBuffer : public std::streambuf {
        std::streamoff length;

    public:
        SeekableByteBuffer(uint8_t* data, std::size_t size) :
                length(static_cast<std::streamoff>(size)) {
            auto begin = reinterpret_cast<char*>(data);
            setg(begin, begin, begin + size);
        }

        std::streampos seekoff(std::streamoff off, std::ios_base::seekdir dir,
                               std::ios_base::openmode which) override {
            if (!(which & std::ios_base::in) || (which & std::ios_base::out))
                return std::streampos(std::streamoff(-1));
            std::streamoff base;
            if (dir == std::ios_base::beg)
                base = 0;
            else if (dir == std::ios_base::cur)
                base = gptr() - eback();
            else if (dir == std::ios_base::end)
                base = length;
            else
                return std::streampos(std::streamoff(-1));

            // Check before adding or doing pointer arithmetic, including negative/huge seeks.
            if (off < -base || off > length - base)
                return std::streampos(std::streamoff(-1));
            auto position = base + off;
            setg(eback(), eback() + position, egptr());
            return std::streampos(position);
        }

        std::streampos seekpos(std::streampos position,
                               std::ios_base::openmode which) override {
            return seekoff(static_cast<std::streamoff>(position), std::ios_base::beg, which);
        }
    };

    uint32_t littleEndian32(const uint8_t* bytes) {
        return uint32_t(bytes[0]) | (uint32_t(bytes[1]) << 8) |
               (uint32_t(bytes[2]) << 16) | (uint32_t(bytes[3]) << 24);
    }

    uint16_t littleEndian16(const uint8_t* bytes) {
        return uint16_t(bytes[0]) | (uint16_t(bytes[1]) << 8);
    }

    uint64_t littleEndian64(const uint8_t* bytes) {
        return uint64_t(littleEndian32(bytes)) | (uint64_t(littleEndian32(bytes + 4)) << 32);
    }

    // CHOC's WAV reader trusts blockAlign. A malformed value can otherwise make its
    // fixed-size raw read buffer overflow, or make its read loop never advance.
    bool validWavLayout(const uint8_t* data, uint64_t length) {
        if (length < 12 || std::memcmp(data + 8, "WAVE", 4) != 0)
            return false;
        const bool rf64 = std::memcmp(data, "RF64", 4) == 0;
        if (!rf64 && std::memcmp(data, "RIFF", 4) != 0)
            return false;
        uint64_t end = rf64 ? length : uint64_t(littleEndian32(data + 4)) + 8;
        if (end < 12 || end > length)
            return false;
        bool foundFormat = false, foundData = false, foundDs64 = false;
        uint64_t data64Size = 0;
        uint32_t blockAlign = 0;
        for (uint64_t position = 12; position < end;) {
            if (end - position < 8)
                return false;
            auto header = data + position;
            uint64_t size = littleEndian32(header + 4);
            auto content = position + 8;
            const bool isData = std::memcmp(header, "data", 4) == 0;
            if (rf64 && isData && size == 0xFFFFFFFFu) {
                if (!foundDs64)
                    return false;
                size = data64Size;
            }
            if (size > end - content)
                return false;
            auto payload = data + content;
            if (rf64 && std::memcmp(header, "ds64", 4) == 0) {
                if (foundDs64 || size < 28)
                    return false;
                auto riffSize = littleEndian64(payload);
                if (riffSize < 4 || riffSize > length - 8)
                    return false;
                end = riffSize + 8;
                if (content > end || size > end - content ||
                    littleEndian32(payload + 24) > (size - 28) / 12)
                    return false;
                data64Size = littleEndian64(payload + 8);
                foundDs64 = true;
            } else if (std::memcmp(header, "fmt ", 4) == 0) {
                if (foundFormat || foundData || size < 16)
                    return false;
                auto encoding = littleEndian16(payload);
                auto channels = littleEndian16(payload + 2);
                auto rate = littleEndian32(payload + 4);
                auto bits = littleEndian16(payload + 14);
                blockAlign = littleEndian16(payload + 12);
                if (channels < 1 || channels > 2 || rate == 0 || rate > 768000)
                    return false;
                if (encoding == 0xFFFE) {
                    static constexpr uint8_t guidTail[]{0, 0, 16, 0, 128, 0, 0, 170, 0, 56, 155, 113};
                    if (size < 40 || littleEndian16(payload + 16) < 22 ||
                        littleEndian16(payload + 18) != bits ||
                        std::memcmp(payload + 28, guidTail, sizeof(guidTail)) != 0)
                        return false;
                    auto subFormat = littleEndian32(payload + 24);
                    if (subFormat != 1 && subFormat != 3)
                        return false;
                    encoding = static_cast<uint16_t>(subFormat);
                }
                if (!((encoding == 1 && (bits == 8 || bits == 16 || bits == 24 || bits == 32)) ||
                      (encoding == 3 && (bits == 32 || bits == 64))) ||
                    blockAlign != channels * (bits / 8))
                    return false;
                foundFormat = true;
            } else if (isData) {
                if (!foundFormat || foundData || size == 0 || size % blockAlign != 0)
                    return false;
                foundData = true;
            }
            auto next = content + size;
            if ((size & 1) != 0) {
                if (next == end)
                    return false;
                ++next;
            }
            position = next;
        }
        return foundFormat && foundData && (!rf64 || foundDs64);
    }

    uint64_t clipBytes(uint32_t channels, uint64_t frames) {
        // CHOC channel arrays align each channel to 16 bytes and keep a pointer table.
        return (((frames * sizeof(float) + 15) & ~uint64_t(15)) + sizeof(float*)) * channels;
    }

    bool finiteSamples(const DecodedClip& clip) {
        for (uint32_t ch = 0; ch < clip.getNumChannels(); ++ch) {
            auto samples = clip.getChannel(ch).data.data;
            for (uint32_t frame = 0; frame < clip.getNumFrames(); ++frame)
                if (!std::isfinite(samples[frame]))
                    return false;
        }
        return true;
    }

    std::unique_ptr<DecodedClip> decodeAudio(uint8_t* data, int dataLength,
                                           const char* filename, int32_t sampleRate,
                                           uint64_t byteBudget, long double maxDuration = 120.0L) {
        if (!data || dataLength <= 0 || static_cast<uint64_t>(dataLength) > maxDecodedBytes ||
            !filename || !*filename || sampleRate <= 0 || sampleRate > 768000 || byteBudget == 0)
            return nullptr;
        try {
            std::string name(filename);
            std::transform(name.begin(), name.end(), name.begin(),
                           [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
            static choc::audio::WAVAudioFileFormat<false> wav{};
            static choc::audio::MP3AudioFileFormat mp3{};
            static choc::audio::OggAudioFileFormat<false> ogg{};
            static choc::audio::FLACAudioFileFormat<false> flac{};
            choc::audio::AudioFileFormat* formats[]{&wav, &mp3, &ogg, &flac};
            for (auto format : formats) {
                if (!format->filenameSuffixMatches(name))
                    continue;
                if (format == &wav && !validWavLayout(data, static_cast<uint64_t>(dataLength)))
                    return nullptr;
                SeekableByteBuffer buffer(data, static_cast<std::size_t>(dataLength));
                auto stream = std::make_shared<std::istream>(&buffer);
                auto reader = format->createReader(stream);
                if (!reader)
                    return nullptr;
                const auto& props = reader->getProperties();
                if (props.numChannels < 1 || props.numChannels > 2 || props.numFrames == 0 ||
                    props.numFrames > static_cast<uint64_t>(std::numeric_limits<int32_t>::max()) ||
                    !std::isfinite(props.sampleRate) || props.sampleRate <= 0 ||
                    props.sampleRate > 768000)
                    return nullptr;
                auto duration = static_cast<long double>(props.numFrames) / props.sampleRate;
                if (!std::isfinite(duration) || duration <= 0 || duration > maxDuration)
                    return nullptr;
                auto resampledFrames = props.sampleRate == sampleRate ?
                                       static_cast<long double>(props.numFrames) :
                                       std::max(1.0L, std::round(duration * sampleRate));
                if (!std::isfinite(resampledFrames) || resampledFrames < 1 ||
                    resampledFrames > std::numeric_limits<int32_t>::max())
                    return nullptr;
                auto targetFrames = static_cast<uint32_t>(resampledFrames);
                auto sourceBytes = clipBytes(props.numChannels, props.numFrames);
                auto targetBytes = targetFrames == props.numFrames ? 0 :
                                   clipBytes(props.numChannels, targetFrames);
                // sincInterpolate allocates one mono intermediate when downsampling.
                auto scratchBytes = targetFrames < props.numFrames ? props.numFrames * sizeof(float) : 0;
                if (sourceBytes + targetBytes + scratchBytes > std::min(byteBudget, maxDecodedBytes))
                    return nullptr;

                auto source = std::make_unique<DecodedClip>(props.numChannels,
                                                           static_cast<uint32_t>(props.numFrames));
                if (!reader->readFrames(0, source->getView()) || !finiteSamples(*source))
                    return nullptr;
                if (targetFrames == props.numFrames)
                    return source;
                auto result = std::make_unique<DecodedClip>(props.numChannels, targetFrames);
                choc::interpolation::sincInterpolate(*result, *source);
                if (!finiteSamples(*result))
                    return nullptr;
                return result;
            }
        } catch (...) {
            // Corrupt streams, decoder errors and allocation failures never replace the clip.
        }
        return nullptr;
    }
}

aap::AudioDataSourceNode::AudioDataSourceNode(aap::AudioGraph* ownerGraph) :
        AudioGraphNode(ownerGraph) {
}

bool aap::AudioDataSourceNode::shouldSkip() {
    // Inactive but playing sources still consume their stream.
    return !hasData() || !playing;
}

void aap::AudioDataSourceNode::processAudio(AudioBuffer* audioData, int32_t numFrames) {
    read(audioData, numFrames);
}

void aap::AudioDataSourceNode::start() {
    active = true;
}

void aap::AudioDataSourceNode::pause() {
    active = false;
}

void aap::AudioDataSourceNode::setPlaying(bool newPlayingState) {
    if (playing)
        current_frame_offset = 0;
    playing = newPlayingState;
}

int32_t aap::AudioDataSourceNode::read(AudioBuffer* dst, int32_t numFrames) {
    if (!hasData() || numFrames <= 0)
        return 0;
    auto size = std::min(audio_data->audio.getNumFrames() - current_frame_offset,
                         std::min(static_cast<uint32_t>(numFrames), dst->audio.getNumFrames()));
    if (!shouldConsumeButBypass()) {
        choc::buffer::FrameRange range{static_cast<uint32_t>(current_frame_offset),
                                       static_cast<uint32_t>(current_frame_offset) + size};
        choc::buffer::copyRemappingChannels(dst->audio.getStart(size),
                                            audio_data->audio.getFrameRange(range));
    }
    current_frame_offset += size;
    return static_cast<int32_t>(size);
}

bool aap::AudioDataSourceNode::setAudioSource(uint8_t* data, int dataLength, const char* filename) {
    auto decoded = decodeAudio(data, dataLength, filename, graph->getSampleRate(), maxDecodedBytes);
    if (!decoded)
        return false;
    try {
        auto replacement = std::make_unique<AudioBuffer>(decoded->getNumChannels(), 0);
        if (!replacement->midi_in || !replacement->midi_out)
            return false;
        replacement->audio = std::move(*decoded);
        audio_data = std::move(replacement);
        current_frame_offset = 0;
        return true;
    } catch (...) {
        return false;
    }
}

aap::AudioDataSourceNode::~AudioDataSourceNode() = default;

void aap::AudioTrackNode::start() {
    timeline_frame = 0;
    active = true;
    last_block_peak.store(0.0f, std::memory_order_relaxed);
}

void aap::AudioTrackNode::pause() {
    active = false;
    last_block_peak.store(0.0f, std::memory_order_relaxed);
}

void aap::AudioTrackNode::setGain(float gain) {
    audio_gain.store(std::isfinite(gain) ? std::max(0.0f, gain) : 0.0f,
                     std::memory_order_relaxed);
}

uint64_t aap::AudioTrackNode::getDecodedBytes() const {
    return clip ? clipBytes(clip->getNumChannels(), clip->getNumFrames()) : 0;
}

bool aap::AudioTrackNode::load(uint8_t* bytes, int dataLength, const char* filename,
                              uint64_t byteBudget) {
    auto replacement = decodeAudio(bytes, dataLength, filename, graph->getSampleRate(), byteBudget);
    if (!replacement)
        return false;
    clip = std::move(replacement);
    if (!active)
        timeline_frame = 0;
    last_block_peak.store(0.0f, std::memory_order_relaxed);
    return true;
}

uint64_t aap::AudioTrackNode::getDurationFrames() const {
    return clip ? clip->getNumFrames() : 0;
}

bool aap::AudioTrackNode::setPlacements(const std::vector<uint64_t>& starts) {
    if (starts.size() > max_placements)
        return false;
    for (auto start : starts)
        if (start > max_placement_start ||
            getDurationFrames() > std::numeric_limits<uint64_t>::max() - start)
            return false;
    try {
        auto replacement = starts;
        placements.swap(replacement);
        return true;
    } catch (...) {
        return false;
    }
}

void aap::AudioTrackNode::processAudio(AudioBuffer* audioData, int32_t numFrames) {
    float peak = 0.0f;
    if (active && numFrames > 0) {
        // Saturate rather than wrap even after an exceptionally long transport run.
        const auto advance = std::min<uint64_t>(numFrames,
                std::numeric_limits<uint64_t>::max() - timeline_frame);
        const auto end = timeline_frame + std::min<uint64_t>(advance, audioData->audio.getNumFrames());
        const auto gain = audio_gain.load(std::memory_order_relaxed);
        if (clip && gain != 0.0f) {
            auto channels = audioData->audio.getNumChannels();
            auto sourceChannels = clip->getNumChannels();
            // Mono is duplicated; stereo is averaged for a mono bus, or uses left/right only.
            auto mappedChannels = sourceChannels == 1 ? channels : std::min(channels, sourceChannels);
            for (auto start : placements) {
                // Starts are bounded to 2^53 and decoded lengths to int32_t, so this cannot wrap.
                auto clipEnd = start + getDurationFrames();
                auto begin = std::max(timeline_frame, start);
                auto overlapEnd = std::min(end, clipEnd);
                if (begin >= overlapEnd)
                    continue;
                auto sourceOffset = static_cast<uint32_t>(begin - start);
                auto destinationOffset = static_cast<uint32_t>(begin - timeline_frame);
                auto frames = static_cast<uint32_t>(overlapEnd - begin);
                for (uint32_t ch = 0; ch < mappedChannels; ++ch) {
                    auto dst = audioData->audio.getChannel(ch).data.data + destinationOffset;
                    auto src = clip->getChannel(sourceChannels == 1 ? 0 : ch).data.data + sourceOffset;
                    auto right = sourceChannels == 2 && channels == 1 ?
                                 clip->getChannel(1).data.data + sourceOffset : nullptr;
                    for (uint32_t frame = 0; frame < frames; ++frame) {
                        const auto sample = (right ? src[frame] * 0.5f + right[frame] * 0.5f : src[frame]) * gain;
                        dst[frame] += sample;
                        peak = std::max(peak, std::fabs(sample));
                    }
                }
            }
        }
        // Advance through gaps, empty schedules, EOF and mute without restarting instances.
        timeline_frame += advance;
    }
    last_block_peak.store(peak, std::memory_order_relaxed);
}

namespace {
    constexpr float twoPi = 6.2831853071795864769f;

    float polyBlep(float phase, float increment) {
        if (phase < increment) {
            auto t = phase / increment;
            return t + t - t * t - 1.0f;
        }
        if (phase > 1.0f - increment) {
            auto t = (phase - 1.0f) / increment;
            return t * t + t + t + 1.0f;
        }
        return 0.0f;
    }

    float analogOscillator(float phase, float increment) {
        auto saw = phase * 2.0f - 1.0f - polyBlep(phase, increment);
        auto shifted = phase + 0.5f;
        if (shifted >= 1.0f)
            shifted -= 1.0f;
        auto pulse = (phase < 0.5f ? 1.0f : -1.0f) + polyBlep(phase, increment) -
                     polyBlep(shifted, increment);
        return 0.6f * saw + 0.15f * pulse - 0.25f * std::sin(twoPi * phase);
    }

    float drumDuration(int pitch) {
        switch (pitch) {
            case 36: return 0.65f;
            case 38: return 0.30f;
            case 39: return 0.28f;
            case 42: return 0.12f;
            case 46: return 0.70f;
            case 45: return 0.55f;
            case 49: return 1.80f;
            case 51: return 1.25f;
            default: return 0.0f;
        }
    }
}

aap::BuiltinInstrumentNode::BuiltinInstrumentNode(AudioGraph* ownerGraph, int instrumentKind) :
        AudioGraphNode(ownerGraph), kind(instrumentKind),
        fade_frames(std::max(1L, std::lround(std::max(1, ownerGraph->getSampleRate()) * 0.005))),
        sample_rate(static_cast<float>(std::max(1, ownerGraph->getSampleRate()))),
        attack_step(1.0f / (sample_rate * (kind == 0 ? 0.006f : 0.012f))),
        decay_step((1.0f - (kind == 0 ? 0.70f : 0.55f)) /
                   (sample_rate * (kind == 0 ? 0.13f : 0.25f))),
        sustain_level(kind == 0 ? 0.70f : 0.55f),
        release_frames(std::max(1.0f, sample_rate * (kind == 0 ? 0.16f : 0.35f))) {
    static_assert(std::atomic<uint32_t>::is_always_lock_free &&
                  std::atomic<bool>::is_always_lock_free && std::atomic<float>::is_always_lock_free,
                  "Built-in instruments require lock-free callback atomics");
}

void aap::BuiltinInstrumentNode::reset() {
    // ControlScope quiesces both the producer and consumer before resetting the ring.
    event_read.store(0, std::memory_order_relaxed);
    event_write.store(0, std::memory_order_relaxed);
    event_overflow.store(false, std::memory_order_relaxed);
    voices = {};
    next_serial = 0;
    last_block_peak.store(0.0f, std::memory_order_relaxed);
}

void aap::BuiltinInstrumentNode::start() {
    reset();
    active = true;
}

void aap::BuiltinInstrumentNode::pause() {
    active = false;
    reset();
}

void aap::BuiltinInstrumentNode::setGain(float gain) {
    audio_gain.store(std::isfinite(gain) ? std::clamp(gain, 0.0f, 4.0f) : 0.0f,
                     std::memory_order_relaxed);
}

void aap::BuiltinInstrumentNode::note(int pitch, float velocity, bool down) {
    if (!active || pitch < 0 || pitch > 127 || !std::isfinite(velocity))
        return;
    auto write = event_write.load(std::memory_order_relaxed);
    if (write - event_read.load(std::memory_order_acquire) >= event_capacity) {
        event_overflow.store(true, std::memory_order_release);
        return;
    }
    events[write % event_capacity] = {pitch, std::clamp(velocity, 0.0f, 1.0f), down && velocity > 0};
    event_write.store(write + 1, std::memory_order_release);
}

void aap::BuiltinInstrumentNode::releaseVoice(Voice& voice) {
    if (voice.stage != Off && voice.stage != Release && voice.stage != Drum) {
        voice.release_step = voice.envelope / release_frames;
        voice.stage = Release;
    }
}

void aap::BuiltinInstrumentNode::retireVoice(Voice& voice) {
    voice.tail_left = voice.last_left;
    voice.tail_right = voice.last_right;
    voice.tail_frames = fade_frames;
    voice.stage = Off;
}

void aap::BuiltinInstrumentNode::handleNote(const NoteEvent& event) {
    if (!event.down) {
        if (kind != 2)
            for (auto& voice : voices)
                if (voice.pitch == event.pitch)
                    releaseVoice(voice);
        return;
    }
    if (kind == 2 && !samples[event.pitch] && drumDuration(event.pitch) == 0.0f)
        return;
    if (kind == 2 && event.pitch == 42)
        for (auto& voice : voices)
            if (voice.pitch == 46 && voice.stage == Drum)
                retireVoice(voice);

    Voice* selected = nullptr;
    if (kind != 2)
        for (auto& voice : voices)
            if (voice.pitch == event.pitch && voice.stage != Off) {
                selected = &voice;
                break;
            }
    if (!selected)
        for (auto& voice : voices)
            if (voice.stage == Off && voice.tail_frames == 0) {
                selected = &voice;
                break;
            }
    if (!selected) {
        selected = &voices[0];
        for (auto& voice : voices) {
            auto releasing = voice.stage == Release || voice.stage == Off;
            auto selectedReleasing = selected->stage == Release || selected->stage == Off;
            if ((releasing && !selectedReleasing) ||
                (releasing && selectedReleasing && voice.envelope < selected->envelope) ||
                (releasing == selectedReleasing && voice.serial < selected->serial))
                selected = &voice;
        }
    }
    auto oldLeft = selected->last_left;
    auto oldRight = selected->last_right;
    *selected = {};
    selected->tail_left = oldLeft;
    selected->tail_right = oldRight;
    selected->tail_frames = fade_frames;
    selected->stage = kind == 2 ? Drum : Attack;
    selected->pitch = event.pitch;
    selected->velocity = event.velocity;
    selected->serial = ++next_serial;
    selected->random = 0x9E3779B9u ^ (static_cast<uint32_t>(next_serial) * 747796405u);
    if (selected->random == 0)
        selected->random = 1;
    auto frequency = 440.0f * std::exp2((event.pitch - 69) / 12.0f);
    selected->increment = std::min(0.45f, frequency / sample_rate);
}

uint64_t aap::BuiltinInstrumentNode::getDecodedBytes(int pitch) const {
    if (pitch < 0 || pitch > 127 || !samples[pitch])
        return 0;
    return clipBytes(samples[pitch]->getNumChannels(), samples[pitch]->getNumFrames());
}

bool aap::BuiltinInstrumentNode::loadSample(int pitch, uint8_t* bytes, int dataLength,
                                           const char* filename, uint64_t byteBudget) {
    if (kind != 2 || pitch < 0 || pitch > 127)
        return false;
    auto replacement = decodeAudio(bytes, dataLength, filename, graph->getSampleRate(), byteBudget, 20.0L);
    if (!replacement)
        return false;
    for (auto& voice : voices)
        if (voice.pitch == pitch && voice.stage == Drum)
            retireVoice(voice);
    samples[pitch] = std::move(replacement);
    last_block_peak.store(0.0f, std::memory_order_relaxed);
    return true;
}

void aap::BuiltinInstrumentNode::renderVoice(Voice& voice, float& left, float& right) {
    left = right = 0.0f;
    if (voice.stage == Drum) {
        const auto* clip = samples[voice.pitch].get();
        if (clip) {
            auto frames = clip->getNumFrames();
            if (voice.frame < frames) {
                auto fade = std::min(fade_frames, std::max(1u, frames / 2));
                auto envelope = std::min(1.0f, static_cast<float>(voice.frame + 1) / fade) *
                                std::min(1.0f, static_cast<float>(frames - voice.frame) / fade);
                auto scale = voice.velocity * envelope * 0.5f;
                left = std::clamp(clip->getChannel(0).data.data[voice.frame], -4.0f, 4.0f) * scale;
                right = clip->getNumChannels() == 1 ? left :
                        std::clamp(clip->getChannel(1).data.data[voice.frame], -4.0f, 4.0f) * scale;
                ++voice.frame;
            } else {
                voice.stage = Off;
            }
        } else {
            auto t = static_cast<float>(voice.frame) / sample_rate;
            auto duration = drumDuration(voice.pitch);
            if (t >= duration) {
                voice.stage = Off;
            } else {
                voice.random ^= voice.random << 13;
                voice.random ^= voice.random >> 17;
                voice.random ^= voice.random << 5;
                auto noise = static_cast<float>(voice.random >> 8) / 8388607.5f - 1.0f;
                auto highNoise = (noise - voice.noise_previous) * 0.5f;
                voice.noise_previous = noise;
                float signal = 0.0f;
                switch (voice.pitch) {
                    case 36: {
                        auto frequency = 45.0f + 120.0f * std::exp(-t * 40.0f);
                        voice.phase += std::min(0.45f, frequency / sample_rate);
                        voice.phase -= std::floor(voice.phase);
                        signal = std::sin(twoPi * voice.phase) * std::exp(-t * 8.0f) +
                                 noise * 0.12f * std::exp(-t * 160.0f);
                        break;
                    }
                    case 38:
                        signal = highNoise * 0.85f * std::exp(-t * 16.0f) +
                                 std::sin(twoPi * t * 185.0f) * 0.25f * std::exp(-t * 24.0f);
                        break;
                    case 39: {
                        float bursts = 0.0f;
                        for (int burst = 0; burst < 3; ++burst) {
                            auto age = t - burst * 0.012f;
                            if (age >= 0.0f)
                                bursts += std::min(1.0f, age * 1500.0f) * std::exp(-age * 100.0f);
                        }
                        signal = highNoise * (bursts + 0.3f * std::exp(-t * 20.0f));
                        break;
                    }
                    case 45: {
                        auto frequency = 100.0f + 75.0f * std::exp(-t * 22.0f);
                        voice.phase += std::min(0.45f, frequency / sample_rate);
                        voice.phase -= std::floor(voice.phase);
                        signal = std::sin(twoPi * voice.phase) * std::exp(-t * 9.0f) +
                                 noise * 0.06f * std::exp(-t * 45.0f);
                        break;
                    }
                    default: {
                        float metal = 0.0f;
                        const float partials[]{2341.0f, 3457.0f, 5311.0f, 7897.0f};
                        for (auto frequency : partials)
                            if (frequency < sample_rate * 0.45f)
                                metal += std::sin(twoPi * t * frequency) * 0.25f;
                        auto decay = voice.pitch == 42 ? 42.0f : voice.pitch == 46 ? 8.0f :
                                     voice.pitch == 49 ? 3.5f : 5.0f;
                        signal = (voice.pitch == 51 ? metal * 0.8f + highNoise * 0.2f :
                                  metal * 0.25f + highNoise * 0.75f) * std::exp(-t * decay);
                        break;
                    }
                }
                auto envelope = std::min(1.0f, t * 1000.0f) *
                                std::min(1.0f, std::max(0.0f, duration - t) / 0.008f);
                left = right = signal * envelope * voice.velocity * (voice.pitch == 36 ? 0.6f : 0.45f);
                ++voice.frame;
            }
        }
    } else if (voice.stage != Off) {
        switch (voice.stage) {
            case Attack:
                voice.envelope = std::min(1.0f, voice.envelope + attack_step);
                if (voice.envelope >= 1.0f)
                    voice.stage = Decay;
                break;
            case Decay:
                voice.envelope = std::max(sustain_level, voice.envelope - decay_step);
                if (voice.envelope <= sustain_level)
                    voice.stage = Sustain;
                break;
            case Release:
                voice.envelope = std::max(0.0f, voice.envelope - voice.release_step);
                if (voice.envelope <= 0.0f)
                    voice.stage = Off;
                break;
            default: break;
        }
        float oscillator;
        if (kind == 0) {
            oscillator = std::sin(twoPi * voice.phase) * 0.78f;
            if (voice.increment < 0.225f)
                oscillator += std::sin(twoPi * voice.phase * 2.0f) * 0.16f;
            if (voice.increment < 0.15f)
                oscillator += std::sin(twoPi * voice.phase * 3.0f) * 0.06f;
        } else {
            oscillator = (analogOscillator(voice.phase, voice.increment) +
                          analogOscillator(voice.phase2, voice.increment * 1.0043f)) * 0.5f;
        }
        auto cutoff = std::min(0.35f, voice.increment * (kind == 0 ? 4.0f : 2.0f + voice.envelope * 8.0f));
        auto coefficient = twoPi * cutoff / (1.0f + twoPi * cutoff);
        voice.filter += coefficient * (oscillator - voice.filter);
        if (std::fabs(voice.filter) < 1.0e-20f)
            voice.filter = 0.0f;
        left = right = voice.filter * voice.envelope * voice.velocity * (kind == 0 ? 0.28f : 0.16f);
        voice.phase += voice.increment;
        voice.phase -= std::floor(voice.phase);
        voice.phase2 += voice.increment * 1.0043f;
        voice.phase2 -= std::floor(voice.phase2);
    }
    if (voice.tail_frames > 0) {
        auto fade = static_cast<float>(voice.tail_frames) / fade_frames;
        left += voice.tail_left * fade;
        right += voice.tail_right * fade;
        --voice.tail_frames;
    }
    voice.last_left = left;
    voice.last_right = right;
}

void aap::BuiltinInstrumentNode::processAudio(AudioBuffer* audioData, int32_t numFrames) {
    if (!active) {
        last_block_peak.store(0.0f, std::memory_order_relaxed);
        return;
    }
    auto read = event_read.load(std::memory_order_relaxed);
    auto overflow = event_overflow.exchange(false, std::memory_order_acq_rel);
    auto write = event_write.load(std::memory_order_acquire);
    if (overflow) {
        for (auto& voice : voices)
            releaseVoice(voice);
        read = write;
    }
    while (read != write) {
        handleNote(events[read % event_capacity]);
        ++read;
    }
    event_read.store(read, std::memory_order_release);

    auto frames = std::min(static_cast<uint32_t>(std::max(0, numFrames)), audioData->audio.getNumFrames());
    auto channels = audioData->audio.getNumChannels();
    auto gain = audio_gain.load(std::memory_order_relaxed);
    float peak = 0.0f;
    for (uint32_t frame = 0; frame < frames; ++frame) {
        float left = 0.0f, right = 0.0f;
        for (auto& voice : voices) {
            if (voice.stage == Off && voice.tail_frames == 0)
                continue;
            float voiceLeft, voiceRight;
            renderVoice(voice, voiceLeft, voiceRight);
            left += voiceLeft;
            right += voiceRight;
        }
        left = 0.8f * std::tanh(left) * gain;
        right = 0.8f * std::tanh(right) * gain;
        for (uint32_t channel = 0; channel < channels; ++channel) {
            auto sample = channels == 1 || channel > 1 ? left * 0.5f + right * 0.5f :
                          channel == 0 ? left : right;
            audioData->audio.getChannel(channel).data.data[frame] += sample;
            peak = std::max(peak, std::fabs(sample));
        }
    }
    last_block_peak.store(peak, std::memory_order_relaxed);
}
