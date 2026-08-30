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

float median(std::vector<float>& values) {
    if (values.empty()) return 0.0;
    const size_t middle = values.size() / 2;
    std::nth_element(values.begin(), values.begin() + middle, values.end());
    float result = values[middle];
    if ((values.size() & 1U) == 0U) {
        const auto lower = std::max_element(values.begin(), values.begin() + middle);
        result = (result + *lower) * 0.5;
    }
    return result;
}

// A median does not become meaningfully more stable after tens of thousands of samples, while
// retaining every pixel in a large center-weighted ROI can consume tens of megabytes. Sampling
// complete 2x2 Bayer cells preserves equal representation of R/G1/G2/B and caps scratch memory.
constexpr int kMaxSamplesPerChannel = 65'536;

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
        cfa < 0 || cfa > 3 ||
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

    const int first_x = left + (left & 1);
    const int first_y = top + (top & 1);
    const int cell_columns = std::max(0, (right - first_x) / 2);
    const int cell_rows = std::max(0, (bottom - first_y) / 2);
    const int64_t total_cells = static_cast<int64_t>(cell_columns) * cell_rows;
    const double sampling_ratio = total_cells > 0
            ? static_cast<double>(total_cells) / kMaxSamplesPerChannel
            : 1.0;
    const int cell_step = std::max(1, static_cast<int>(std::ceil(std::sqrt(sampling_ratio))));
    const int expected = std::max(
            1,
            static_cast<int>(std::min<int64_t>(
                    kMaxSamplesPerChannel,
                    (total_cells + cell_step * cell_step - 1) / (cell_step * cell_step))));
    std::array<std::vector<float>, 4> samples;
    for (auto& channel : samples) channel.reserve(expected);

    int clipped = 0;
    int valid = 0;
    const int pixel_step = cell_step * 2;
    for (int cell_y = first_y; cell_y + 1 < bottom; cell_y += pixel_step) {
        for (int cell_x = first_x; cell_x + 1 < right; cell_x += pixel_step) {
            for (int dy = 0; dy < 2; ++dy) {
                for (int dx = 0; dx < 2; ++dx) {
                    const int x = cell_x + dx;
                    const int y = cell_y + dy;
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
                    const float normalized = static_cast<float>(std::clamp(
                            (static_cast<double>(raw) - black_value) / denominator,
                            0.0,
                            1.0));
                    samples[channel].push_back(normalized);
                    if (normalized >= 0.985f) ++clipped;
                    ++valid;
                }
            }
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
