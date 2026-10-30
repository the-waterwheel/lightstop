// Copyright 2026 lightstop contributors. Licensed under Apache-2.0.
// Standalone synthetic research benchmark; not linked into the Android app.
#include "../../app/src/main/cpp/evaluative_raw_sampler.h"
#include <chrono>
#include <cstring>
#include <iomanip>
#include <iostream>
#include <numeric>
#include <string>

namespace {
constexpr int W = 4000, H = 3000, C = W / 2, R = H / 2;
constexpr int GX = 12, GY = 8, BINS = 1024;
constexpr double MIN_EV = -16.0, BIN_WIDTH = 1.0 / 32.0;
constexpr double BLACK = 64.0, WHITE = 4095.0, FLOOR = 0.000001;
volatile double sink = 0;

double logLut(double x) {
    static const std::array<double, 1025> lut = [] {
        std::array<double, 1025> a{};
        for (size_t i = 0; i < a.size(); ++i) a[i] = std::log2(1.0 + i / 1024.0);
        return a;
    }();
    int exponent = 0;
    double m = std::frexp(x, &exponent) * 2.0;
    double p = (m - 1.0) * 1024.0;
    int i = std::min(1023, std::max(0, static_cast<int>(p)));
    return exponent - 1.0 + lut[i] + (lut[i + 1] - lut[i]) * (p - i);
}

double interval(const std::vector<double>& sorted, double low, double high) {
    const double begin = low * sorted.size(), end = high * sorted.size();
    double sum = 0;
    for (int i = static_cast<int>(begin); i < static_cast<int>(std::ceil(end)); ++i)
        sum += sorted[std::min<int>(i, sorted.size() - 1)] *
            std::max(0.0, std::min(end, i + 1.0) - std::max(begin, double(i)));
    return sum / (end - begin);
}

struct Result { double base = 0, upper = 0, weighted = 0; int samples = 0; double clipped = 0; };
struct Roi { int left, top, right, bottom; };

double readY(const std::vector<uint8_t>& raw, int cx, int cy) {
    const size_t p = (static_cast<size_t>(cy * 2) * W + cx * 2) * 2;
    const auto at = [&](size_t q) { return (raw[q] | (unsigned(raw[q + 1]) << 8)) - BLACK; };
    return std::max(FLOOR, (.21 * at(p) + .36 * at(p + 2) +
        .36 * at(p + W * 2) + .07 * at(p + W * 2 + 2)) / (WHITE - BLACK));
}

Result current(const std::vector<uint8_t>& raw, Roi roi) {
    const auto values = evaluative::sample(raw.data(), raw.size(), 0, W, H, W * 2, 2, 0,
        {64.f, 64.f, 64.f, 64.f}, 4095, roi.left * 2, roi.top * 2,
        roi.right * 2, roi.bottom * 2, {.21, .36, .36, .07});
    std::vector<double> ev;
    ev.reserve(values.size() - 3);
    for (size_t i = 3; i < values.size(); ++i) ev.push_back(8.0 + std::log2(values[i] / .18));
    std::sort(ev.begin(), ev.end());
    return {interval(ev, .05, .95), interval(ev, .98, .995), 0, int(ev.size()), values[0]};
}

double histInterval(const std::array<double, BINS>& hist, double low, double high) {
    const double total = std::accumulate(hist.begin(), hist.end(), 0.0);
    const double begin = total * low, end = total * high;
    double sum = 0, position = 0;
    for (int b = 0; b < BINS; ++b) {
        const double next = position + hist[b];
        const double overlap = std::max(0.0, std::min(end, next) - std::max(begin, position));
        sum += overlap * (MIN_EV + (b + .5) * BIN_WIDTH);
        position = next;
    }
    return sum / (end - begin);
}

Result grid(const std::vector<uint8_t>& raw, Roi roi, int side, bool lut) {
    std::array<double, BINS> hist{};
    double weighted = 0, weights = 0, clippedArea = 0;
    int count = 0;
    for (int gy = 0; gy < GY; ++gy) for (int gx = 0; gx < GX; ++gx) {
        const int x0 = roi.left + gx * (roi.right - roi.left) / GX;
        const int x1 = roi.left + (gx + 1) * (roi.right - roi.left) / GX;
        const int y0 = roi.top + gy * (roi.bottom - roi.top) / GY;
        const int y1 = roi.top + (gy + 1) * (roi.bottom - roi.top) / GY;
        const int nx = std::min(side, x1 - x0), ny = std::min(side, y1 - y0);
        if (nx <= 0 || ny <= 0) continue;
        const double area = double(x1 - x0) * (y1 - y0);
        const double sampleArea = area / (nx * ny);
        double sum = 0;
        for (int sy = 0; sy < ny; ++sy) for (int sx = 0; sx < nx; ++sx) {
            const int cx = x0 + int((sx + .5) * (x1 - x0) / nx);
            const int cy = y0 + int((sy + .5) * (y1 - y0) / ny);
            const double luma = readY(raw, cx, cy);
            if (luma >= .985) clippedArea += sampleArea;
            const double ev = 8.0 + (lut ? logLut(luma / .18) : std::log2(luma / .18));
            sum += ev;
            const int b = std::clamp(int((ev - MIN_EV) / BIN_WIDTH), 0, BINS - 1);
            hist[b] += sampleArea;
            ++count;
        }
        const double dx = (gx + .5) / GX - .5, dy = (gy + .5) / GY - .5;
        // Illustrative center prior, not a calibrated photography policy.
        const double w = area * (1.0 + 2.0 * std::exp(-(dx * dx + dy * dy) / .08));
        weighted += w * sum / (nx * ny);
        weights += w;
    }
    const double totalArea = double(roi.right - roi.left) * (roi.bottom - roi.top);
    return {histInterval(hist, .05, .95), histInterval(hist, .98, .995), weighted / weights,
        count, clippedArea / totalArea};
}

Result dense(const std::vector<uint8_t>& raw, Roi roi) {
    std::vector<double> ev;
    ev.reserve((roi.right - roi.left) * (roi.bottom - roi.top));
    double clipped = 0;
    for (int y = roi.top; y < roi.bottom; ++y) for (int x = roi.left; x < roi.right; ++x) {
        const double luma = readY(raw, x, y);
        clipped += luma >= .985;
        ev.push_back(8.0 + std::log2(luma / .18));
    }
    std::sort(ev.begin(), ev.end());
    return {interval(ev, .05, .95), interval(ev, .98, .995), 0, int(ev.size()), clipped / ev.size()};
}

void fill(std::vector<uint8_t>& raw, int scene) {
    for (int y = 0; y < R; ++y) for (int x = 0; x < C; ++x) {
        double luma = .12 + .07 * x / C + .04 * y / R;
        if (scene == 1 && x >= C * 3 / 4 && x < C * 3 / 4 + 24 && y < R / 2) luma = 1;
        if (scene == 2) luma = ((x % 37) < 2 && (y % 29) < 2) ? 1.0 : .008;
        if (scene == 3) luma = (x & 1) ? .81 : .01;
        if (scene == 4) luma = (x > C / 4 && x < C * 3 / 4 && y > R / 4 && y < R * 3 / 4) ? .025 : .70;
        const unsigned value = unsigned(std::lround(BLACK + luma * (WHITE - BLACK)));
        for (int dy = 0; dy < 2; ++dy) for (int dx = 0; dx < 2; ++dx) {
            const size_t p = (static_cast<size_t>(y * 2 + dy) * W + x * 2 + dx) * 2;
            raw[p] = value & 255; raw[p + 1] = value >> 8;
        }
    }
}

template <typename Fn> void bench(const std::string& name, Fn fn) {
    for (int i = 0; i < 20; ++i) sink += fn().base;
    std::vector<double> times;
    for (int i = 0; i < 160; ++i) {
        const auto start = std::chrono::steady_clock::now();
        const auto r = fn(); sink += r.base + r.upper + r.weighted;
        times.push_back(std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - start).count());
    }
    std::sort(times.begin(), times.end());
    std::cout << "timing," << name << ",p50_ms," << times[80]
        << ",p95_ms," << times[152] << '\n';
}
}

