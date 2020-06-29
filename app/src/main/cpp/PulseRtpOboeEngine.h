//
// Created by wenxin on 20-6-14.
//

#ifndef PULSERTP_OBOEENGINE_H
#define PULSERTP_OBOEENGINE_H

#include <cstdint>
#include <vector>
#include <atomic>
#include <thread>
#include <asio.hpp>
#include <oboe/Oboe.h>

#define MODULE_NAME "PULSE_RTP_OBOE_ENGINE"

// MTU: 1280, channel 2, sample se16e -> 320 sample per pkt
// 48k sample per s -> 150 pkt/s
// 100ms buffer: 15pkt
// RTP payload: 1280 + 12 = 1292

class PacketBuffer {
public:
    PacketBuffer(unsigned mtu, unsigned sample_rate, unsigned max_latency);
    const std::vector<int16_t>* RefNextHeadForRead();
    std::vector<int16_t>* RefTailForWrite();
    bool NextTail();

    unsigned capacity() const { return pkts_.size(); }
    unsigned size() const { return size_; }
    unsigned head_move_req() const { return head_move_req_; }
    unsigned head_move() const { return head_move_; }
    unsigned tail_move_req() const { return tail_move_req_; }
    unsigned tail_move() const { return tail_move_; }
private:
    std::vector<std::vector<int16_t>> pkts_;
    std::atomic<unsigned> head_;
    std::atomic<unsigned> tail_;
    std::atomic<unsigned> size_;

    std::atomic<unsigned> head_move_req_;
    std::atomic<unsigned> head_move_;
    std::atomic<unsigned> tail_move_req_;
    std::atomic<unsigned> tail_move_;
};

class RtpReceiveThread {
public:
    RtpReceiveThread(PacketBuffer& pkt_buffer, const std::string& ip, uint16_t port, int mtu);
    ~RtpReceiveThread();
private:
    void Start(const std::string& ip, uint16_t port, int mtu);
    void Stop();
    void StartReceive();
    void HandleReceive(size_t bytes_recvd);
    PacketBuffer& pkt_buffer_;
    asio::io_context io_;
    asio::ip::udp::socket socket_;
    asio::ip::udp::endpoint sender_endpoint_;
    std::vector<char> data_;
    std::thread thread_;
};

class PulseRtpOboeEngine
: public oboe::AudioStreamCallback {
public:
    PulseRtpOboeEngine(int latency_option, const std::string& ip, uint16_t port, unsigned mtu,
                       unsigned max_latency);
    ~PulseRtpOboeEngine();

    int num_underrun() const { return num_underrun_; }
    int audio_buffer_size() const { return audio_buffer_size_; }
    unsigned pkt_buffer_capacity() const { return pkt_buffer_.capacity(); }
    unsigned pkt_buffer_size() const { return pkt_buffer_.size(); }
    unsigned pkt_buffer_head_move_req() const { return pkt_buffer_.head_move_req(); }
    unsigned pkt_buffer_head_move() const { return pkt_buffer_.head_move(); }
    unsigned pkt_buffer_tail_move_req() const { return pkt_buffer_.tail_move_req(); }
    unsigned pkt_buffer_tail_move() const { return pkt_buffer_.tail_move(); }

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
    void Start(int latency_option, const std::string& ip, uint16_t port, unsigned mtu);
    void Stop();
    bool EnsureBuffer();
    PacketBuffer pkt_buffer_;
    RtpReceiveThread receive_thread_;
    oboe::ManagedStream managedStream_;
    std::unique_ptr<oboe::LatencyTuner> latencyTuner_;
    const std::vector<int16_t>* buffer_ = nullptr;
    std::vector<int16_t> last_samples_;
    unsigned offset_ = 0;
    enum State {
        None,
        Overrun,
        Underrun,
    } state_ = State::None;
    bool is_thread_affinity_set_ = false;

    std::atomic<int> num_underrun_;
    std::atomic<int> audio_buffer_size_;
};

#endif //PULSERTP_OBOEENGINE_H
