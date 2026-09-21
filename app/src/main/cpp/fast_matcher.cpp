#include "fast_matcher.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <set>
#include <android/log.h>

namespace {
constexpr int MAX_CANDIDATES = 40;
constexpr int MAX_MATCHES = 32;
constexpr int MIN_TARGET_SIDE = 10;
constexpr double SCALE_STEP = 1.16;

cv::Mat chromatic_histogram(const cv::Mat &hsv, const cv::Mat &mask) {
  cv::Mat chromatic, histogram;
  cv::inRange(hsv, cv::Scalar(0, 48, 48), cv::Scalar(180, 255, 255), chromatic);
  if (!mask.empty()) chromatic &= mask;
  if (cv::countNonZero(chromatic) < 4) return histogram;
  const int bins = 18, channel = 0;
  const float range[] = {0, 180};
  const float *ranges[] = {range};
  cv::calcHist(&hsv, 1, &channel, chromatic, histogram, 1, &bins, ranges);
  const cv::Mat raw = histogram.clone();
  for (int i = 0; i < bins; ++i)
    histogram.at<float>(i) = raw.at<float>(i) * 0.5f +
        (raw.at<float>((i + bins - 1) % bins) + raw.at<float>((i + 1) % bins)) * 0.25f;
  cv::normalize(histogram, histogram, 1, 0, cv::NORM_L1);
  return histogram;
}

bool overlaps(const cv::Rect &a, const cv::Rect &b) {
  const double intersection = (a & b).area();
  return intersection / std::max(1.0, a.area() + b.area() - intersection) > 0.30;
}

bool ranked(const FastMatch &a, const FastMatch &b) {
  if (a.score != b.score) return a.score > b.score;
  if (a.rect.y != b.rect.y) return a.rect.y > b.rect.y;
  return a.rect.x < b.rect.x;
}

float color_similarity(const cv::Scalar &a, const cv::Scalar &b) {
  double hue = std::abs(a[0] - b[0]);
  hue = std::min(hue, 180.0 - hue) / 90.0;
  hue *= std::min(a[1], b[1]) / 255.0;
  return static_cast<float>(1.0 - (hue + std::abs(a[1] - b[1]) / 255.0 +
                                  std::abs(a[2] - b[2]) / 255.0) / 3.0);
}

cv::Mat response_for(const cv::Mat &image, const cv::Mat &target,
                     const cv::Mat &mask, bool textured) {
  cv::Mat response;
  cv::matchTemplate(image, target, response,
      textured ? cv::TM_CCOEFF_NORMED : cv::TM_SQDIFF, mask);
  if (!textured) {
    const double pixels = mask.empty() ? target.total() : cv::countNonZero(mask);
    response = 1.0 - response / (std::max(1.0, pixels) * 255.0 * 255.0);
  }
  cv::patchNaNs(response, -1);
  response.setTo(-1, response > 1.001);
  response.setTo(-1, response < -1.001);
  return response;
}
}

FastMatcher::FastMatcher(const cv::Mat &image, const cv::Mat &mask, float threshold)
    : original_(image), original_mask_(mask), threshold_(threshold) {
  scales_.push_back(1.0);
  // Interleave sizes and keep exploring even while other sizes match.
  for (int step = 1; step <= 10; ++step) {
    const double factor = std::pow(SCALE_STEP, step);
    if (1.0 / factor >= 0.25) scales_.push_back(1.0 / factor);
    if (factor <= 4.0) scales_.push_back(factor);
  }
}

