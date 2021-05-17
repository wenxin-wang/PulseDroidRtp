/*
 * Copyright 2020 Wenxin Wang
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "PulseRtpOboeEngine.h"
#include <android/log.h>
#include <logging_macros.h>
#include <trace.h>
#include <chrono>
#include <utility>

namespace {
    const unsigned kRtpHeader = 12;
    // static const unsigned kNumChannel = 2;
    const unsigned kSampleSize = 2;
    // static const unsigned kSampleRate = 48000;
    // static const unsigned kMaxLatency = 200;
    const unsigned kIdleRecvMs = 10000;
}

PacketBuffer::PacketBuffer(
        unsigned mtu, unsigned sample_rate, unsigned max_latency, unsigned num_channel)
        : head_(0)
        , tail_(0)
        , size_(0)
        , head_move_req_(0)
        , head_move_(0)
        , tail_move_req_(0)
        , tail_move_(0) {
    const unsigned num_buffer = (1 + sample_rate * max_latency / 1000 /
                                     (mtu / num_channel / kSampleSize));
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
                                   std::string ip, uint16_t port, unsigned mtu)
        : pkt_buffer_(pkt_buffer)
        , ip_(std::move(ip))
        , port_(port)
        , socket_(io_)
        , data_(kRtpHeader + mtu)
        , idle_check_timer_(io_) {
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

bool RtpReceiveThread::Start() {
    asio::ip::address::from_string(ip_);
    std::mutex start_mutex;
    std::condition_variable start_cv;
    int start_success = 0;

    thread_ = std::thread([this, &start_mutex, &start_cv, &start_success]() {
        setThreadAffinity();
        bool has_error = false;
        try {
            Restart();
        } catch (asio::system_error& e) {
            LOGE("Failed to start receive thread, %s", e.what());
            has_error = true;
        }
        {
            std::unique_lock<std::mutex> lk(start_mutex);
            start_success = has_error ? 2 : 1;
            start_cv.notify_all();
        }
        LOGI("Start Receiving");
        io_.run();
        LOGI("Stop Receiving");
    });
    std::unique_lock<std::mutex> lk(start_mutex);
    start_cv.wait(lk, [&] { return start_success != 0; });
    return start_success == 1;
}

void RtpReceiveThread::Stop() {
    io_.stop();
    if (thread_.joinable()) {
        thread_.join();
    }
}

void RtpReceiveThread::Restart() {
    LOGE("Restart");
    is_idle_ = false;
    socket_.close();
    socket_ = asio::ip::udp::socket(io_);
    auto local_address = asio::ip::address::from_string(ip_);
    bool is_mcast = local_address.is_multicast();
    auto listen_address = local_address;
    if (is_mcast) {
        if (local_address.is_v4()) {
            listen_address = asio::ip::address::from_string("0.0.0.0");
        } else if (local_address.is_v6()) {
            listen_address = asio::ip::address::from_string("::");
        }
    }
    LOGI("Listening on %s %s:%u", ip_.c_str(), listen_address.to_string().c_str(), port_);
    // Create the socket so that multiple may be bound to the same address.
    asio::ip::udp::endpoint listen_endpoint(listen_address, port_);
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
    idle_check_timer_.expires_from_now(std::chrono::milliseconds(kIdleRecvMs));
    idle_check_timer_.async_wait([&](const asio::error_code &error) {
        if (error) {
            return;
        }
        is_idle_ = true;
        LOGE("Is Idle Now");
    });
}

void RtpReceiveThread::HandleReceive(size_t bytes_recvd) {
    pkt_recved_++;
    if (bytes_recvd <= kRtpHeader) {
        LOGE("Packet Too Small");
        StartReceive();
        return;
    } else if (bytes_recvd != data_.size()) {
        LOGE("Strange packet %zu", bytes_recvd);
    }
    auto vec = pkt_buffer_.RefTailForWrite();
    vec->resize((bytes_recvd - kRtpHeader) / kSampleSize);
    auto buffer = vec->data();
    std::memcpy(buffer, data_.data() + kRtpHeader, bytes_recvd - kRtpHeader);
    if (!pkt_buffer_.NextTail()) {
        // LOGE("Packet Buffer Full");
    }

    if (is_idle_) {
        Restart();
    } else {
        StartReceive();
    }
}

std::unique_ptr<PulseRtpOboeEngine> PulseRtpOboeEngine::Create(
        int latency_option, const std::string &ip, uint16_t port, unsigned mtu,
        unsigned max_latency, unsigned num_channel, unsigned mask_channel) {
    auto engine = std::unique_ptr<PulseRtpOboeEngine>(new PulseRtpOboeEngine(
            ip, port, mtu, max_latency, num_channel, mask_channel));
    if (engine && !engine->Start(latency_option, ip, port, mtu)) {
        return nullptr;
    }
    return engine;
}

PulseRtpOboeEngine::PulseRtpOboeEngine(const std::string &ip,
                                       uint16_t port,
                                       unsigned mtu,
                                       unsigned max_latency,
                                       unsigned num_channel,
                                       unsigned mask_channel)
        : pkt_buffer_(mtu, oboe::DefaultStreamValues::SampleRate, max_latency, num_channel)
        , receive_thread_(pkt_buffer_, ip, port, mtu)
        , num_channel_(num_channel)
        , mask_channel_(mask_channel & ((1U << num_channel) - 1))
        , last_samples_(num_channel)
        , num_underrun_(0)
        , audio_buffer_size_(0) {
    // Trace::initialize();
    if (!mask_channel_) {
        mask_channel_ = (1U << num_channel) - 1;
    }
    num_output_channel_ = 0;
    mask_channel = mask_channel_;
    while(mask_channel) {
        if (mask_channel & 1U) {
            ++num_output_channel_;
        }
        mask_channel >>= 1U;
    }
}

PulseRtpOboeEngine::~PulseRtpOboeEngine() {
    Stop();
}

bool
PulseRtpOboeEngine::Start(int latency_option, const std::string &ip, uint16_t port, unsigned mtu) {
    if (!receive_thread_.Start()) {
        LOGE("Failed to start receive thread");
        return false;
    }
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
    builder.setUsage(oboe::Usage::Media);
    builder.setContentType(oboe::ContentType::Music);
    builder.setPerformanceMode(performanceMode);
    builder.setSharingMode(oboe::SharingMode::Exclusive);
    builder.setFormat(oboe::AudioFormat::I16);
    builder.setChannelCount(int(num_output_channel_));
    // Always use default sample rate
    // builder.setSampleRate(48000);
    builder.setCallback(this);
    oboe::Result result = builder.openManagedStream(managedStream_);
    if (result != oboe::Result::OK) {
        LOGE("Failed to create stream. Error: %s",
             oboe::convertToText(result));
        return false;
    }
    LOGI("Open stream, c:%d s:%d p:%d b:%d",
         getBufferCapacityInFrames(), getSharingMode(),
         getPerformanceMode(), getFramesPerBurst());

    result = managedStream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start stream. Error: %s",
             oboe::convertToText(result));
        return false;
    }
    return true;
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

    auto old_state = state_;
    if (state_ == State::None) {
        auto num_pkt = pkt_buffer_size();
        if (num_pkt < pkt_buffer_capacity() / 32) {
            state_ = State::Depleted;
        } else if (num_pkt < pkt_buffer_capacity() / 16) {
            state_ = State::Underrun;
        } else if (num_pkt > pkt_buffer_capacity() / 2) {
            state_ = State::Overrun;
        }
        if (state_ != old_state) {
            LOGE("Enter state %u -> %u %u", unsigned(old_state), unsigned(state_), num_output_channel_);
        }
    }
    old_state = state_;
    bool has_adjustment_ = false;
    for (int i = 0; i < numFrames; ++i) {
        unsigned mask_channel = mask_channel_;
        unsigned k = 0;
        for (int j = 0; j < num_channel_; ++j) {
            if (state_ == State::Depleted || !EnsureBuffer()) {
                state_ = State::Depleted;
                // LOGE("No more data: %zu/%d", num_sample, numFrames);
            } else {
                last_samples_[j] = ntohs((*buffer_)[offset_]);
                ++offset_;
            }
            if (mask_channel & 1U) {
                // only fill channel selected by mask
                outputData[i * num_output_channel_ + k] = last_samples_[j];
                ++k;
            }
            mask_channel >>= 1U;
        }
        if (state_ != State::None) {
            auto num_pkt = pkt_buffer_size();
            if (num_pkt < pkt_buffer_capacity() / 32) {
                state_ = State::Depleted;
            } else if (num_pkt < pkt_buffer_capacity() / 8) {
                if (state_ != State::Depleted) {
                    state_ = State::Underrun;
                }
            } else if (num_pkt > pkt_buffer_capacity() / 4) {
                state_ = State::Overrun;
            } else {
                state_ = State::None;
            }
            if (!has_adjustment_) {
                has_adjustment_ = true;
                if (state_ == State::Overrun) {
                    // skip one sample
                    // LOGE("OVERRUN %u/%u", num_pkt, pkt_buffer_capacity());
                    offset_ += num_channel_;
                } else if (state_ == State::Underrun) {
                    // repeat one sample
                    // LOGE("UNDERRUN %u/%u", num_pkt, pkt_buffer_capacity());
                    offset_ -= num_channel_;
                }
            }
        }
        if (state_ != old_state) {
            LOGE("Change state1 %u -> %u", unsigned(old_state), unsigned(state_));
            old_state = state_;
        }
    }

    // if (Trace::isEnabled()) Trace::endSection();
    return oboe::DataCallbackResult::Continue;
}
