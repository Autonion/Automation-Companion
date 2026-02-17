#include "vision_engine.h"
#include <android/bitmap.h>
#include <android/log.h>

#define LOG_TAG "VisionEngineNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global state — store grayscale templates
std::map<int, cv::Mat> g_templates;

void vision_init() {
  g_templates.clear();
  LOGD("Vision Engine Initialized (Template Matching)");
}

void vision_add_template(int id, const cv::Mat &templ) {
  if (templ.empty())
    return;
  cv::Mat gray;
  if (templ.channels() == 4) {
    cv::cvtColor(templ, gray, cv::COLOR_RGBA2GRAY);
  } else if (templ.channels() == 3) {
    cv::cvtColor(templ, gray, cv::COLOR_RGB2GRAY);
  } else {
    gray = templ.clone();
  }
  g_templates[id] = gray;
  LOGD("Added template ID=%d: %dx%d", id, gray.cols, gray.rows);
}

void vision_clear_templates() {
  g_templates.clear();
  LOGD("Cleared all templates");
}

// Template matching with optional multi-scale
// Returns: matched, score (0-1.0), and bounding rect in screen coords
bool match_one(const cv::Mat &screen_gray, const cv::Mat &templ_gray,
               cv::Rect &out_rect, float &out_score, int id) {

  if (screen_gray.empty() || templ_gray.empty())
    return false;

  // Template must be smaller than screen
  if (templ_gray.cols > screen_gray.cols ||
      templ_gray.rows > screen_gray.rows) {
    LOGD("ID=%d: template (%dx%d) larger than screen (%dx%d), skipping", id,
         templ_gray.cols, templ_gray.rows, screen_gray.cols, screen_gray.rows);
    return false;
  }

  float best_score = -1.0f;
  cv::Point best_loc;
  float best_scale = 1.0f;

  // Try multiple scales to handle slight DPI/resolution differences
  // Scales: 0.8, 0.9, 1.0, 1.1, 1.2
  float scales[] = {1.0f, 0.95f, 1.05f, 0.9f, 1.1f, 0.85f, 1.15f};
  int num_scales = 7;

  for (int s = 0; s < num_scales; s++) {
    float scale = scales[s];

    cv::Mat scaled_templ;
    if (scale == 1.0f) {
      scaled_templ = templ_gray;
    } else {
      int new_w = (int)(templ_gray.cols * scale);
      int new_h = (int)(templ_gray.rows * scale);
      if (new_w <= 0 || new_h <= 0 || new_w > screen_gray.cols ||
          new_h > screen_gray.rows)
        continue;
      cv::resize(templ_gray, scaled_templ, cv::Size(new_w, new_h));
    }

    cv::Mat result;
    cv::matchTemplate(screen_gray, scaled_templ, result, cv::TM_CCOEFF_NORMED);

    double minVal, maxVal;
    cv::Point minLoc, maxLoc;
    cv::minMaxLoc(result, &minVal, &maxVal, &minLoc, &maxLoc);

    if ((float)maxVal > best_score) {
      best_score = (float)maxVal;
      best_loc = maxLoc;
      best_scale = scale;
    }

    // Early exit if we find a very good match at scale 1.0
    if (s == 0 && best_score > 0.90f)
      break;
  }

  out_score = best_score;

  int w = (int)(templ_gray.cols * best_scale);
  int h = (int)(templ_gray.rows * best_scale);
  out_rect = cv::Rect(best_loc.x, best_loc.y, w, h);

  // Threshold for considering a match valid
  const float MATCH_THRESHOLD = 0.75f;
  bool matched = best_score >= MATCH_THRESHOLD;

  LOGD("ID=%d: score=%.3f (threshold=%.2f) scale=%.2f at=(%d,%d) %dx%d %s", id,
       best_score, MATCH_THRESHOLD, best_scale, best_loc.x, best_loc.y, w, h,
       matched ? "MATCHED" : "no match");

  return matched;
}