int main() {
    std::cout << std::fixed << std::setprecision(7);
    std::vector<uint8_t> raw(W * H * 2);
    const Roi full{0, 0, C, R}, zoom{C / 4, R / 4, C * 3 / 4, R * 3 / 4};
    fill(raw, 0);
    bench("current_32768", [&] { return current(raw, full); });
    bench("grid_6144_log2", [&] { return grid(raw, full, 8, false); });
    bench("grid_6144_lut", [&] { return grid(raw, full, 8, true); });
    bench("grid_24576_lut", [&] { return grid(raw, full, 16, true); });
    double worst = 0;
    for (int i = 0; i < 500000; ++i) {
        const double x = std::exp2(-20.0 + 23.0 * i / 499999.0);
        worst = std::max(worst, std::abs(logLut(x) - std::log2(x)));
    }
    std::cout << "lut,max_absolute_ev_error," << worst << '\n';
    std::cout << "jensen,mean_of_log2," << (std::log2(.01) + std::log2(.81)) / 2
        << ",log2_of_mean," << std::log2((.01 + .81) / 2) << '\n';
    const char* names[] = {"gradient", "clipped_window_0.6pct", "night_pinlights", "one_cell_stripes", "backlit_subject"};
    for (int scene = 0; scene < 5; ++scene) {
        fill(raw, scene);
        const auto truth = dense(raw, full), old = current(raw, full);
        const auto a = grid(raw, full, 8, false), b = grid(raw, full, 16, true);
        std::cout << "scene," << names[scene] << ",dense_B," << truth.base << ",dense_U," << truth.upper
            << ",current_B_error," << old.base - truth.base << ",current_U_error," << old.upper - truth.upper
            << ",grid6144_B_error," << a.base - truth.base << ",grid6144_U_error," << a.upper - truth.upper
            << ",grid24576_B_error," << b.base - truth.base << ",grid24576_U_error," << b.upper - truth.upper
            << ",dense_clipped_fraction," << truth.clipped << ",current_clipped_fraction," << old.clipped
            << ",grid6144_clipped_fraction," << a.clipped << ",grid24576_clipped_fraction," << b.clipped
            << ",illustrative_center_ev," << a.weighted << '\n';
    }
    // Crop invariance: changing pixels strictly outside selected ROI must not change results.
    fill(raw, 0);
    const auto before = grid(raw, zoom, 8, false);
    for (int y = 0; y < R; ++y) for (int x = 0; x < C; ++x) {
        if (x >= zoom.left && x < zoom.right && y >= zoom.top && y < zoom.bottom) continue;
        for (int dy = 0; dy < 2; ++dy) for (int dx = 0; dx < 2; ++dx) {
            const size_t p = (static_cast<size_t>(y * 2 + dy) * W + x * 2 + dx) * 2;
            raw[p] = 255; raw[p + 1] = 15;
        }
    }
    const auto after = grid(raw, zoom, 8, false);
    const double error = std::max({std::abs(before.base - after.base),
        std::abs(before.upper - after.upper), std::abs(before.weighted - after.weighted)});
    std::cout << "crop,outside_roi_change_error_ev," << error << '\n';
    if (error != 0 || worst > 0.000001) return 2;
    return 0;
}
