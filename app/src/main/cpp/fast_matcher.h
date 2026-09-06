#pragma once

#include <mutex>
#include <map>
#include <chrono>
#include <opencv2/opencv.hpp>
#include <vector>

struct FastMatch {
  cv::Rect rect;
  float score;
};

// Appearance-only matching. No foreground segmentation or tracking prerequisite.
class FastMatcher {
public:
  FastMatcher(const cv::Mat &image, const cv::Mat &mask, float threshold);
  std::vector<FastMatch> match(const cv::Mat &search, float &best_score);

private:
  struct Variant {
    cv::Mat rgb, gray, mask, hue_histogram;
    cv::Rect anchor;
    cv::Scalar hsv;
    bool textured;
  };
  Variant &prepare(double scale);
  std::vector<FastMatch> scan(const cv::Mat &rgb, const cv::Mat &gray,
      const cv::Mat &coarse, double coarse_scale, double working_scale,
      double object_scale, float &best_score,
      std::chrono::steady_clock::time_point deadline);
  cv::Mat original_, original_mask_;
  std::map<std::pair<int, int>, Variant> variants_;
  std::vector<double> scales_;
  std::map<int, int> active_scales_;
  size_t discovery_ = 0;
  int frame_ = 0;
  double working_scale_ = 0;
  float threshold_;
  std::mutex mutex_;
};
