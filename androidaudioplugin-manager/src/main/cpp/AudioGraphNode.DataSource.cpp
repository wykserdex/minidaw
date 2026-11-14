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
                                           uint64_t byteBudget) {
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
                if (!std::isfinite(duration) || duration <= 0 || duration > 120.0L)
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
    current_frame_offset = 0;
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
    current_frame_offset = 0;
    last_block_peak.store(0.0f, std::memory_order_relaxed);
    return true;
}

void aap::AudioTrackNode::processAudio(AudioBuffer* audioData, int32_t numFrames) {
    float peak = 0.0f;
    if (active && clip && current_frame_offset < clip->getNumFrames() && numFrames > 0) {
        auto frames = std::min(clip->getNumFrames() - current_frame_offset,
                               std::min(static_cast<uint32_t>(numFrames), audioData->audio.getNumFrames()));
        const auto gain = audio_gain.load(std::memory_order_relaxed);
        auto channels = audioData->audio.getNumChannels();
        auto sourceChannels = clip->getNumChannels();
        // Mono is duplicated; stereo is averaged for a mono bus, or uses left/right only.
        auto mappedChannels = sourceChannels == 1 ? channels : std::min(channels, sourceChannels);
        for (uint32_t ch = 0; ch < mappedChannels; ++ch) {
            auto dst = audioData->audio.getChannel(ch).data.data;
            auto src = clip->getChannel(sourceChannels == 1 ? 0 : ch).data.data + current_frame_offset;
            auto right = sourceChannels == 2 && channels == 1 ?
                         clip->getChannel(1).data.data + current_frame_offset : nullptr;
            for (uint32_t frame = 0; frame < frames; ++frame) {
                const auto sample = gain == 0.0f ? 0.0f :
                                    (right ? src[frame] * 0.5f + right[frame] * 0.5f : src[frame]) * gain;
                dst[frame] += sample;
                peak = std::max(peak, std::fabs(sample));
            }
        }
        // Muted tracks advance too. At EOF they remain silent until the next start.
        current_frame_offset += frames;
    }
    last_block_peak.store(peak, std::memory_order_relaxed);
}
