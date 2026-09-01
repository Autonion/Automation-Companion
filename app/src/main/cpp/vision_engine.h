#ifndef VISION_ENGINE_H
#define VISION_ENGINE_H

#include <jni.h>
#include <condition_variable>
#include <functional>
#include <map>
#include <mutex>
#include <opencv2/opencv.hpp>
#include <queue>
#include <string>
#include <thread>
#include <vector>

// Helper to convert Bitmap to Mat
bool bitmap_to_mat(JNIEnv *env, jobject bitmap, cv::Mat &dst);

void vision_init();
void vision_add_template(int id, const cv::Mat &templ, int roi_x, int roi_y,
                         int roi_w, int roi_h, float threshold);
void vision_clear_templates();
void vision_destroy();
void vision_request_fullscreen_search(int id);

struct MatchResult {
  int id;
  bool matched;
  float score;
  cv::Rect rect;
  bool used_fullscreen;
};

// Per-template storage: grayscale template + ROI + threshold + miss tracking
struct TemplateData {
  cv::Mat templ;     // Grayscale template image
  cv::Rect roi;      // Expected screen location (from editor)
  float threshold;   // Per-region match threshold (default 0.75)
  int miss_count;    // Consecutive ROI misses (triggers fullscreen fallback)
  bool force_fullscreen_once;
};

std::vector<MatchResult> vision_match_all(const cv::Mat &screen);

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_autonion_automationcompanion_core_vision_VisionNativeBridge_nativeInit(
    JNIEnv *env, jobject thiz);

JNIEXPORT void JNICALL
Java_com_autonion_automationcompanion_core_vision_VisionNativeBridge_nativeAddTemplate(
    JNIEnv *env, jobject thiz, jint id, jobject bitmap, jint roiX, jint roiY,
    jint roiW, jint roiH, jfloat threshold);

JNIEXPORT void JNICALL
Java_com_autonion_automationcompanion_core_vision_VisionNativeBridge_nativeClearTemplates(
    JNIEnv *env, jobject thiz);

JNIEXPORT void JNICALL
Java_com_autonion_automationcompanion_core_vision_VisionNativeBridge_nativeDestroy(
    JNIEnv *env, jobject thiz);

JNIEXPORT void JNICALL
Java_com_autonion_automationcompanion_core_vision_VisionNativeBridge_nativeRequestFullscreenSearch(
    JNIEnv *env, jobject thiz, jint id);

JNIEXPORT jobjectArray JNICALL
Java_com_autonion_automationcompanion_core_vision_VisionNativeBridge_nativeMatch(
    JNIEnv *env, jobject thiz, jobject bitmap);
}

#endif // VISION_ENGINE_H
