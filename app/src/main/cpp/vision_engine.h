#ifndef VISION_ENGINE_H
#define VISION_ENGINE_H

#include <opencv2/opencv.hpp>
#include <vector>
#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Initialize the vision engine.
 * Call once when app starts.
 */
void vision_init();

/**
 * Release all native resources.
 * Call when app is shutting down.
 */
void vision_release();

/**
 * Store a template image for later matching.
 *
 * @param templateMat   Grayscale template image
 */
void vision_set_template(const cv::Mat& templateMat);

/**
 * Match the stored template against a screen image.
 *
 * @param screenMat     Grayscale screen image
 * @param outRect       Output bounding box of match
 * @param outScore      Output confidence score (0.0 - 1.0)
 *
 * @return true if match found above threshold
 */
bool vision_match_template(
        const cv::Mat& screenMat,
        cv::Rect& outRect,
        float& outScore
);

extern "C" {

JNIEXPORT void JNICALL
Java_com_example_screenunderstaingusingml_ndk_VisionBridge_nativeSetTemplate(
        JNIEnv* env,
jobject thiz,
        jobject bitmap
);

JNIEXPORT jobject JNICALL
        Java_com_example_screenunderstaingusingml_ndk_VisionBridge_nativeMatch(
        JNIEnv* env,
        jobject thiz,
jobject bitmap,
        jfloat threshold
);

}

#ifdef __cplusplus
}
#endif

#endif // VISION_ENGINE_H
