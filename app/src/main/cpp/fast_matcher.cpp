#include "fast_matcher.h"
#include <algorithm>
#include <cmath>

namespace {
constexpr int MAX_CANDIDATES = 96;
constexpr int MAX_MATCHES = 32;

bool overlaps(const cv::Rect &a, const cv::Rect &b) {
  const double intersection = (a & b).area();
  return intersection / std::max(1.0, a.area() + b.area() - intersection) > 0.30;
}

float color_similarity(const cv::Scalar &a, const cv::Scalar &b) {
  double hue = std::abs(a[0] - b[0]);
  hue = std::min(hue, 180.0 - hue) / 90.0;
  // Hue is not meaningful for unsaturated pixels.
  hue *= std::min(a[1], b[1]) / 255.0;
  return static_cast<float>(1.0 - (hue + std::abs(a[1] - b[1]) / 255.0 +
                                  std::abs(a[2] - b[2]) / 255.0) / 3.0);
}
}

FastMatcher::FastMatcher(const cv::Mat &image, const cv::Mat &mask, float threshold)
    : original_(image), original_mask_(mask), threshold_(threshold) {}

void FastMatcher::prepare(double scale) {
  if (scale == scale_) return;
  scale_ = scale;
  cv::resize(original_, rgb_, {std::max(1, cvRound(original_.cols * scale)),
                              std::max(1, cvRound(original_.rows * scale))}, 0, 0, cv::INTER_AREA);
  if (rgb_.channels() == 1) cv::cvtColor(rgb_, rgb_, cv::COLOR_GRAY2RGB);
  cv::cvtColor(rgb_, gray_, cv::COLOR_RGB2GRAY);
  mask_.release();
  if (!original_mask_.empty()) {
    cv::resize(original_mask_, mask_, gray_.size(), 0, 0, cv::INTER_NEAREST);
  }
  anchor_ = cv::Rect({}, gray_.size());
  if (!mask_.empty()) {
    // Search an opaque interior patch without a mask, then verify the full
    // masked crop only at candidate locations. Avoid masked FFTs across the ROI.
    cv::Mat padded, distance;
    cv::copyMakeBorder(mask_, padded, 1, 1, 1, 1, cv::BORDER_CONSTANT, 0);
    cv::distanceTransform(padded, distance, cv::DIST_C, 3);
    double radius;
    cv::Point center;
    cv::minMaxLoc(distance, nullptr, &radius, nullptr, &center);
    const int half = std::max(0, static_cast<int>(radius) - 1);
    if (half >= 4) anchor_ = cv::Rect(center.x - 1 - half, center.y - 1 - half,
                                     2 * half + 1, 2 * half + 1);
  }
  cv::Scalar mean, deviation;
  cv::meanStdDev(gray_(anchor_), mean, deviation,
                 anchor_.size() == gray_.size() ? mask_ : cv::Mat());
  textured_ = deviation[0] >= 2.0;
  cv::Mat hsv;
  cv::cvtColor(rgb_, hsv, cv::COLOR_RGB2HSV);
  hsv_mean_ = cv::mean(hsv, mask_);
}

