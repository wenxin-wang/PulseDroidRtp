//
// Created by wenxin on 20-6-14.
//

#include "PulseRtpOboeEngine.h"
#include <android/log.h>
#include <logging_macros.h>
#include <trace.h>

PacketBuffer::PacketBuffer()
        : head_move_req_(0), head_move_(0), tail_move_req_(0), tail_move_(0), head_(0), tail_(0) {
    pkts_.reserve(kPacketBufferSize);
    for (unsigned i = 0; i < kPacketBufferSize; ++i) {
        pkts_.emplace_back(kRtpMtu / kSampleSize, 0);
    }
}

const std::vector<int16_t> *PacketBuffer::RefNextHeadForRead() {
    ++head_move_req_;
    auto head = head_.load(), tail = tail_.load();
    if (head == tail) {
        return nullptr;
    }
    if (++head >= kPacketBufferSize) {
        head = 0;
    }
    head_.store(head);
    ++head_move_;
    return &pkts_[head];
}

std::vector<int16_t> *PacketBuffer::RefTailForWrite() {
    auto tail = tail_.load();
    return &pkts_[tail];
}

bool PacketBuffer::NextTail() {
    ++tail_move_req_;
    auto head = head_.load(), tail = tail_.load();
    if (tail + 1 == head || (!head && tail == kPacketBufferSize - 1)) {
        return false;
    }
    if (++tail >= kPacketBufferSize) {
        tail = 0;
    }
    tail_.store(tail);
    ++tail_move_;
    return true;
}

RtpReceiveThread::RtpReceiveThread(PacketBuffer &pkt_buffer)
        : pkt_buffer_(pkt_buffer), socket_(io_) {
    Start();
}

RtpReceiveThread::~RtpReceiveThread() {
    Stop();
}

void RtpReceiveThread::Start() {
    thread_ = std::thread([&]() {
        // Create the socket so that multiple may be bound to the same address.
        asio::ip::udp::endpoint listen_endpoint(
                asio::ip::address::from_string("0.0.0.0"), 4010);
        socket_.open(listen_endpoint.protocol());
        socket_.set_option(asio::ip::udp::socket::reuse_address(true));
        socket_.bind(listen_endpoint);

        // Join the multicast group.
        socket_.set_option(
                asio::ip::multicast::join_group(
                        asio::ip::address::from_string("224.0.0.56")));

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
            asio::buffer(data_, kRtpPacketSize), sender_endpoint_,
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
    } else if (bytes_recvd != kRtpPacketSize) {
        LOGE("Strange packet %zu", bytes_recvd);
    }
    auto buffer = pkt_buffer_.RefTailForWrite()->data();
    std::memcpy(buffer, data_ + kRtpHeader, bytes_recvd - kRtpHeader);
    if (!pkt_buffer_.NextTail()) {
        LOGE("Packet Buffer Full");
    }

    StartReceive();
}

PulseRtpOboeEngine::PulseRtpOboeEngine(int latency_option)
        : receive_thread_(pkt_buffer_) {
    // Trace::initialize();
    Start(latency_option);
}

PulseRtpOboeEngine::~PulseRtpOboeEngine() {
    Stop();
}

void PulseRtpOboeEngine::Start(int latency_option) {
    oboe::PerformanceMode performanceMode = oboe::PerformanceMode::None;
    switch (latency_option) {
        case 0:
            performanceMode = oboe::PerformanceMode::LowLatency;
            LOGE("FRE %d", latency_option);
            break;
        case 1:
            performanceMode = oboe::PerformanceMode::None;
            LOGE("FRE %d", latency_option);
            break;
        case 2:
            performanceMode = oboe::PerformanceMode::PowerSaving;
            LOGE("FRE %d", latency_option);
            break;
        default:
            LOGE("FRRE %d", latency_option);
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
PulseRtpOboeEngine::onAudioReady(oboe::AudioStream *audioStream, void *audioData, int32_t numFrames) {
    auto *outputData = static_cast<int16_t *>(audioData);
    if (!latencyTuner_) {
        latencyTuner_ = std::make_unique<oboe::LatencyTuner>(*audioStream);
    }
    if (audioStream->getAudioApi() == oboe::AudioApi::AAudio) {
        latencyTuner_->tune();
    }
    auto underrunCountResult = audioStream->getXRunCount();
    int bufferSize = audioStream->getBufferSizeInFrames();
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
    if (!count_) {
        LOGI("numFrames %d, Underruns %d, buffer size %d q:%u/%u %u/%u",
             numFrames, underrunCountResult.value(), bufferSize,
             pkt_buffer_.head_move_req_.load(), pkt_buffer_.head_move_.load(),
             pkt_buffer_.tail_move_req_.load(), pkt_buffer_.tail_move_.load());
    }
    ++count_;
    if (count_ >= 500) {
        count_ = 0;
    }
    return oboe::DataCallbackResult::Continue;
}