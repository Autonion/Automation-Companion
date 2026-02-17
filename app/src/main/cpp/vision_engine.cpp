// =======================
// JNI + Android
// =======================
#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>

// =======================
// OpenCV
// =======================
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/features2d.hpp>
#include <opencv2/calib3d.hpp>

// =======================
// Logging
// =======================
#define LOG_TAG "VISION_ENGINE"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// =======================
// Global Engine State
// =======================
static bool g_initialized = false;

// Template data
static cv::Mat g_templateGray;
static std::vector<cv::KeyPoint> g_templateKeypoints;
static cv::Mat g_templateDescriptors;
static cv::Ptr<cv::ORB> g_orb;

// =======================
// Utility: Bitmap → cv::Mat
// =======================
static bool bitmap_to_mat(JNIEnv* env, jobject bitmap, cv::Mat& outMat) {
    AndroidBitmapInfo info;
    void* pixels = nullptr;

    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to get bitmap info");
        return false;
    }

    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Unsupported bitmap format");
        return false;
    }

    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to lock bitmap pixels");
        return false;
    }

    cv::Mat rgba(info.height, info.width, CV_8UC4, pixels);
    outMat = rgba.clone(); // copy before unlock

    AndroidBitmap_unlockPixels(env, bitmap);
    return true;
}

// =======================
// Engine Lifecycle
// =======================
void vision_init() {
    if (g_initialized) return;

    g_orb = cv::ORB::create(
            800,        // nfeatures
            1.2f,       // scaleFactor
            8,          // nlevels
            31,         // edgeThreshold
            0,          // firstLevel
            2,          // WTA_K
            cv::ORB::HARRIS_SCORE,
            31,
            20
    );

    g_initialized = true;
    LOGD("Vision engine initialized (ORB)");
}

void vision_release() {
    g_templateGray.release();
    g_templateKeypoints.clear();
    g_templateDescriptors.release();
    g_initialized = false;
    LOGD("Vision engine released");
}

// =======================
// Template Setup (ORB)
// =======================
void vision_set_template(const cv::Mat& templateMat) {
    if (!g_initialized || templateMat.empty()) return;

    cv::cvtColor(templateMat, g_templateGray, cv::COLOR_RGBA2GRAY);

    g_orb->detectAndCompute(
            g_templateGray,
            cv::noArray(),
            g_templateKeypoints,
            g_templateDescriptors
    );

    LOGD("Template ORB ready: keypoints=%zu",
         g_templateKeypoints.size());
}

// =======================
// ORB + Homography Matching
// =======================
bool vision_match_orb(
        const cv::Mat& screenRGBA,
        cv::Rect& outRect,
        float& outScore
) {
    if (g_templateDescriptors.empty()) return false;

    // Convert to gray
    cv::Mat gray;
    cv::cvtColor(screenRGBA, gray, cv::COLOR_RGBA2GRAY);

    // Detect ORB features
    std::vector<cv::KeyPoint> frameKeypoints;
    cv::Mat frameDescriptors;

    g_orb->detectAndCompute(
            gray,
            cv::noArray(),
            frameKeypoints,
            frameDescriptors
    );

    if (frameDescriptors.empty()) return false;

    // Match descriptors
    cv::BFMatcher matcher(cv::NORM_HAMMING);
    std::vector<std::vector<cv::DMatch>> knnMatches;
    matcher.knnMatch(
            g_templateDescriptors,
            frameDescriptors,
            knnMatches,
            2
    );

    // Lowe ratio filter
    std::vector<cv::DMatch> goodMatches;
    for (const auto& m : knnMatches) {
        if (m.size() == 2 && m[0].distance < 0.75f * m[1].distance) {
            goodMatches.push_back(m[0]);
        }
    }

    if (goodMatches.size() < 8) return false;

    // Build point sets
    std::vector<cv::Point2f> objPts, scenePts;
    for (const auto& m : goodMatches) {
        objPts.push_back(g_templateKeypoints[m.queryIdx].pt);
        scenePts.push_back(frameKeypoints[m.trainIdx].pt);
    }

    // Homography
    cv::Mat inlierMask;
    cv::Mat H = cv::findHomography(
            objPts,
            scenePts,
            cv::RANSAC,
            3.0,
            inlierMask
    );

    if (H.empty()) return false;

    int inliers = cv::countNonZero(inlierMask);
    outScore = static_cast<float>(inliers);



// ---- HARD SUCCESS GATE (THIS IS THE FIX) ----
    constexpr int MIN_INLIERS = 12;   // start with 12 (you already see 10–30 in logs)

    if (inliers < MIN_INLIERS) {
        return false;
    }

    // Project template corners
    std::vector<cv::Point2f> corners = {
            {0, 0},
            {(float)g_templateGray.cols, 0},
            {(float)g_templateGray.cols, (float)g_templateGray.rows},
            {0, (float)g_templateGray.rows}
    };

    std::vector<cv::Point2f> projected;
    cv::perspectiveTransform(corners, projected, H);

    outRect = cv::boundingRect(projected);

    LOGD("Homography match: inliers=%d score=%.2f rect=(%d,%d)",
         inliers, outScore, outRect.x, outRect.y);

    return true;
}

// =======================
// JNI BRIDGE
// =======================
extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_screenunderstaingusingml_ndk_VisionBridge_nativeInit(
        JNIEnv* env,
        jobject
) {
    vision_init();
    return env->NewStringUTF("Vision engine ready");
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_screenunderstaingusingml_ndk_VisionBridge_nativeSetTemplate(
        JNIEnv* env,
        jobject,
        jobject bitmap
) {
    cv::Mat mat;
    if (!bitmap_to_mat(env, bitmap, mat)) return;
    vision_set_template(mat);
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_example_screenunderstaingusingml_ndk_VisionBridge_nativeMatch(
        JNIEnv* env,
        jobject,
        jobject bitmap,
        jfloat /*threshold - unused now*/
) {
    cv::Mat screen;
    if (!bitmap_to_mat(env, bitmap, screen)) return nullptr;

    cv::Rect rect;
    float score = 0.f;
    bool matched = vision_match_orb(screen, rect, score);

    jclass cls = env->FindClass(
            "com/example/screenunderstaingusingml/ndk/MatchResultNative"
    );

    jmethodID ctor = env->GetMethodID(cls, "<init>", "(ZFIIII)V");

    if (!ctor) {
        env->ExceptionClear();
        return env->NewObject(cls, env->GetMethodID(cls, "<init>", "()V"));
    }

    return env->NewObject(
            cls,
            ctor,
            matched ? JNI_TRUE : JNI_FALSE,
            score,
            rect.x,
            rect.y,
            rect.width,
            rect.height
    );
}
