// Standalone Android benchmark; it does not change or launch the application.
// Run the same executable/input against baseline and candidate OpenCV runtimes.
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/video/tracking.hpp>
#include <opencv2/features2d.hpp>
#include <opencv2/calib3d.hpp>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <numeric>
#include <vector>

using Clock = std::chrono::steady_clock;

double milliseconds(Clock::time_point start) {
    return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
}

void report(const char* name, std::vector<double> values) {
    std::sort(values.begin(), values.end());
    const double mean = std::accumulate(values.begin(), values.end(), 0.0) / values.size();
    std::printf("\"%s\":{\"mean_ms\":%.4f,\"median_ms\":%.4f,\"p95_ms\":%.4f}",
        name, mean, values[values.size() / 2], values[static_cast<size_t>(std::ceil(values.size() * .95)) - 1]);
}

int main(int argc, char** argv) {
    const int samples = argc > 1 ? std::atoi(argv[1]) : 120;
    const int featureCount = argc > 2 ? std::atoi(argv[2]) : 160;
    if (samples < 20 || featureCount < 8) return 2;
    cv::Mat source(768, 1024, CV_8UC1);
    cv::RNG random(0x42F17);
    random.fill(source, cv::RNG::UNIFORM, 0, 256);
    cv::GaussianBlur(source, source, cv::Size(5, 5), 1.0);
    cv::Mat previous, current;
    cv::resize(source, previous, cv::Size(512, 384));
    const cv::Mat shift = (cv::Mat_<double>(2, 3) << 1, 0, 2, 0, 1, -1);
    cv::warpAffine(previous, current, shift, previous.size(), cv::INTER_LINEAR, cv::BORDER_REFLECT);
    auto orb = cv::ORB::create(700);
    auto matcher = cv::BFMatcher::create(cv::NORM_HAMMING);
    std::vector<cv::KeyPoint> referenceKeys;
    cv::Mat referenceDescriptors;
    orb->detectAndCompute(previous, cv::noArray(), referenceKeys, referenceDescriptors);
    std::vector<double> preprocessTimes, flowTimes, reentryTimes;
    double maxTranslationError = 0;
    int minimumTracked = featureCount;
    int minimumDescriptors = 100000;
    for (int iteration = -20; iteration < samples; ++iteration) {
        const auto preprocessStart = Clock::now();
        cv::Mat resized;
        cv::resize(source, resized, previous.size());
        const auto mean = cv::mean(resized);
        if (mean[0] <= 0) return 3;
        const double preprocessMs = milliseconds(preprocessStart);
        const auto flowStart = Clock::now();
        std::vector<cv::Point2f> points, forward, backward;
        cv::goodFeaturesToTrack(previous, points, featureCount, .012, 9.0);
        forward = points;
        for (auto& point : forward) point += cv::Point2f(2, -1);
        std::vector<unsigned char> forwardStatus, backwardStatus;
        std::vector<float> forwardError, backwardError;
        const cv::TermCriteria criteria(cv::TermCriteria::COUNT | cv::TermCriteria::EPS, 24, .01);
        cv::calcOpticalFlowPyrLK(previous, current, points, forward, forwardStatus, forwardError,
            cv::Size(23, 23), 3, criteria, cv::OPTFLOW_USE_INITIAL_FLOW, .0001);
        cv::calcOpticalFlowPyrLK(current, previous, forward, backward, backwardStatus, backwardError,
            cv::Size(23, 23), 3, criteria, 0, .0001);
        std::vector<cv::Point2f> validPrevious, validCurrent;
        for (size_t i = 0; i < points.size(); ++i) {
            if (forwardStatus[i] && backwardStatus[i] && cv::norm(points[i] - backward[i]) < 1.8) {
                validPrevious.push_back(points[i]);
                validCurrent.push_back(forward[i]);
            }
        }
        cv::Mat inliers;
        const auto affine = cv::estimateAffinePartial2D(validPrevious, validCurrent, inliers,
            cv::RANSAC, 2.6, 1200, .995, 10);
        if (affine.empty()) return 4;
        minimumTracked = std::min(minimumTracked, static_cast<int>(validPrevious.size()));
        maxTranslationError = std::max(maxTranslationError,
            std::hypot(affine.at<double>(0, 2) - 2, affine.at<double>(1, 2) + 1));
        const double flowMs = milliseconds(flowStart);
        const auto reentryStart = Clock::now();
        std::vector<cv::KeyPoint> keys;
        cv::Mat descriptors;
        orb->detectAndCompute(current, cv::noArray(), keys, descriptors);
        std::vector<std::vector<cv::DMatch>> matches;
        matcher->knnMatch(referenceDescriptors, descriptors, matches, 2);
        minimumDescriptors = std::min(minimumDescriptors, descriptors.rows);
        const double reentryMs = milliseconds(reentryStart);
        if (iteration >= 0) {
            preprocessTimes.push_back(preprocessMs);
            flowTimes.push_back(flowMs);
            reentryTimes.push_back(reentryMs);
        }
    }
    std::printf("{\"samples\":%d,\"features\":%d,\"threads\":%d,\"minimum_tracked\":%d,"
        "\"minimum_descriptors\":%d,\"max_translation_error_px\":%.6f,", samples, featureCount,
        cv::getNumThreads(), minimumTracked, minimumDescriptors, maxTranslationError);
    report("preprocess", preprocessTimes);
    std::printf(","); report("flow", flowTimes);
    std::printf(","); report("reentry", reentryTimes);
    std::printf("}\n");
    return minimumTracked < featureCount * .9 || maxTranslationError > .25 ? 5 : 0;
}
