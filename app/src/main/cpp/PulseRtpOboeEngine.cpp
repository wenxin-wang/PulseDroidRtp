//
// Created by wenxin on 20-6-14.
//

#include "PulseRtpOboeEngine.h"
#include <android/log.h>
#include <logging_macros.h>
#include <trace.h>

namespace {
    static const unsigned kRtpHeader = 12;
    static const unsigned kNumChannel = 2;
    static const unsigned kSampleSize = 2;
    static const unsigned kSampleRate = 48000;
    static const unsigned kMaxLatency = 1000;
}

PacketBuffer::PacketBuffer(unsigned mtu)
        : head_(0)
        , tail_(0)
        , size_(0)
        , head_move_req_(0)
        , head_move_(0)
        , tail_move_req_(0)
        , tail_move_(0) {
    const unsigned num_buffer = (1 + kSampleRate * kMaxLatency / 1000 /
                                     (mtu / kNumChannel / kSampleSize));
    pkts_.reserve(num_buffer);
    for (unsigned i = 0; i < num_buffer; ++i) {
        pkts_.emplace_back(mtu / kSampleSize, 0);
    }
}

const std::vector<int16_t> *PacketBuffer::RefNextHeadForRead() {
    ++head_move_req_;
    auto head = head_.load(), tail = tail_.load();
    if (head == tail) {
        return nullptr;
    }
    if (++head >= pkts_.size()) {
        head = 0;
    }
    head_.store(head);
    ++head_move_;
    --size_;
    return &pkts_[head];
}

std::vector<int16_t> *PacketBuffer::RefTailForWrite() {
    auto tail = tail_.load();
    return &pkts_[tail];
}

bool PacketBuffer::NextTail() {
    ++tail_move_req_;
    auto head = head_.load(), tail = tail_.load();
    if (tail + 1 == head || (!head && tail == pkts_.size() - 1)) {
        return false;
    }
    if (++tail >= pkts_.size()) {
        tail = 0;
    }
    tail_.store(tail);
    ++tail_move_;
    ++size_;
    return true;
}

RtpReceiveThread::RtpReceiveThread(PacketBuffer &pkt_buffer,
                                   const std::string &ip, uint16_t port, int mtu)
        : pkt_buffer_(pkt_buffer), socket_(io_), data_(kRtpHeader + mtu) {
    Start(ip, port, mtu);
}

// borrowed from oboe samples
static void setThreadAffinity() {
    pid_t current_thread_id = gettid();
    cpu_set_t cpu_set;
    CPU_ZERO(&cpu_set);

    // If the callback cpu ids aren't specified then bind to the current cpu
    int current_cpu_id = sched_getcpu();
    LOGI("Binding to current CPU ID %d", current_cpu_id);
    CPU_SET(current_cpu_id, &cpu_set);
    // nproc = sysconf(_SC_NPROCESSORS_ONLN);
    int result = sched_setaffinity(current_thread_id, sizeof(cpu_set_t), &cpu_set);
    if (result == 0) {
        LOGV("Thread affinity set");
    } else {
        LOGW("Error setting thread affinity. Error no: %d", result);
    }
}

RtpReceiveThread::~RtpReceiveThread() {
    Stop();
}

void RtpReceiveThread::Start(const std::string& ip, uint16_t port, int mtu) {
    auto local_address = asio::ip::address::from_string(ip);
    bool is_mcast = local_address.is_multicast();
    auto listen_address = local_address;
    if (is_mcast) {
        if (local_address.is_v4()) {
            listen_address = asio::ip::address::from_string("0.0.0.0");
        } else if (local_address.is_v6()) {
            listen_address = asio::ip::address::from_string("::");
        }
    }

    thread_ = std::thread([=]() {
        setThreadAffinity();
        // Create the socket so that multiple may be bound to the same address.
        asio::ip::udp::endpoint listen_endpoint(listen_address, port);
        socket_.open(listen_endpoint.protocol());
        if (is_mcast) {
            socket_.set_option(asio::ip::udp::socket::reuse_address(true));
        }
        socket_.bind(listen_endpoint);

        // Join the multicast group.
        if (is_mcast) {
            socket_.set_option(asio::ip::multicast::join_group(local_address));
        }

        StartReceive();
        LOGI("Start Receiving");
        io_.run();
        LOGI("Stop Receiving");
    });
}

void RtpReceiveThread::Stop() {
    io_.stop();
    if (thread_.joinable()) {
        thread_.join();
    }
}

void RtpReceiveThread::StartReceive() {
    socket_.async_receive_from(
            asio::buffer(data_), sender_endpoint_,
            [&](const asio::error_code &error, size_t bytes_recvd) {
                if (error && error != asio::error::message_size) {
                    return;
                }
                if (error == asio::error::message_size) {
                    LOGE("Long packet");
                }
                HandleReceive(bytes_recvd);
            });
}

