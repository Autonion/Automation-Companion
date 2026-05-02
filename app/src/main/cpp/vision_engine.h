#ifndef VISION_ENGINE_H
#define VISION_ENGINE_H

#include <jni.h>
#include <map>
#include <mutex>
#include <opencv2/opencv.hpp>
#include <string>
#include <vector>

// Helper to convert Bitmap to Mat
bool bitmap_to_mat(JNIEnv *env, jobject bitmap, cv::Mat &dst);

void vision_init();
void vision_add_template(int id, const cv::Mat &templ);
void vision_clear_templates();

struct MatchResult {
  int id;
  bool matched;
  float score;
  cv::Rect rect;
};

std::vector<MatchResult> vision_match_all(const cv::Mat &screen);

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_autonion_automationcompanion_core_vision_VisionNativeBridge_nativeInit(
    JNIEnv *env, jobject thiz);

JNIEXPORT void JNICALL
Java_com_autonion_automationcompanion_core_vision_VisionNativeBridge_nativeAddTemplate(
    JNIEnv *env, jobject thiz, jint id, jobject bitmap);

JNIEXPORT void JNICALL
Java_com_autonion_automationcompanion_core_vision_VisionNativeBridge_nativeClearTemplates(
    JNIEnv *env, jobject thiz);

JNIEXPORT jobjectArray JNICALL
Java_com_autonion_automationcompanion_core_vision_VisionNativeBridge_nativeMatch(
    JNIEnv *env, jobject thiz, jobject bitmap);
}

#endif // VISION_ENGINE_H
