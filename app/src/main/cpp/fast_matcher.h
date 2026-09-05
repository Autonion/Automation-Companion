#pragma once

#include <mutex>
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
  void prepare(double scale);
  cv::Mat original_, original_mask_, rgb_, gray_, mask_;
  cv::Rect anchor_;
  cv::Scalar hsv_mean_;
  float threshold_;
  double scale_ = 0;
  bool textured_ = true;
  std::mutex mutex_;
};
