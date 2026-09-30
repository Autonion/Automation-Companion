#pragma once

#include <opencv2/opencv.hpp>
#include <cstdint>
#include <mutex>
#include <vector>

struct MovingMatch {
  cv::Rect rect;
  float score;
  int track_id = 0;
  float velocity_x = 0;
  float velocity_y = 0;
  int observations = 0;
};

// One independent background model and tracker per user-selected search area.
class MovingMatcher {
public:
  MovingMatcher(const cv::Mat &rgb, const cv::Mat &mask, cv::Rect roi,
                float threshold);
  std::vector<MovingMatch> match(const cv::Mat &screen, int64_t frame_ms);
  void reset();

private:
  struct Variant {
    cv::Mat image, mask;
    int pixels;
  };
  struct Track {
    int id;
    cv::KalmanFilter filter;
    cv::Point2f prediction;
    int64_t seen_ms;
    int observations;
  };
  void clear_state();
  void assign_tracks(std::vector<MovingMatch> &matches, int64_t frame_ms);

  cv::Rect roi_;
  float threshold_;
  double scale_;
  cv::Mat histogram_;
  std::vector<Variant> variants_;
  cv::Ptr<cv::BackgroundSubtractorMOG2> background_;
  cv::Size frame_size_;
  int64_t previous_ms_ = 0;
  int64_t last_log_ms_ = 0;
  int next_track_id_ = 1;
  std::vector<Track> tracks_;
  std::mutex mutex_;
};
