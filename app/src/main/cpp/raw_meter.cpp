#include <jni.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <vector>

namespace {

int channel_for_position(int cfa, int x, int y) {
    const int position = ((y & 1) << 1) | (x & 1);
    // Channel order is R, G1, G2, B. G1/G2 retain their sensor positions.
    switch (cfa) {
        case 0: { // RGGB
            static constexpr int map[4] = {0, 1, 2, 3};
            return map[position];
        }
        case 1: { // GRBG
            static constexpr int map[4] = {1, 0, 3, 2};
            return map[position];
        }
        case 2: { // GBRG
            static constexpr int map[4] = {1, 3, 0, 2};
            return map[position];
        }
        case 3: { // BGGR
            static constexpr int map[4] = {3, 1, 2, 0};
            return map[position];
        }
        default:
            return position;
    }
}

double median(std::vector<double>& values) {
    if (values.empty()) return 0.0;
    const size_t middle = values.size() / 2;
    std::nth_element(values.begin(), values.begin() + middle, values.end());
    double result = values[middle];
    if ((values.size() & 1U) == 0U) {
        const auto lower = std::max_element(values.begin(), values.begin() + middle);
        result = (result + *lower) * 0.5;
    }
    return result;
}

} // namespace

extern "C"
JNIEXPORT jdoubleArray JNICALL
Java_com_lightmeter_rawmeter_RawMeterBridge_analyzeRaw(
        JNIEnv* env,
        jobject,
        jobject buffer,
        jint buffer_offset,
        jint width,
        jint height,
        jint row_stride,
        jint pixel_stride,
        jint cfa,
        jfloatArray black_levels_array,
        jint white_level,
        jint roi_left,
        jint roi_top,
        jint roi_width,
        jint roi_height) {
    auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    jdoubleArray output = env->NewDoubleArray(6);
    std::array<jdouble, 6> result{0.0, 0.0, 0.0, 0.0, 0.0, 0.0};
    if (base == nullptr || capacity <= 0 || pixel_stride < 2 || row_stride <= 0 ||
        white_level <= 0) {
        env->SetDoubleArrayRegion(output, 0, result.size(), result.data());
        return output;
    }

    std::array<float, 4> black{0.f, 0.f, 0.f, 0.f};
    if (black_levels_array != nullptr && env->GetArrayLength(black_levels_array) >= 4) {
        env->GetFloatArrayRegion(black_levels_array, 0, 4, black.data());
    }

    const int left = std::max(0, roi_left);
    const int top = std::max(0, roi_top);
    const int right = std::min(static_cast<int>(width), left + std::max(0, roi_width));
    const int bottom = std::min(static_cast<int>(height), top + std::max(0, roi_height));

    std::array<std::vector<double>, 4> samples;
    const int expected = std::max(1, (right - left) * (bottom - top) / 4);
    for (auto& channel : samples) channel.reserve(expected);

    int clipped = 0;
    int valid = 0;
    for (int y = top; y < bottom; ++y) {
        for (int x = left; x < right; ++x) {
            const int64_t offset =
                    static_cast<int64_t>(buffer_offset) +
                    static_cast<int64_t>(y) * row_stride +
                    static_cast<int64_t>(x) * pixel_stride;
            if (offset < 0 || offset + 1 >= capacity) continue;
            const uint16_t raw =
                    static_cast<uint16_t>(base[offset]) |
                    (static_cast<uint16_t>(base[offset + 1]) << 8U);
            const int position = ((y & 1) << 1) | (x & 1);
            const int channel = channel_for_position(cfa, x, y);
            const double black_value = black[position];
            const double denominator =
                    std::max(1.0, static_cast<double>(white_level) - black_value);
            const double normalized =
                    std::clamp((static_cast<double>(raw) - black_value) / denominator, 0.0, 1.0);
            samples[channel].push_back(normalized);
            if (normalized >= 0.985) ++clipped;
            ++valid;
        }
    }

    for (int channel = 0; channel < 4; ++channel) {
        result[channel] = median(samples[channel]);
    }
    result[4] = valid > 0 ? static_cast<double>(clipped) / valid : 0.0;
    result[5] = valid;
    env->SetDoubleArrayRegion(output, 0, result.size(), result.data());
    return output;
}
