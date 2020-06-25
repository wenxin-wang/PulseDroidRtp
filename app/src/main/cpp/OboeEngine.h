//
// Created by wenxin on 20-6-14.
//

#ifndef REVIVEXIAOXUN_OBOEENGINE_H
#define REVIVEXIAOXUN_OBOEENGINE_H

#include <cstdint>
#include <vector>
#include <atomic>
#include <thread>
#include <asio.hpp>
#include <oboe/Oboe.h>

#define MODULE_NAME "REVIVE_XIAOXUN"

// MTU: 1280, channel 2, sample se16e -> 320 sample per pkt
// 48k sample per s -> 150 pkt/s
// 100ms buffer: 15pkt
// RTP payload: 1280 + 12 = 1292

const unsigned kRtpHeader = 12;
const unsigned kRtpMtu = 1280;
const unsigned kRtpPacketSize = kRtpHeader + kRtpMtu;
const unsigned kNumChannel = 2;
const unsigned kSampleSize = 2;
const unsigned kSampleRate = 48000;
const unsigned kMaxLatency = 500;
const unsigned kPacketBufferSize = (1 + kSampleRate * kMaxLatency / 1000 / (kRtpMtu / kNumChannel / kSampleSize));

class PacketBuffer {
public:
    PacketBuffer();
    const std::vector<int16_t>* RefNextHeadForRead();
    std::vector<int16_t>* RefNextTailForWrite();
private:
    std::vector<std::vector<int16_t>> pkts_;
    std::atomic<unsigned> head_;
    std::atomic<unsigned> tail_;
};

class RtpReceiveThread {
public:
    RtpReceiveThread(PacketBuffer& pkt_buffer);
    ~RtpReceiveThread();
private:
    void Start();
    void Stop();
    void StartReceive();
    void HandleReceive(size_t bytes_recvd);
    PacketBuffer& pkt_buffer_;
    asio::io_context io_;
    asio::ip::udp::socket socket_;
    asio::ip::udp::endpoint sender_endpoint_;
    char data_[kRtpPacketSize];
    std::thread thread_;
};

class OboeEngine
: public oboe::AudioStreamCallback {
public:
    OboeEngine();
    ~OboeEngine();

    int32_t getBufferCapacityInFrames() const {
        return managedStream_->getBufferSizeInFrames();
    }

    int getSharingMode() const {
        return (int)managedStream_->getSharingMode();
    }

    int getPerformanceMode() const {
        return (int)managedStream_->getPerformanceMode();
    }

    int32_t getFramesPerBurst() const {
        return managedStream_->getFramesPerBurst();
    }

    oboe::DataCallbackResult
    onAudioReady(oboe::AudioStream *audioStream, void *audioData, int32_t numFrames) override;
private:
    void Start();
    void Stop();
    bool EnsureBuffer();
    PacketBuffer pkt_buffer_;
    RtpReceiveThread receive_thread_;
    oboe::ManagedStream managedStream_;
    const std::vector<int16_t>* buffer_ = nullptr;
    unsigned offset_ = 0;
};

#endif //REVIVEXIAOXUN_OBOEENGINE_H