FastMatcher::Variant &FastMatcher::prepare(double scale) {
  const auto key = std::make_pair(std::max(1, cvRound(original_.cols * scale)),
                                  std::max(1, cvRound(original_.rows * scale)));
  auto found = variants_.find(key);
  if (found != variants_.end()) return found->second;
  Variant v;
  cv::resize(original_, v.rgb, {key.first, key.second}, 0, 0,
             scale < 1.0 ? cv::INTER_AREA : cv::INTER_LINEAR);
  if (v.rgb.channels() == 1) cv::cvtColor(v.rgb, v.rgb, cv::COLOR_GRAY2RGB);
  cv::cvtColor(v.rgb, v.gray, cv::COLOR_RGB2GRAY);
  if (!original_mask_.empty())
    cv::resize(original_mask_, v.mask, v.gray.size(), 0, 0, cv::INTER_NEAREST);
  v.anchor = cv::Rect({}, v.gray.size());
  if (!v.mask.empty()) {
    cv::Mat padded, distance;
    cv::copyMakeBorder(v.mask, padded, 1, 1, 1, 1, cv::BORDER_CONSTANT, 0);
    cv::distanceTransform(padded, distance, cv::DIST_C, 3);
    double radius;
    cv::Point center;
    cv::minMaxLoc(distance, nullptr, &radius, nullptr, &center);
    const int half = std::max(0, static_cast<int>(radius) - 1);
    if (half >= 4) v.anchor = cv::Rect(center.x - 1 - half, center.y - 1 - half,
                                      2 * half + 1, 2 * half + 1);
  }
  cv::Scalar mean, deviation;
  cv::meanStdDev(v.gray(v.anchor), mean, deviation,
                 v.anchor.size() == v.gray.size() ? v.mask : cv::Mat());
  v.textured = deviation[0] >= 2.0;
  cv::Mat hsv;
  cv::cvtColor(v.rgb, hsv, cv::COLOR_RGB2HSV);
  v.hsv = cv::mean(hsv, v.mask);
  v.hue_histogram = chromatic_histogram(hsv, v.mask);
  return variants_.emplace(key, std::move(v)).first->second;
}

