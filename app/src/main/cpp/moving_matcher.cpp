#include "moving_matcher.h"
#include <algorithm>
#include <cmath>
#include <chrono>
#include <android/log.h>

namespace {
cv::Mat color_histogram(const cv::Mat &rgb, const cv::Mat &mask) {
  cv::Mat hsv, hist;
  cv::cvtColor(rgb, hsv, cv::COLOR_RGB2HSV);
  const int channels[] = {0, 1};
  const int bins[] = {18, 8};
  const float hue[] = {0, 180}, saturation[] = {0, 256};
  const float *ranges[] = {hue, saturation};
  cv::calcHist(&hsv, 1, channels, mask, hist, 2, bins, ranges);
  cv::normalize(hist, hist, 1, 0, cv::NORM_L1);
  return hist;
}

}

MovingMatcher::MovingMatcher(const cv::Mat &rgb, const cv::Mat &mask,
                             cv::Rect roi, float threshold)
    : roi_(roi), threshold_(threshold) {
  // Bound work by target size: a larger ROI adds cheap background pixels,
  // rather than a full-resolution masked correlation at every location.
  scale_ = std::min(1.0, 32.0 / std::max(rgb.cols, rgb.rows));
  cv::Mat small, alpha;
  cv::resize(rgb, small, {std::max(1, cvRound(rgb.cols * scale_)),
                         std::max(1, cvRound(rgb.rows * scale_))}, 0, 0, cv::INTER_AREA);
  if (mask.empty()) {
    alpha = cv::Mat(small.size(), CV_8U, cv::Scalar(255));
  } else {
    cv::resize(mask, alpha, small.size(), 0, 0, cv::INTER_NEAREST);
  }
  histogram_ = color_histogram(small, alpha);

  for (double zoom : {0.85, 1.0, 1.15}) {
    for (int angle = 0; angle < 360; angle += 15) {
      cv::Point2f center((small.cols - 1) * 0.5f, (small.rows - 1) * 0.5f);
      cv::Mat transform = cv::getRotationMatrix2D(center, angle, zoom);
      double a = std::abs(transform.at<double>(0, 0));
      double b = std::abs(transform.at<double>(0, 1));
      cv::Size size(static_cast<int>(std::ceil(small.cols * a + small.rows * b)),
                    static_cast<int>(std::ceil(small.cols * b + small.rows * a)));
      transform.at<double>(0, 2) += (size.width - 1) * 0.5 - center.x;
      transform.at<double>(1, 2) += (size.height - 1) * 0.5 - center.y;
      Variant v;
      cv::warpAffine(small, v.image, transform, size, cv::INTER_LINEAR);
      cv::warpAffine(alpha, v.mask, transform, size, cv::INTER_NEAREST);
      // Exclude interpolated transparent borders from appearance verification.
      cv::erode(v.mask, v.mask, cv::Mat());
      v.pixels = cv::countNonZero(v.mask);
      if (v.pixels >= 16) variants_.push_back(std::move(v));
    }
  }
  clear_state();
}

void MovingMatcher::clear_state() {
  background_ = cv::createBackgroundSubtractorMOG2(120, 20, false);
  tracks_.clear();
  previous_ms_ = 0;
  frame_size_ = {};
  // IDs never repeat during a session, including after a scene change.
}

void MovingMatcher::reset() {
  std::lock_guard<std::mutex> lock(mutex_);
  clear_state();
}