std::vector<FastMatch> FastMatcher::match(const cv::Mat &search, float &best_score) {
  std::lock_guard<std::mutex> lock(mutex_);
  std::vector<FastMatch> hits;
  best_score = 0;
  if (search.cols < original_.cols || search.rows < original_.rows) return hits;
  // Bound the working image without erasing small targets. Capture may already be scaled.
  const double scale = std::min(1.0, std::max(720.0 / std::max(search.cols, search.rows),
                                             20.0 / std::min(original_.cols, original_.rows)));
  prepare(scale);
  if (!mask_.empty() && cv::countNonZero(mask_) < 4) return hits;
  cv::Mat small, gray, response;
  if (scale == 1.0) small = search;
  else cv::resize(search, small, {}, scale, scale, cv::INTER_AREA);
  if (small.channels() == 4) cv::cvtColor(small, small, cv::COLOR_RGBA2RGB);
  if (small.channels() == 1) cv::cvtColor(small, small, cv::COLOR_GRAY2RGB);
  cv::cvtColor(small, gray, cv::COLOR_RGB2GRAY);
  const cv::Mat anchor_mask = anchor_.size() == gray_.size() ? mask_ : cv::Mat();
  if (textured_) {
    cv::matchTemplate(gray, gray_(anchor_), response, cv::TM_CCOEFF_NORMED, anchor_mask);
  } else {
    // Normalized correlation is undefined for constant templates (including black).
    cv::matchTemplate(gray, gray_(anchor_), response, cv::TM_SQDIFF, anchor_mask);
    const double pixels = anchor_mask.empty() ? anchor_.area() : cv::countNonZero(anchor_mask);
    response = 1.0 - response / (std::max(1.0, pixels) * 255.0 * 255.0);
  }
  cv::patchNaNs(response, -1);
  response.setTo(-1, response > 1.001);
  response.setTo(-1, response < -1.001);
  double maximum;
  cv::minMaxLoc(response, nullptr, &maximum);
  best_score = static_cast<float>(std::max(0.0, maximum));
  if (maximum < threshold_) return hits;

  // One local-max pass replaces repeated full-image minMaxLoc scans. Each peak
  // is a template location, so touching objects do not become one motion blob.
  cv::Mat maxima, peak_mask;
  cv::dilate(response, maxima, cv::getStructuringElement(cv::MORPH_RECT,
      {std::max(3, (gray_.cols / 2) | 1), std::max(3, (gray_.rows / 2) | 1)}));
  cv::compare(response, maxima, peak_mask, cv::CMP_GE);
  peak_mask &= response >= threshold_;
  std::vector<std::vector<cv::Point>> peaks;
  cv::findContours(peak_mask, peaks, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);
  std::vector<FastMatch> candidates;
  for (const auto &peak : peaks) {
    // Connected plateaus share a score; retain one actual peak pixel.
    const auto point = peak.front();
    const cv::Rect rect(point.x - anchor_.x, point.y - anchor_.y, gray_.cols, gray_.rows);
    if ((rect & cv::Rect({}, small.size())) == rect)
      candidates.push_back({rect, response.at<float>(point)});
  }
  const auto ranked = [](const FastMatch &a, const FastMatch &b) {
    if (a.score != b.score) return a.score > b.score;
    if (a.rect.y != b.rect.y) return a.rect.y > b.rect.y;
    return a.rect.x < b.rect.x;
  };
  const size_t count = std::min(candidates.size(), size_t(MAX_CANDIDATES));
  std::partial_sort(candidates.begin(), candidates.begin() + count, candidates.end(), ranked);
  best_score = 0;
  for (size_t i = 0; i < count && hits.size() < MAX_MATCHES; ++i) {
    auto candidate = candidates[i];
    if (std::any_of(hits.begin(), hits.end(), [&](const auto &hit) { return overlaps(hit.rect, candidate.rect); })) continue;
    cv::Mat hsv;
    cv::cvtColor(small(candidate.rect), hsv, cv::COLOR_RGB2HSV);
    candidate.score = std::min(candidate.score, color_similarity(cv::mean(hsv, mask_), hsv_mean_));
    if (!mask_.empty() && candidate.score >= threshold_) {
      const float pixels = cv::countNonZero(mask_);
      const float appearance = 1.0f - static_cast<float>(
          cv::norm(small(candidate.rect), rgb_, cv::NORM_L1, mask_) / (pixels * 3.0 * 255.0));
      candidate.score = std::min(candidate.score, appearance);
    }
    best_score = std::max(best_score, candidate.score);
    if (candidate.score >= threshold_) hits.push_back(candidate);
  }
  for (auto &hit : hits) {
    hit.rect = cv::Rect(cvRound(hit.rect.x / scale), cvRound(hit.rect.y / scale),
                       original_.cols, original_.rows) & cv::Rect({}, search.size());
  }
  return hits;
}