std::vector<MatchResult> vision_match_all(const cv::Mat &screen) {
  std::vector<MatchResult> results;
  if (screen.empty() || g_templates.empty())
    return results;

  LOGD("vision_match_all: screen=%dx%d ch=%d, templates=%zu", screen.cols,
       screen.rows, screen.channels(), g_templates.size());

  cv::Mat screen_gray;
  if (screen.channels() == 4) {
    cv::cvtColor(screen, screen_gray, cv::COLOR_RGBA2GRAY);
  } else if (screen.channels() == 3) {
    cv::cvtColor(screen, screen_gray, cv::COLOR_RGB2GRAY);
  } else {
    screen_gray = screen;
  }

  for (const auto &pair : g_templates) {
    int id = pair.first;
    const cv::Mat &tmpl = pair.second;

    cv::Rect r;
    float score = 0;
    bool found = match_one(screen_gray, tmpl, r, score, id);

    MatchResult res;
    res.id = id;
    res.matched = found;
    res.score = score;
    res.rect = r;
    results.push_back(res);
  }
  return results;
}

// ── JNI Helpers ───────────────────────────────────────────────────────

bool bitmap_to_mat(JNIEnv *env, jobject bitmap, cv::Mat &dst) {
  AndroidBitmapInfo info;
  void *pixels = 0;

  if (AndroidBitmap_getInfo(env, bitmap, &info) < 0)
    return false;
  if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
    return false;
  if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0)
    return false;
  if (!pixels)
    return false;

  // Deep copy so we can safely unlock
  cv::Mat view(info.height, info.width, CV_8UC4, pixels);
  view.copyTo(dst);

  AndroidBitmap_unlockPixels(env, bitmap);
  return true;
}

// ── JNI Exports ───────────────────────────────────────────────────────

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_autonion_automationcompanion_core_vision_VisionNativeBridge_nativeInit(
    JNIEnv *env, jobject) {
  vision_init();
  return env->NewStringUTF("Vision Engine Initialized (Template Matching)");
}

JNIEXPORT void JNICALL
Java_com_autonion_automationcompanion_core_vision_VisionNativeBridge_nativeAddTemplate(
    JNIEnv *env, jobject, jint id, jobject bitmap) {
  cv::Mat mat;
  if (!bitmap_to_mat(env, bitmap, mat))
    return;
  vision_add_template((int)id, mat);
}

JNIEXPORT void JNICALL
Java_com_autonion_automationcompanion_core_vision_VisionNativeBridge_nativeClearTemplates(
    JNIEnv *env, jobject) {
  vision_clear_templates();
}

JNIEXPORT jobjectArray JNICALL
Java_com_autonion_automationcompanion_core_vision_VisionNativeBridge_nativeMatch(
    JNIEnv *env, jobject, jobject bitmap) {

  cv::Mat screen;
  if (!bitmap_to_mat(env, bitmap, screen))
    return nullptr;

  std::vector<MatchResult> results = vision_match_all(screen);

  // Create Java Array of MatchResultNative
  jclass cls = env->FindClass(
      "com/autonion/automationcompanion/core/vision/MatchResultNative");
  if (!cls)
    return nullptr;

  // Constructor: (IZFIIII)V  -> id, matched, score, x, y, w, h
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(IZFIIII)V");
  if (!ctor)
    return nullptr;

  jobjectArray jobjArray =
      env->NewObjectArray((jsize)results.size(), cls, nullptr);

  for (size_t i = 0; i < results.size(); ++i) {
    jobject obj = env->NewObject(
        cls, ctor, (jint)results[i].id,
        results[i].matched ? JNI_TRUE : JNI_FALSE, (jfloat)results[i].score,
        (jint)results[i].rect.x, (jint)results[i].rect.y,
        (jint)results[i].rect.width, (jint)results[i].rect.height);
    env->SetObjectArrayElement(jobjArray, (jsize)i, obj);
    env->DeleteLocalRef(obj);
  }

  return jobjArray;
}
}