std::vector<MovingMatch> MovingMatcher::match(const cv::Mat &screen,
                                            int64_t frame_ms) {
  std::lock_guard<std::mutex> lock(mutex_);
  std::vector<MovingMatch> matches;
  const cv::Rect roi = roi_ & cv::Rect({}, screen.size());
  if (roi.empty() || variants_.empty()) return matches;
  if (frame_ms <= previous_ms_) return matches;
  if (frame_size_ != screen.size() || frame_ms - previous_ms_ > 500) clear_state();
  frame_size_ = screen.size();

  cv::Mat small, foreground;
  cv::resize(screen(roi), small, {std::max(1, cvRound(roi.width * scale_)),
                                std::max(1, cvRound(roi.height * scale_))}, 0, 0, cv::INTER_AREA);
  background_->apply(small, foreground, previous_ms_ == 0 ? 1.0 : 0.015);
  const double coverage = cv::countNonZero(foreground) / double(foreground.total());
  if (previous_ms_ == 0 || coverage > 0.55) {
    // Initial frame, scrolling, menus and scene cuts must never become targets.
    if (previous_ms_ != 0) {
      clear_state();
      background_->apply(small, foreground, 1.0);
      frame_size_ = screen.size();
    }
    previous_ms_ = frame_ms;
    return matches;
  }

  cv::Mat components;
  cv::morphologyEx(foreground, components, cv::MORPH_CLOSE,
                   cv::getStructuringElement(cv::MORPH_RECT, {3, 3}));
  std::vector<std::vector<cv::Point>> contours;
  cv::findContours(components, contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);
  std::sort(contours.begin(), contours.end(), [](const auto &a, const auto &b) {
    return cv::contourArea(a) > cv::contourArea(b);
  });

  int verified = 0;
  for (const auto &contour : contours) {
    const auto bounds = cv::boundingRect(contour);
    if (bounds.area() < 24 || bounds.width > 100 || bounds.height > 100) continue;
    if (cv::countNonZero(foreground(bounds)) < 16) continue;
    const auto hist = color_histogram(small(bounds), foreground(bounds));
    if (cv::compareHist(histogram_, hist, cv::HISTCMP_BHATTACHARYYA) > 0.65) continue;
    if (++verified > 32) break;

    // Motion supplies the center. Evaluate a bounded neighborhood directly;
    // FFT-based masked matchTemplate for every angle costs more than the ROI.
    struct Appearance { cv::Rect rect; size_t variant; float color_score; };
    std::vector<Appearance> shortlist;
    const cv::Point center(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
    for (size_t index = 0; index < variants_.size(); ++index) {
      const auto &v = variants_[index];
      Appearance best_color{{}, index, 0};
      for (int dy : {-3, 0, 3}) for (int dx : {-3, 0, 3}) {
        const cv::Rect location(center.x - v.image.cols / 2 + dx,
                                center.y - v.image.rows / 2 + dy,
                                v.image.cols, v.image.rows);
        if ((location & cv::Rect({}, small.size())) != location) continue;
        float score = static_cast<float>(1.0 -
            cv::norm(small(location), v.image, cv::NORM_L1, v.mask) / (v.pixels * 3.0 * 255.0));
        if (score > best_color.color_score) best_color = {location, index, score};
      }
      if (best_color.color_score >= threshold_) shortlist.push_back(best_color);
    }
    std::sort(shortlist.begin(), shortlist.end(), [](const auto &a, const auto &b) {
      return a.color_score > b.color_score;
    });
    MovingMatch best{{}, 0};
    for (size_t i = 0; i < std::min(size_t(8), shortlist.size()); ++i) {
      auto candidate = shortlist[i];
      const auto &v = variants_[candidate.variant];
      const auto coarse = candidate.rect;
      for (int dy = -1; dy <= 1; ++dy) for (int dx = -1; dx <= 1; ++dx) {
        auto refined = coarse + cv::Point(dx, dy);
        if ((refined & cv::Rect({}, small.size())) != refined) continue;
        const float color_score = static_cast<float>(1.0 -
            cv::norm(small(refined), v.image, cv::NORM_L1, v.mask) / (v.pixels * 3.0 * 255.0));
        if (color_score > candidate.color_score) {
          candidate.rect = refined;
          candidate.color_score = color_score;
        }
      }
      cv::Mat support;
      cv::bitwise_and(foreground(candidate.rect), v.mask, support);
      if (cv::countNonZero(support) < v.pixels * 0.35) continue;
      cv::Scalar mean, deviation;
      cv::meanStdDev(v.image, mean, deviation, v.mask);
      float score = candidate.color_score;
      if (deviation[0] + deviation[1] + deviation[2] > 24) {
        // Verify texture after color localization. A similarly colored moving
        // shape is not sufficient to identify a textured target.
        cv::Mat correlation;
        cv::matchTemplate(small(candidate.rect), v.image, correlation,
                           cv::TM_CCOEFF_NORMED, v.mask);
        const float structure = correlation.at<float>(0, 0);
        if (!std::isfinite(structure)) continue;
        score = 0.65f * score + 0.35f * std::max(0.0f, structure);
      }
      if (score > best.score) best = {candidate.rect, score};
    }
    if (best.score >= threshold_) {
      bool duplicate = false;
      for (const auto &existing : matches) {
        const auto intersection = best.rect & existing.rect;
        if (intersection.area() > 0.3 * std::min(best.rect.area(), existing.rect.area())) {
          duplicate = true;
          break;
        }
      }
      if (!duplicate) matches.push_back(best);
    }
  }

  for (auto &hit : matches) {
    hit.rect = cv::Rect(roi.x + cvRound(hit.rect.x / scale_),
                        roi.y + cvRound(hit.rect.y / scale_),
                        cvRound(hit.rect.width / scale_),
                        cvRound(hit.rect.height / scale_)) & roi;
  }
  assign_tracks(matches, frame_ms);
  previous_ms_ = frame_ms;
  const auto now = std::chrono::duration_cast<std::chrono::milliseconds>(
      std::chrono::steady_clock::now().time_since_epoch()).count();
  if (now - last_log_ms_ >= 2000) {
    __android_log_print(ANDROID_LOG_INFO, "MovingVision",
        "ROI=(%d,%d,%d,%d) foreground=%.3f blobs=%zu candidates=%d hits=%zu threshold=%.2f",
        roi.x, roi.y, roi.width, roi.height, coverage, contours.size(), verified,
        matches.size(), threshold_);
    last_log_ms_ = now;
  }
  return matches;
}

void MovingMatcher::assign_tracks(std::vector<MovingMatch> &matches, int64_t frame_ms) {
  tracks_.erase(std::remove_if(tracks_.begin(), tracks_.end(), [frame_ms](const auto &t) {
    return frame_ms - t.seen_ms > 250;
  }), tracks_.end());
  const float dt = std::max(0.001f, (frame_ms - previous_ms_) / 1000.0f);
  for (auto &track : tracks_) {
    track.filter.transitionMatrix.at<float>(0, 2) = dt;
    track.filter.transitionMatrix.at<float>(1, 3) = dt;
    const auto predicted = track.filter.predict();
    track.prediction = {predicted.at<float>(0), predicted.at<float>(1)};
  }
  struct Pair { size_t detection, track; double distance; };
  std::vector<Pair> pairs;
  for (size_t i = 0; i < matches.size(); ++i) {
    const auto &r = matches[i].rect;
    cv::Point2f center(r.x + r.width * 0.5f, r.y + r.height * 0.5f);
    for (size_t j = 0; j < tracks_.size(); ++j) {
      double distance = cv::norm(center - tracks_[j].prediction);
      if (distance < std::max(r.width, r.height) * 1.5) pairs.push_back({i, j, distance});
    }
  }
  std::sort(pairs.begin(), pairs.end(), [](const auto &a, const auto &b) {
    return a.distance < b.distance;
  });
  std::vector<int> assigned(matches.size(), -1);
  std::vector<bool> used(tracks_.size(), false);
  for (const auto &pair : pairs) {
    if (assigned[pair.detection] == -1 && !used[pair.track]) {
      assigned[pair.detection] = static_cast<int>(pair.track);
      used[pair.track] = true;
    }
  }
  for (size_t i = 0; i < matches.size(); ++i) {
    auto &hit = matches[i];
    const float x = hit.rect.x + hit.rect.width * 0.5f;
    const float y = hit.rect.y + hit.rect.height * 0.5f;
    if (assigned[i] == -1) {
      Track track{next_track_id_++, cv::KalmanFilter(4, 2), {x, y}, frame_ms, 1};
      cv::setIdentity(track.filter.transitionMatrix);
      track.filter.measurementMatrix = (cv::Mat_<float>(2, 4) << 1, 0, 0, 0, 0, 1, 0, 0);
      cv::setIdentity(track.filter.processNoiseCov, cv::Scalar(25));
      cv::setIdentity(track.filter.measurementNoiseCov, cv::Scalar(4));
      cv::setIdentity(track.filter.errorCovPost, cv::Scalar(100));
      track.filter.errorCovPost.at<float>(2, 2) = 1000000;
      track.filter.errorCovPost.at<float>(3, 3) = 1000000;
      track.filter.statePost = (cv::Mat_<float>(4, 1) << x, y, 0, 0);
      tracks_.push_back(std::move(track));
      assigned[i] = static_cast<int>(tracks_.size() - 1);
    } else {
      auto &track = tracks_[assigned[i]];
      track.filter.correct((cv::Mat_<float>(2, 1) << x, y));
      track.observations++;
      track.seen_ms = frame_ms;
    }
    const auto &track = tracks_[assigned[i]];
    hit.track_id = track.id;
    hit.observations = track.observations;
    hit.velocity_x = track.filter.statePost.at<float>(2);
    hit.velocity_y = track.filter.statePost.at<float>(3);
  }
}
