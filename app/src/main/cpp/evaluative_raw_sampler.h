#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace evaluative {

inline double clampValue(double value, double low, double high) {
    return value < low ? low : value > high ? high : value;
}

struct Shading {
    int columns = 0, rows = 0;
    double left = 0, top = 0, right = 1, bottom = 1;
    std::vector<float> gains;

    double at(double x, double y) const {
        if (columns < 2 || rows < 2 || right <= left || bottom <= top ||
            gains.size() != static_cast<size_t>(columns) * rows) return 1.0;
        x = clampValue((x - left) / (right - left), 0.0, 1.0) * (columns - 1);
        y = clampValue((y - top) / (bottom - top), 0.0, 1.0) * (rows - 1);
        const int ix = static_cast<int>(x), iy = static_cast<int>(y);
        const int rx = std::min(ix + 1, columns - 1), by = std::min(iy + 1, rows - 1);
        const double fx = x - ix, fy = y - iy;
        const double a = gains[iy * columns + ix] * (1 - fx) + gains[iy * columns + rx] * fx;
        const double b = gains[by * columns + ix] * (1 - fx) + gains[by * columns + rx] * fx;
        return clampValue(a * (1 - fy) + b * fy, 0.5, 4.0);
    }
};

/** Header is platform-independent so the actual native scanner can be tested on the host. */
inline std::vector<double> sample(
    const uint8_t* bytes, int64_t limit, int offset, int width, int height,
    int row_stride, int pixel_stride, int cfa, const std::array<float, 4>& black,
    int white, int left, int top, int right, int bottom,
    const std::array<double, 4>& coefficients, const Shading& shading = {}) {
    constexpr int budget = 32768;
    constexpr double floor = 0.000001;
    if (!bytes || width < 2 || height < 2 || offset < 0 || pixel_stride < 2 ||
        row_stride <= 0 || cfa < 0 || cfa > 3 || white <= 0 ||
        left < 0 || top < 0 || right > width || bottom > height || left >= right || top >= bottom)
        return {};
    for (float b : black) if (!std::isfinite(b) || b < 0 || b >= white) return {};
    for (double c : coefficients) if (!std::isfinite(c)) return {};
    const int64_t last = static_cast<int64_t>(offset) +
        static_cast<int64_t>(bottom - 1) * row_stride + static_cast<int64_t>(right - 1) * pixel_stride;
    if (last + 1 >= limit || static_cast<int64_t>(right) * pixel_stride > row_stride) return {};
    left += left & 1;
    top += top & 1;
    const int columns = (right - left) / 2, rows = (bottom - top) / 2;
    if (columns <= 0 || rows <= 0) return {};
    const int nx = std::min(columns, std::max(1, static_cast<int>(std::sqrt(
        static_cast<double>(budget) * columns / rows))));
    const int ny = std::min(rows, std::max(1, budget / nx));
    // Complete Bayer units, one sample per equal-area stratum, independent of channel ordering.
    static constexpr int channel[4][4] = {{0,1,2,3},{1,0,3,2},{1,3,0,2},{3,1,2,0}};
    std::vector<double> result(3, 0.0);
    result.reserve(3 + nx * ny);
    int clipped = 0, dark = 0;
    for (int sy = 0; sy < ny; ++sy) {
        const int y = top + 2 * static_cast<int>((sy + 0.5) * rows / ny);
        for (int sx = 0; sx < nx; ++sx) {
            const int x = left + 2 * static_cast<int>((sx + 0.5) * columns / nx);
            double luma = 0;
            bool saturated = false;
            for (int dy = 0; dy < 2; ++dy) for (int dx = 0; dx < 2; ++dx) {
                const int position = dy * 2 + dx;
                const int64_t i = static_cast<int64_t>(offset) +
                    static_cast<int64_t>(y + dy) * row_stride + static_cast<int64_t>(x + dx) * pixel_stride;
                const unsigned raw = bytes[i] | (static_cast<unsigned>(bytes[i + 1]) << 8U);
                const double value = clampValue((static_cast<double>(raw) - black[position]) /
                    (static_cast<double>(white) - black[position]), 0.0, 1.0);
                saturated |= value >= 0.985;
                luma += coefficients[channel[cfa][position]] * value;
            }
            luma *= shading.at((x + 1.0) / width, (y + 1.0) / height);
            if (!std::isfinite(luma)) return {};
            clipped += saturated;
            dark += luma <= floor;
            result.push_back(std::max(floor, luma));
        }
    }
    result[2] = result.size() - 3;
    result[0] = clipped / result[2];
    result[1] = dark / result[2];
    return result;
}
} // namespace evaluative
