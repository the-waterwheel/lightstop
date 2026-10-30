#include "../../main/cpp/evaluative_raw_sampler.h"
#include <cassert>
#include <iostream>

int main() {
    constexpr int width = 400, height = 300, offset = 17, stride = width * 4 + 16;
    std::vector<uint8_t> bytes(offset + stride * height, 0);
    const std::array<float, 4> black = {10, 20, 30, 40};
    const std::array<double, 4> coefficients = {.21, .36, .36, .07};
    static constexpr int channel[4][4] = {{0,1,2,3},{1,0,3,2},{1,3,0,2},{3,1,2,0}};
    const double colors[4] = {.2, .4, .6, .8};
    auto write = [&](int x, int y, unsigned v) {
        const auto i = offset + y * stride + x * 4;
        bytes[i] = v & 255; bytes[i + 1] = v >> 8;
    };
    auto run = [&](int cfa, int left = 0, int right = -1, int limit = -1) {
        if (right < 0) right = width;
        return evaluative::sample(bytes.data(), limit < 0 ? bytes.size() : limit, offset,
            width, height, stride, 4, cfa, black, 1040, left, 0, right, height, coefficients);
    };
    for (int cfa = 0; cfa < 4; ++cfa) {
        for (int y = 0; y < height; ++y) for (int x = 0; x < width; ++x) {
            const int p = (y & 1) * 2 + (x & 1);
            write(x, y, std::lround(black[p] + colors[channel[cfa][p]] * (1040 - black[p])));
        }
        const auto samples = run(cfa);
        assert(samples.size() > 19 && samples.size() <= 32768 + 3);
        assert(samples[0] == 0 && samples[1] == 0);
        for (size_t i = 3; i < samples.size(); ++i) assert(std::abs(samples[i] - .458) < .001);
        const auto rgb = evaluative::sampleRgb(bytes.data(), bytes.size(), offset, width, height, stride, 4,
            cfa, black, 1040, 0, 0, width, height, 0, false);
        assert(rgb.size() == 4 * 6144);
        for (int c = 0; c < 4; ++c) for (int i = 0; i < 6144; ++i)
            assert(std::abs(rgb[c * 6144 + i] - colors[c]) < .001);
    }
    // Bright material outside the chosen crop cannot influence the measurement.
    for (int y = 0; y < height; ++y) for (int x = 0; x < width; ++x) {
        const int p = (y & 1) * 2 + (x & 1);
        write(x, y, x < width / 2 ? 1040 : std::lround(black[p] + .18 * (1040 - black[p])));
    }
    assert(run(0)[0] > .49);
    const auto cropped = run(0, width / 2, width);
    assert(cropped[0] == 0);
    assert(std::abs(cropped[3] - .18) < .001);
    for (int rotation : {0,90,180,270}) for (bool mirror : {false,true}) {
        const auto rgb = evaluative::sampleRgb(bytes.data(), bytes.size(), offset, width, height, stride, 4,
            0, black, 1040, width/2+1, 3, width-1, height-1, rotation, mirror);
        assert(rgb.size() == 4 * 6144);
        for (float value : rgb) assert(std::abs(value-.18) < .001);
    }
    assert(evaluative::sampleRgb(bytes.data(), 100, offset, width, height, stride, 4,
        0, black, 1040, 0,0,width,height,0,false).empty());
    assert(evaluative::sampleRgb(bytes.data(), bytes.size(), offset, width, height, stride, 4,
        0, black, 1040, 0,0,width,height,45,false).empty());
    assert(run(0, 0, width, 100).empty());
    assert(run(4).empty());
    // Spatial values make rotations/mirroring observable, rather than testing a uniform frame only.
    for (int y = 0; y < height; ++y) for (int x = 0; x < width; ++x) {
        const int p = (y & 1) * 2 + (x & 1);
        const double value = .05 + .5 * x / width + .2 * y / height;
        write(x, y, std::lround(black[p] + value * (1040 - black[p])));
    }
    const double expected[4] = {.053833333, .251166667, .742333333, .545};
    for (int turn = 0; turn < 4; ++turn) {
        const auto rgb = evaluative::sampleRgb(bytes.data(), bytes.size(), offset, width, height, stride, 4,
            0, black, 1040, 0,0,width,height,turn*90,false);
        assert(std::abs(rgb[0] - expected[turn]) < .001);
    }
    const auto mirrored = evaluative::sampleRgb(bytes.data(), bytes.size(), offset, width, height, stride, 4,
        0, black, 1040, 0,0,width,height,0,true);
    assert(std::abs(mirrored[0] - .546333333) < .001);
    // Keep dark areas represented and flagged, and test per-position shading.
    std::fill(bytes.begin(), bytes.end(), 0);
    assert(run(0)[1] == 1);
    evaluative::Shading shading;
    shading.columns = shading.rows = 2;
    shading.gains = {1, 2, 3, 4};
    assert(std::abs(shading.at(.5, .5) - 2.5) < 1e-10);
    std::cout << "Native evaluation sampler: CFA, offsets, strides, bounds, crop, clipping, dark area, budget and shading passed\n";
}