std::vector<FastMatch> FastMatcher::scan(const cv::Mat &rgb, const cv::Mat &gray,
    const cv::Mat &coarse, double coarse_scale, double working_scale,
    double object_scale, float &best_score,
    std::chrono::steady_clock::time_point deadline) {
  std::vector<FastMatch> hits;
  auto &v = prepare(working_scale * object_scale);
  if (v.gray.cols > gray.cols || v.gray.rows > gray.rows ||
      (!v.mask.empty() && cv::countNonZero(v.mask) < 4)) return hits;
  cv::Mat anchor, mask;
  cv::resize(v.gray(v.anchor), anchor,
      {std::max(1, cvRound(v.anchor.width * coarse_scale)),
       std::max(1, cvRound(v.anchor.height * coarse_scale))}, 0, 0, cv::INTER_AREA);
  if (v.anchor.size() == v.gray.size() && !v.mask.empty())
    cv::resize(v.mask, mask, anchor.size(), 0, 0, cv::INTER_NEAREST);
  if (anchor.cols > coarse.cols || anchor.rows > coarse.rows) return hits;
  cv::Mat response = response_for(coarse, anchor, mask, v.textured);
  double maximum;
  cv::minMaxLoc(response, nullptr, &maximum);
  // Coarse scores propose locations only. Full-resolution verification uses
  // the user's unchanged threshold, including color and alpha.
  const float proposal_threshold = std::max(0.45f, threshold_ - 0.22f);
  if (maximum < proposal_threshold) return hits;
  cv::Mat maxima, peak_mask;
  cv::dilate(response, maxima, cv::getStructuringElement(cv::MORPH_RECT,
      {std::max(3, cvRound(v.gray.cols * coarse_scale / 2) | 1),
       std::max(3, cvRound(v.gray.rows * coarse_scale / 2) | 1)}));
  cv::compare(response, maxima, peak_mask, cv::CMP_GE);
  peak_mask &= response >= proposal_threshold;
  std::vector<std::vector<cv::Point>> peaks;
  cv::findContours(peak_mask, peaks, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);
  std::vector<FastMatch> candidates;
  for (const auto &peak : peaks) {
    const auto point = peak.front();
    const cv::Point top(cvRound(point.x / coarse_scale) - v.anchor.x,
                         cvRound(point.y / coarse_scale) - v.anchor.y);
    candidates.push_back({cv::Rect(top, v.gray.size()), response.at<float>(point)});
  }
  const size_t count = std::min(candidates.size(), size_t(MAX_CANDIDATES));
  std::partial_sort(candidates.begin(), candidates.begin() + count, candidates.end(), ranked);
  const cv::Rect bounds({}, gray.size());
  for (size_t i = 0; i < count; ++i) {
    if (std::chrono::steady_clock::now() >= deadline) break;
    FastMatch best{{}, 0};
    for (double adjustment : {1.0, 0.95, 1.05}) {
      if (std::chrono::steady_clock::now() >= deadline) break;
      auto &fine = prepare(working_scale * object_scale * adjustment);
      if (fine.gray.cols > gray.cols || fine.gray.rows > gray.rows ||
          (!fine.mask.empty() && cv::countNonZero(fine.mask) < 4)) continue;
      const auto center = candidates[i].rect.tl() + cv::Point(v.gray.cols / 2, v.gray.rows / 2);
      const cv::Point top = center - cv::Point(fine.gray.cols / 2, fine.gray.rows / 2);
      const int margin = static_cast<int>(std::ceil(2.0 / coarse_scale));
      const auto patch = (cv::Rect(top + fine.anchor.tl(), fine.anchor.size()) +
          cv::Size(2 * margin, 2 * margin) - cv::Point(margin, margin)) & bounds;
      if (patch.width < fine.anchor.width || patch.height < fine.anchor.height) continue;
      const cv::Mat fine_mask = fine.anchor.size() == fine.gray.size() ? fine.mask : cv::Mat();
      const auto fine_response = response_for(gray(patch), fine.gray(fine.anchor), fine_mask, fine.textured);
      cv::Point location;
      double score;
      cv::minMaxLoc(fine_response, nullptr, &score, nullptr, &location);
      const cv::Rect rect(patch.tl() + location - fine.anchor.tl(), fine.gray.size());
      if ((rect & bounds) != rect) continue;
      if (score < threshold_) {
        best_score = std::max(best_score, static_cast<float>(score));
        continue;
      }
      if (score <= best.score) continue;
      cv::Mat hsv;
      cv::cvtColor(rgb(rect), hsv, cv::COLOR_RGB2HSV);
      score = std::min(score, static_cast<double>(color_similarity(cv::mean(hsv, fine.mask), fine.hsv)));
      if (!fine.hue_histogram.empty()) {
        const auto histogram = chromatic_histogram(hsv, fine.mask);
        score = std::min(score, histogram.empty() ? 0.0 :
            1.0 - cv::compareHist(histogram, fine.hue_histogram, cv::HISTCMP_BHATTACHARYYA));
      }
      // Mean color alone can accept gray distractors around dark backgrounds.
      // Compare spatial RGB energy too, with alpha excluded where appropriate.
      const double energy = cv::norm(rgb(rect), cv::NORM_L2SQR, fine.mask) +
                            cv::norm(fine.rgb, cv::NORM_L2SQR, fine.mask);
      const double color_appearance = 1.0 - cv::norm(rgb(rect), fine.rgb, cv::NORM_L2SQR, fine.mask) /
          std::max(1.0, energy);
      score = std::min(score, color_appearance);
      if (!fine.mask.empty()) {
        const double appearance = 1.0 - cv::norm(rgb(rect), fine.rgb, cv::NORM_L1, fine.mask) /
            (cv::countNonZero(fine.mask) * 3.0 * 255.0);
        score = std::min(score, appearance);
      }
      if (score > best.score) best = {rect, static_cast<float>(score)};
      if (best.score >= 0.97f) break;
    }
    best_score = std::max(best_score, best.score);
    if (best.score >= threshold_) hits.push_back(best);
  }
  return hits;
}

