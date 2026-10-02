#include <jni.h>
#include <errno.h>
#include <stdint.h>
#include <sys/stat.h>
#include <unistd.h>

extern "C" {
#include <libavformat/avformat.h>
#include <libavutil/error.h>
#include <libavutil/mathematics.h>
#include <libavutil/mem.h>
#include <libavutil/time.h>
}

#include "jni_utils.h"

namespace {
constexpr int kBufferSize = 32 * 1024;
constexpr int64_t kProbeBudgetUs = 5 * AV_TIME_BASE;

// The caller owns fd for this entire synchronous call. No player state is used.
struct Probe {
    int fd;
    int64_t deadline;
    AVFormatContext *format = nullptr;
    AVIOContext *io = nullptr;

    Probe(int descriptor) : fd(descriptor), deadline(av_gettime_relative() + kProbeBudgetUs) {}
    ~Probe() {
        avformat_close_input(&format);
        if (io) {
            // FFmpeg may replace the original buffer; free the current one.
            av_freep(&io->buffer);
            avio_context_free(&io);
        }
    }

    bool expired() const { return av_gettime_relative() >= deadline; }
};

int interrupt(void *opaque) {
    return static_cast<Probe *>(opaque)->expired();
}

int readPacket(void *opaque, uint8_t *buffer, int size) {
    Probe &probe = *static_cast<Probe *>(opaque);
    ssize_t result;
    do {
        if (probe.expired()) return AVERROR_EXIT;
        result = read(probe.fd, buffer, size);
    } while (result < 0 && errno == EINTR);
    if (result < 0) return AVERROR(errno);
    return result == 0 ? AVERROR_EOF : static_cast<int>(result);
}

int64_t seek(void *opaque, int64_t offset, int whence) {
    Probe &probe = *static_cast<Probe *>(opaque);
    if (probe.expired()) return AVERROR_EXIT;
    if (whence & AVSEEK_SIZE) {
        struct stat64 info;
        int result;
        do {
            result = fstat64(probe.fd, &info);
        } while (result < 0 && errno == EINTR && !probe.expired());
        if (result < 0) return AVERROR(errno);
        return S_ISREG(info.st_mode) ? info.st_size : AVERROR(ENOSYS);
    }
    off64_t result;
    do {
        if (probe.expired()) return AVERROR_EXIT;
        result = lseek64(probe.fd, offset, whence & ~AVSEEK_FORCE);
    } while (result < 0 && errno == EINTR);
    return result < 0 ? AVERROR(errno) : result;
}

// Demux only the supplied descriptor, including when a container references
// another file or URL. Never let a nested demuxer open a network connection.
int rejectAdditionalInput(AVFormatContext *, AVIOContext **, const char *, int, AVDictionary **) {
    return AVERROR(EACCES);
}

bool usableVideo(const AVStream *stream) {
    return stream->codecpar->codec_type == AVMEDIA_TYPE_VIDEO &&
           !(stream->disposition & AV_DISPOSITION_ATTACHED_PIC);
}
}

extern "C" jni_func(jlongArray, probeMedia, jint fd) {
    if (fd < 0) return nullptr;
    Probe probe(fd);
    // SAF videos must be seekable. Reject pipes rather than potentially blocking
    // forever waiting for data or inventing metadata from an incomplete stream.
    if (seek(&probe, 0, SEEK_SET) < 0) return nullptr;

    probe.format = avformat_alloc_context();
    if (!probe.format) return nullptr;
    uint8_t *buffer = static_cast<uint8_t *>(av_malloc(kBufferSize));
    if (!buffer) return nullptr;
    probe.io = avio_alloc_context(buffer, kBufferSize, 0, &probe, readPacket, nullptr, seek);
    if (!probe.io) {
        av_free(buffer);
        return nullptr;
    }
    probe.io->seekable = AVIO_SEEKABLE_NORMAL;
    probe.format->pb = probe.io;
    probe.format->flags |= AVFMT_FLAG_CUSTOM_IO;
    probe.format->interrupt_callback = {interrupt, &probe};
    probe.format->io_open = rejectAdditionalInput;
    // Empty (not null) permits no URL protocols, also for nested demuxers
    // propagating this setting directly instead of using io_open.
    probe.format->protocol_whitelist = av_strdup("");
    if (!probe.format->protocol_whitelist) return nullptr;
    probe.format->probesize = 8 * 1024 * 1024;
    probe.format->max_analyze_duration = 3 * AV_TIME_BASE;
    if (avformat_open_input(&probe.format, nullptr, nullptr, nullptr) < 0 ||
        avformat_find_stream_info(probe.format, nullptr) < 0 || probe.expired()) {
        return nullptr;
    }
    // Dynamic-stream containers cannot establish subtitle absence from a
    // bounded probe. Preserve unknown rather than claiming an incomplete list.
    if (probe.format->ctx_flags & AVFMTCTX_NOHEADER) return nullptr;

    jlong values[4] = {-1, 0, 0, 0};
    if (probe.format->duration != AV_NOPTS_VALUE && probe.format->duration > 0)
        values[0] = probe.format->duration;
    const bool hasContainerDuration = values[0] > 0;

    int primary = av_find_best_stream(probe.format, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
    if (primary >= 0 && !usableVideo(probe.format->streams[primary])) primary = -1;
    for (unsigned int i = 0; i < probe.format->nb_streams; ++i) {
        const AVStream *stream = probe.format->streams[i];
        const AVCodecParameters *codec = stream->codecpar;
        if (codec->codec_type == AVMEDIA_TYPE_SUBTITLE) values[3] = 1;
        if (usableVideo(stream) && (primary < 0 ||
            (!(probe.format->streams[primary]->disposition & AV_DISPOSITION_DEFAULT) &&
             (stream->disposition & AV_DISPOSITION_DEFAULT)))) {
            primary = static_cast<int>(i);
        }
        if (!hasContainerDuration && (codec->codec_type == AVMEDIA_TYPE_VIDEO ||
                                     codec->codec_type == AVMEDIA_TYPE_AUDIO) &&
            stream->duration != AV_NOPTS_VALUE && stream->duration > 0 &&
            stream->time_base.num > 0 && stream->time_base.den > 0) {
            // Used below only when the container does not report a duration.
            const int64_t duration = av_rescale_q(stream->duration, stream->time_base, AV_TIME_BASE_Q);
            if (duration > values[0]) values[0] = duration;
        }
    }
    if (primary >= 0) {
        const AVCodecParameters *codec = probe.format->streams[primary]->codecpar;
        if (codec->width > 0 && codec->height > 0) {
            values[1] = codec->width;
            values[2] = codec->height;
        }
    }

    jlongArray result = env->NewLongArray(4);
    if (!result) return nullptr; // Preserve the JVM allocation exception.
    env->SetLongArrayRegion(result, 0, 4, values);
    return env->ExceptionCheck() ? nullptr : result;
}
