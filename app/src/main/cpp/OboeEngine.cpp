//
// Created by wenxin on 20-6-14.
//

#include "OboeEngine.h"
#include <android/log.h>

PacketBuffer::PacketBuffer()
        : head_(0), tail_(0) {
    pkts_.reserve(kPacketBufferSize);
    for (unsigned i = 0; i < kPacketBufferSize; ++i) {
        pkts_.emplace_back(kRtpMtu / kSampleSize, 0);
    }
}

const std::vector<int16_t> *PacketBuffer::RefNextHeadForRead() {
    auto head = head_.load(), tail = tail_.load();
    if (head == tail) {
        return nullptr;
    }
    if (++head >= kPacketBufferSize) {
        head = 0;
    }
    head_.store(head);
    return &pkts_[head];
}

std::vector<int16_t> *PacketBuffer::RefNextTailForWrite() {
    auto head = head_.load(), tail = tail_.load();
    if (tail + 1 == head || (!head && tail == kPacketBufferSize - 1)) {
        return nullptr;
    }
    if (++tail >= kPacketBufferSize) {
        tail = 0;
    }
    tail_.store(tail);
    return &pkts_[tail];
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
        __android_log_print(ANDROID_LOG_INFO, MODULE_NAME, "Start Receiving");
        io_.run();
        __android_log_print(ANDROID_LOG_INFO, MODULE_NAME, "Stop Receiving");
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
                    __android_log_print(ANDROID_LOG_ERROR, MODULE_NAME, "Long packet");
                }
                HandleReceive(bytes_recvd);
            });
}

void RtpReceiveThread::HandleReceive(size_t bytes_recvd) {
    std::vector<int16_t> *buffer = nullptr;
    if (bytes_recvd <= kRtpHeader) {
        __android_log_print(ANDROID_LOG_ERROR, MODULE_NAME, "Packet Too Small");
        goto start_recv;
    } else if (bytes_recvd != kRtpPacketSize) {
        __android_log_print(ANDROID_LOG_ERROR, MODULE_NAME, "Strange packet %zu", bytes_recvd);
    }
    buffer = pkt_buffer_.RefNextTailForWrite();
    if (!buffer) {
        __android_log_print(ANDROID_LOG_ERROR, MODULE_NAME, "Packet Buffer Full");
        goto start_recv;
    }
    std::memcpy(buffer->data(), data_ + kRtpHeader, bytes_recvd - kRtpHeader);

    start_recv:
    StartReceive();
}

OboeEngine::OboeEngine()
        : receive_thread_(pkt_buffer_) {
    Start();
}

OboeEngine::~OboeEngine() {
    Stop();
}

void OboeEngine::Start() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output);
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    builder.setSharingMode(oboe::SharingMode::Exclusive);
    builder.setFormat(oboe::AudioFormat::I16);
    builder.setChannelCount(oboe::ChannelCount::Stereo);
    builder.setSampleRate(48000);
    builder.setCallback(this);
    oboe::Result result = builder.openManagedStream(managedStream_);
    if (result != oboe::Result::OK) {
        __android_log_print(ANDROID_LOG_ERROR, MODULE_NAME, "Failed to create stream. Error: %s",
                            oboe::convertToText(result));
    }
    __android_log_print(ANDROID_LOG_INFO, MODULE_NAME, "Open stream, c:%d s:%d p:%d b:%d",
                        getBufferCapacityInFrames(), getSharingMode(),
                        getPerformanceMode(), getFramesPerBurst());

    managedStream_->requestStart();
}

void OboeEngine::Stop() {
    managedStream_->stop(); // timeout for 2s
}

bool OboeEngine::EnsureBuffer() {
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
OboeEngine::onAudioReady(oboe::AudioStream *audioStream, void *audioData, int32_t numFrames) {
    auto *outputData = static_cast<int16_t *>(audioData);

    size_t num_sample = 0;
    for (int i = 0; i < numFrames; ++i) {
        for (int j = 0; j < kNumChannel; ++j) {
            if (!EnsureBuffer()) {
                __android_log_print(ANDROID_LOG_ERROR, MODULE_NAME,
                                    "No more data: %zu/%d", num_sample, numFrames);
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
        std::string logstr =
                "Fill with empty: " + std::to_string(num_sample) + "/" + std::to_string(numFrames);
        __android_log_print(ANDROID_LOG_ERROR, MODULE_NAME,
                            "Fill with empty: %zu/%d", num_sample, numFrames);
    }

    return oboe::DataCallbackResult::Continue;
}