std::vector<FastMatch> FastMatcher::match(const cv::Mat &search, float &best_score) {
  std::lock_guard<std::mutex> lock(mutex_);
  best_score = 0;
  if (search.empty()) return {};
  const auto started = std::chrono::steady_clock::now();
  const double scale = std::min(1.0, std::max(720.0 / std::max(search.cols, search.rows),
                                             20.0 / std::min(original_.cols, original_.rows)));
  if (working_scale_ != scale) {
    variants_.clear();
    active_scales_.clear();
    working_scale_ = scale;
    discovery_ = 0;
    frame_ = 0;
  }
  cv::Mat rgb, gray, coarse;
  if (scale == 1.0) rgb = search;
  else cv::resize(search, rgb, {}, scale, scale, cv::INTER_AREA);
  if (rgb.channels() == 4) cv::cvtColor(rgb, rgb, cv::COLOR_RGBA2RGB);
  if (rgb.channels() == 1) cv::cvtColor(rgb, rgb, cv::COLOR_GRAY2RGB);
  cv::cvtColor(rgb, gray, cv::COLOR_RGB2GRAY);
  const double coarse_scale = std::min(1.0, 360.0 / std::max(gray.cols, gray.rows));
  cv::resize(gray, coarse, {}, coarse_scale, coarse_scale, cv::INTER_AREA);
  ++frame_;
  for (auto it = active_scales_.begin(); it != active_scales_.end();) {
    if (frame_ - it->second > 12) it = active_scales_.erase(it);
    else ++it;
  }
  const bool textured = prepare(scale).textured;
  std::vector<int> order;
  std::set<int> selected;
  auto add = [&](int index) { if (selected.insert(index).second) order.push_back(index); };
  // Reserve discovery turns even when established sizes consume the scan budget.
  if (frame_ % 3 == 0 && textured) add(static_cast<int>(discovery_));
  std::vector<std::pair<int, int>> active(active_scales_.begin(), active_scales_.end());
  std::sort(active.begin(), active.end(), [](const auto &a, const auto &b) { return a.second < b.second; });
  for (const auto &entry : active) add(entry.first);
  if (active.empty()) add(0);
  const int probes = active.empty() ? 4 : 2;
  if (textured) for (int i = 0; i < probes; ++i) add(static_cast<int>((discovery_ + i) % scales_.size()));
  std::vector<FastMatch> candidates;
  int scanned = 0;
  for (int index : order) {
    if (scanned > 0 && std::chrono::steady_clock::now() - started > std::chrono::milliseconds(90)) break;
    const double factor = scales_[index];
    const int width = cvRound(original_.cols * scale * factor);
    const int height = cvRound(original_.rows * scale * factor);
    if (index == static_cast<int>(discovery_)) discovery_ = (discovery_ + 1) % scales_.size();
    if (width > gray.cols || height > gray.rows ||
        (index != 0 && std::min(width, height) < MIN_TARGET_SIDE)) continue;
    auto hits = scan(rgb, gray, coarse, coarse_scale, scale, factor, best_score,
                     started + std::chrono::milliseconds(90));
    ++scanned;
    candidates.insert(candidates.end(), hits.begin(), hits.end());
  }
  std::sort(candidates.begin(), candidates.end(), ranked);
  std::vector<FastMatch> hits;
  for (const auto &candidate : candidates) {
    if (hits.size() == MAX_MATCHES) break;
    if (std::none_of(hits.begin(), hits.end(), [&](const auto &hit) { return overlaps(hit.rect, candidate.rect); }))
      hits.push_back(candidate);
  }
  // Each occurrence carries its actual size, not the original template size.
  for (auto &hit : hits) {
    const double detected_scale = hit.rect.width / (original_.cols * scale);
    const auto closest = std::min_element(scales_.begin(), scales_.end(), [&](double a, double b) {
      return std::abs(std::log(a / detected_scale)) < std::abs(std::log(b / detected_scale));
    });
    active_scales_[static_cast<int>(closest - scales_.begin())] = frame_;
    hit.rect = cv::Rect(cvRound(hit.rect.x / scale), cvRound(hit.rect.y / scale),
        cvRound(hit.rect.width / scale), cvRound(hit.rect.height / scale)) & cv::Rect({}, search.size());
  }
  if (frame_ % 60 == 1)
    __android_log_print(ANDROID_LOG_INFO, "FastVision", "adaptive-size scans=%d active=%zu cursor=%zu hits=%zu best=%.3f",
        scanned, active_scales_.size(), discovery_, hits.size(), best_score);
  return hits;
}