void RtpReceiveThread::HandleReceive(size_t bytes_recvd) {
    if (bytes_recvd <= kRtpHeader) {
        LOGE("Packet Too Small");
        StartReceive();
        return;
    } else if (bytes_recvd != data_.size()) {
        LOGE("Strange packet %zu", bytes_recvd);
    }
    auto buffer = pkt_buffer_.RefTailForWrite()->data();
    std::memcpy(buffer, data_.data() + kRtpHeader, bytes_recvd - kRtpHeader);
    if (!pkt_buffer_.NextTail()) {
        // LOGE("Packet Buffer Full");
    }

    StartReceive();
}

PulseRtpOboeEngine::PulseRtpOboeEngine(int latency_option,
                                       const std::string &ip,
                                       uint16_t port,
                                       unsigned mtu)
        : pkt_buffer_(mtu)
        , receive_thread_(pkt_buffer_, ip, port, mtu)
        , num_underrun_(0)
        , audio_buffer_size_(0) {
    // Trace::initialize();
    Start(latency_option, ip, port, mtu);
}

PulseRtpOboeEngine::~PulseRtpOboeEngine() {
    Stop();
}

void
PulseRtpOboeEngine::Start(int latency_option, const std::string &ip, uint16_t port, unsigned mtu) {
    oboe::PerformanceMode performanceMode = oboe::PerformanceMode::None;
    switch (latency_option) {
        case 0:
            performanceMode = oboe::PerformanceMode::LowLatency;
            break;
        case 1:
            performanceMode = oboe::PerformanceMode::None;
            break;
        case 2:
            performanceMode = oboe::PerformanceMode::PowerSaving;
            break;
        default:
            break;
    }

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output);
    builder.setPerformanceMode(performanceMode);
    builder.setSharingMode(oboe::SharingMode::Exclusive);
    builder.setFormat(oboe::AudioFormat::I16);
    builder.setChannelCount(oboe::ChannelCount::Stereo);
    // Always use default sample rate
    // builder.setSampleRate(48000);
    builder.setCallback(this);
    oboe::Result result = builder.openManagedStream(managedStream_);
    if (result != oboe::Result::OK) {
        LOGE("Failed to create stream. Error: %s",
             oboe::convertToText(result));
    }
    LOGI("Open stream, c:%d s:%d p:%d b:%d",
         getBufferCapacityInFrames(), getSharingMode(),
         getPerformanceMode(), getFramesPerBurst());

    managedStream_->requestStart();
}

void PulseRtpOboeEngine::Stop() {
    managedStream_->stop(); // timeout for 2s
    latencyTuner_.reset();
}

bool PulseRtpOboeEngine::EnsureBuffer() {
    while (!buffer_ || offset_ >= buffer_->size()) {
        offset_ = 0;
        buffer_ = pkt_buffer_.RefNextHeadForRead();
        if (!buffer_) {
            return false;
        }
    }
    return true;
}

oboe::DataCallbackResult
PulseRtpOboeEngine::onAudioReady(oboe::AudioStream *audioStream, void *audioData,
                                 int32_t numFrames) {
    auto *outputData = static_cast<int16_t *>(audioData);

    if (!is_thread_affinity_set_) {
        setThreadAffinity();
        is_thread_affinity_set_ = true;
    }
    if (!latencyTuner_) {
        latencyTuner_ = std::make_unique<oboe::LatencyTuner>(*audioStream);
    }
    if (audioStream->getAudioApi() == oboe::AudioApi::AAudio) {
        latencyTuner_->tune();
    }
    auto underrunCountResult = audioStream->getXRunCount();
    int bufferSize = audioStream->getBufferSizeInFrames();
    num_underrun_.store(underrunCountResult.value());
    audio_buffer_size_.store(bufferSize);
    // if (Trace::isEnabled())
    //     Trace::beginSection(
    //             "numFrames %d, Underruns %d, buffer size %d",
    //             numFrames, underrunCountResult.value(), bufferSize);

    size_t num_sample = 0;
    for (int i = 0; i < numFrames; ++i) {
        for (int j = 0; j < kNumChannel; ++j) {
            if (!EnsureBuffer()) {
                // LOGE("No more data: %zu/%d", num_sample, numFrames);
                goto no_more_data;
            }
            outputData[i * kNumChannel + j] = ntohs((*buffer_)[offset_]);
            // outputData[i] = ((*buffer_)[offset_]);
            ++offset_;
            ++num_sample;
        }
    }
    no_more_data:
    if (num_sample < unsigned(numFrames) * kNumChannel) {
        memset((char *) audioData + sizeof(int16_t) * num_sample, 0,
               sizeof(int16_t) * (unsigned(numFrames) * kNumChannel - num_sample));
        // std::string logstr =
        //         "Fill with empty: " + std::to_string(num_sample) + "/" + std::to_string(numFrames);
        // LOGE("Fill with empty: %zu/%d", num_sample, numFrames);
    }

    // if (Trace::isEnabled()) Trace::endSection();
    return oboe::DataCallbackResult::Continue;
}