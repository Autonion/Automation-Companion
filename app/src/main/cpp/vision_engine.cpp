#include "vision_engine.h"
#include <android/bitmap.h>
#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cstdint>
#include <exception>
#include <future>
#include <cmath>
#include <mutex>

#define LOG_TAG "VisionEngineNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── Global state ──────────────────────────────────────────────────────

std::map<int, TemplateData> g_templates;
std::mutex g_mutex; // Protects g_templates from concurrent access

static constexpr int ROI_MISSES_BEFORE_FULLSCREEN = 4;
static constexpr int64_t FULLSCREEN_FALLBACK_COOLDOWN_MS = 5000;

static int64_t monotonic_ms() {
  return std::chrono::duration_cast<std::chrono::milliseconds>(
             std::chrono::steady_clock::now().time_since_epoch())
      .count();
}

static bool auto_fullscreen_allowed(const TemplateData &tdata,
                                    int64_t now_ms) {
  bool has_roi = tdata.roi.width > 0 && tdata.roi.height > 0;
  return has_roi && tdata.allow_fullscreen_fallback &&
         tdata.miss_count >= ROI_MISSES_BEFORE_FULLSCREEN &&
         now_ms - tdata.last_fullscreen_search_ms >=
             FULLSCREEN_FALLBACK_COOLDOWN_MS;
}

static void limit_auto_fullscreen_fallbacks(
    std::map<int, TemplateData> &templates_snapshot) {
  int selected_id = -1;
  int selected_miss_count = -1;
  int64_t selected_last_fullscreen_ms = 0;
  int64_t now_ms = monotonic_ms();

  for (const auto &pair : templates_snapshot) {
    const TemplateData &tdata = pair.second;
    if (tdata.force_fullscreen_once ||
        !auto_fullscreen_allowed(tdata, now_ms)) {
      continue;
    }

    if (selected_id == -1 || tdata.miss_count > selected_miss_count ||
        (tdata.miss_count == selected_miss_count &&
         tdata.last_fullscreen_search_ms < selected_last_fullscreen_ms)) {
      selected_id = pair.first;
      selected_miss_count = tdata.miss_count;
      selected_last_fullscreen_ms = tdata.last_fullscreen_search_ms;
    }
  }

  if (selected_id == -1)
    return;

  for (auto &pair : templates_snapshot) {
    TemplateData &tdata = pair.second;
    if (!tdata.force_fullscreen_once &&
        auto_fullscreen_allowed(tdata, now_ms) && pair.first != selected_id) {
      tdata.allow_fullscreen_fallback = false;
    }
  }
}

// ── Thread pool ───────────────────────────────────────────────────────

static const int POOL_SIZE = 3; // Suitable for most mobile SoCs (4-8 cores)
static std::vector<std::thread> g_pool;
static std::queue<std::function<void()>> g_tasks;
static std::mutex g_pool_mutex;
static std::condition_variable g_pool_cv;
static bool g_pool_stop = false;

static std::mutex g_match_lifecycle_mutex;
static std::condition_variable g_match_lifecycle_cv;
static int g_active_match_calls = 0;
static bool g_destroying = false;

class MatchLifecycleGuard {
public:
  MatchLifecycleGuard() {
    std::lock_guard<std::mutex> lock(g_match_lifecycle_mutex);
    if (g_destroying)
      return;
    g_active_match_calls++;
    active_ = true;
  }

  ~MatchLifecycleGuard() {
    if (!active_)
      return;
    {
      std::lock_guard<std::mutex> lock(g_match_lifecycle_mutex);
      g_active_match_calls--;
    }
    g_match_lifecycle_cv.notify_all();
  }

  bool active() const { return active_; }

private:
  bool active_ = false;
};

static void pool_worker() {
  while (true) {
    std::function<void()> task;
    {
      std::unique_lock<std::mutex> lock(g_pool_mutex);
      g_pool_cv.wait(lock, [] { return g_pool_stop || !g_tasks.empty(); });
      if (g_pool_stop && g_tasks.empty())
        return;
      task = std::move(g_tasks.front());
      g_tasks.pop();
    }
    task();
  }
}

static void pool_init() {
  std::lock_guard<std::mutex> lock(g_pool_mutex);
  if (!g_pool.empty())
    return; // Already initialized
  g_pool_stop = false;
  for (int i = 0; i < POOL_SIZE; i++) {
    g_pool.emplace_back(pool_worker);
  }
  LOGD("Thread pool initialized: %d workers", POOL_SIZE);
}

static void pool_destroy() {
  {
    std::lock_guard<std::mutex> lock(g_pool_mutex);
    g_pool_stop = true;
  }
  g_pool_cv.notify_all();
  for (auto &t : g_pool) {
    if (t.joinable())
      t.join();
  }
  g_pool.clear();
  // Drain leftover tasks
  std::lock_guard<std::mutex> lock(g_pool_mutex);
  while (!g_tasks.empty())
    g_tasks.pop();
  LOGD("Thread pool destroyed");
}

static std::future<void> pool_submit(std::function<void()> task) {
  auto promise = std::make_shared<std::promise<void>>();
  auto future = promise->get_future();
  {
    std::lock_guard<std::mutex> lock(g_pool_mutex);
    g_tasks.push([task = std::move(task), promise]() {
      try {
        task();
        promise->set_value();
      } catch (...) {
        promise->set_exception(std::current_exception());
      }
    });
  }
  g_pool_cv.notify_one();
  return future;
}

// ── Core functions ────────────────────────────────────────────────────

void vision_init() {
  {
    std::lock_guard<std::mutex> lifecycle_lock(g_match_lifecycle_mutex);
    g_destroying = false;
  }
  std::lock_guard<std::mutex> lock(g_mutex);
  g_templates.clear();
  pool_init();
  LOGD("Vision Engine Initialized (Template Matching + Thread Pool)");
}

void vision_add_template(int id, const cv::Mat &templ, int roi_x, int roi_y,
                         int roi_w, int roi_h, float threshold,
                         bool allow_fullscreen_fallback,
                         bool track_roi_to_match, bool moving) {
  if (templ.empty())
    return;
  cv::Mat match_templ;
  cv::Mat alpha_mask;
  if (templ.channels() == 4) {
    cv::cvtColor(templ, match_templ, cv::COLOR_RGBA2RGB);
    std::vector<cv::Mat> channels;
    cv::split(templ, channels);
    if (channels.size() == 4) {
      double min_alpha = 255.0;
      double max_alpha = 255.0;
      cv::minMaxLoc(channels[3], &min_alpha, &max_alpha);
      if (min_alpha < 255.0 && max_alpha > 0.0) {
        cv::threshold(channels[3], alpha_mask, 200, 255, cv::THRESH_BINARY);
      }
    }
  } else if (templ.channels() == 3) {
    match_templ = templ.clone();
  } else {
    match_templ = templ.clone();
  }

  TemplateData data;
  data.templ = match_templ;
  data.mask = alpha_mask;
  data.roi = cv::Rect(roi_x, roi_y, roi_w, roi_h);
  data.threshold = (threshold > 0.0f)
                       ? std::max(0.5f, std::min(1.0f, threshold))
                       : 0.75f;
  data.miss_count = 0;
  data.last_fullscreen_search_ms = 0;
  data.allow_fullscreen_fallback = allow_fullscreen_fallback;
  data.force_fullscreen_once = false;
  data.track_roi_to_match = track_roi_to_match;
  if (moving) {
    data.moving = std::make_shared<MovingMatcher>(match_templ, alpha_mask,
                                                 data.roi, data.threshold);
    data.track_roi_to_match = false;
    data.allow_fullscreen_fallback = false;
  } else {
    data.fast = std::make_shared<FastMatcher>(match_templ, alpha_mask, data.threshold);
  }

  std::lock_guard<std::mutex> lock(g_mutex);
  g_templates[id] = data;
  LOGD("Added template ID=%d: %dx%d, ROI=(%d,%d,%d,%d), threshold=%.2f, "
       "autoFullscreen=%s, mask=%s, trackRoi=%s",
       id, match_templ.cols, match_templ.rows, roi_x, roi_y, roi_w, roi_h,
       data.threshold,
       data.allow_fullscreen_fallback ? "true" : "false",
       data.mask.empty() ? "false" : "true",
       data.track_roi_to_match ? "true" : "false");
}

void vision_clear_templates() {
  std::lock_guard<std::mutex> lock(g_mutex);
  g_templates.clear();
  LOGD("Cleared all templates");
}

void vision_destroy() {
  {
    std::unique_lock<std::mutex> lifecycle_lock(g_match_lifecycle_mutex);
    g_destroying = true;
    g_match_lifecycle_cv.wait(lifecycle_lock,
                              [] { return g_active_match_calls == 0; });
  }
  vision_clear_templates();
  pool_destroy();
  {
    std::lock_guard<std::mutex> lifecycle_lock(g_match_lifecycle_mutex);
    g_destroying = false;
  }
  LOGD("Vision Engine destroyed");
}

void vision_request_fullscreen_search(int id) {
  std::lock_guard<std::mutex> lock(g_mutex);
  auto it = g_templates.find(id);
  if (it != g_templates.end()) {
    it->second.force_fullscreen_once = true;
    LOGD("ID=%d: full-screen search requested for next cycle", id);
  }
}

// ── Template matching with ROI restriction ────────────────────────────

static std::vector<MatchResult>
match_all_occurrences(const cv::Mat &screen, const TemplateData &data, int id) {
  if (screen.empty() || !data.fast) return {};
  cv::Rect roi({}, screen.size());
  const bool has_roi = data.roi.width > 0 && data.roi.height > 0;
  const bool using_roi = has_roi && !data.force_fullscreen_once &&
                         !auto_fullscreen_allowed(data, monotonic_ms());
  if (using_roi) {
    const bool adaptive = data.track_roi_to_match || data.allow_fullscreen_fallback;
    const int px = adaptive ? std::min(30, std::max(8, data.roi.width / 10)) : 0;
    const int py = adaptive ? std::min(30, std::max(8, data.roi.height / 10)) : 0;
    roi = cv::Rect(data.roi.x - px, data.roi.y - py,
                   data.roi.width + 2 * px, data.roi.height + 2 * py) & roi;
  }
  if (roi.width < data.templ.cols || roi.height < data.templ.rows)
    return {{id, false, 0.0f, {}, !using_roi}};
  float best_score = 0;
  std::vector<MatchResult> results;
  for (const auto &hit : data.fast->match(screen(roi), best_score)) {
    results.push_back({id, true, hit.score, hit.rect + roi.tl(), !using_roi});
  }
  if (results.empty()) results.push_back({id, false, best_score, {}, !using_roi});
  return results;
}

static std::vector<MatchResult> match_template(const cv::Mat &screen,
    const TemplateData &data, int id, int64_t frame_ms) {
  if (!data.moving) return match_all_occurrences(screen, data, id);
  cv::Mat rgb = screen;
  if (screen.channels() == 4) cv::cvtColor(screen, rgb, cv::COLOR_RGBA2RGB);
  std::vector<MatchResult> results;
  for (const auto &hit : data.moving->match(rgb, frame_ms)) {
    results.push_back({id, true, hit.score, hit.rect, false, hit.track_id,
                       hit.velocity_x, hit.velocity_y, hit.observations});
  }
  return results;
}

std::vector<MatchResult> vision_match_all(const cv::Mat &screen, int64_t frame_ms) {
  std::vector<MatchResult> results;
  if (screen.empty())
    return results;

  MatchLifecycleGuard match_guard;
  if (!match_guard.active())
    return results;

  auto t_start = std::chrono::steady_clock::now();

  // Take a snapshot of templates under lock — then match without holding lock
  std::map<int, TemplateData> templates_snapshot;
  {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_templates.empty())
      return results;
    templates_snapshot = g_templates; // Mat uses refcount, TemplateData is cheap
  }
  limit_auto_fullscreen_fallbacks(templates_snapshot);

  const cv::Mat &screen_match = screen;
  if (screen.channels() != 4 && screen.channels() != 3 && screen.channels() != 1) {
    LOGD("vision_match_all: unsupported channel count %d", screen.channels());
    return results;
  }

  size_t count = templates_snapshot.size();

  if (count == 1) {
    // Single template — run directly, no pool overhead
    auto it = templates_snapshot.begin();
    int id = it->first;
    TemplateData &tdata = it->second;

    std::vector<MatchResult> template_results =
        match_template(screen_match, tdata, id, frame_ms);
    results.insert(results.end(), template_results.begin(),
                   template_results.end());

    // Update miss count in global templates
    std::lock_guard<std::mutex> lock(g_mutex);
    int64_t state_now_ms = monotonic_ms();
    auto git = g_templates.find(id);
    if (git != g_templates.end()) {
      git->second.force_fullscreen_once = false;
      bool used_fullscreen =
          !template_results.empty() && template_results.front().used_fullscreen;
      if (used_fullscreen) {
        git->second.last_fullscreen_search_ms = state_now_ms;
      }
      auto best_it = std::max_element(
          template_results.begin(), template_results.end(),
          [](const MatchResult &a, const MatchResult &b) {
            return a.score < b.score;
          });
      bool found = best_it != template_results.end() && best_it->matched;
      if (found) {
        git->second.miss_count = 0;
        if (git->second.track_roi_to_match) {
          git->second.roi = best_it->rect;
        }
      } else if (used_fullscreen) {
        git->second.miss_count = 0;
      } else {
        git->second.miss_count++;
      }
    }
  } else {
    // Multiple templates — dispatch to thread pool
    pool_init();
    std::vector<std::future<void>> futures;
    std::vector<std::vector<MatchResult>> per_template_results(count);
    size_t idx = 0;

    for (auto &pair : templates_snapshot) {
      int id = pair.first;
      TemplateData tdata = pair.second; // Copy for thread safety
      size_t result_idx = idx++;

      futures.push_back(pool_submit([&screen_match, &per_template_results,
                                     tdata, id, result_idx, frame_ms]() {
        per_template_results[result_idx] =
            match_template(screen_match, tdata, id, frame_ms);
      }));
    }

    // Wait for all matches to complete
    std::exception_ptr failure;
    for (auto &f : futures) {
      try { f.get(); } catch (...) {
        if (!failure) failure = std::current_exception();
      }
    }
    if (failure) std::rethrow_exception(failure);

    for (const auto &template_results : per_template_results) {
      results.insert(results.end(), template_results.begin(),
                     template_results.end());
    }

    // Update miss counts in global templates
    std::lock_guard<std::mutex> lock(g_mutex);
    int64_t state_now_ms = monotonic_ms();
    idx = 0;
    for (const auto &pair : templates_snapshot) {
      int id = pair.first;
      const auto &template_results = per_template_results[idx++];
      auto git = g_templates.find(id);
      if (git != g_templates.end()) {
        git->second.force_fullscreen_once = false;
        bool used_fullscreen =
            !template_results.empty() && template_results.front().used_fullscreen;
        if (used_fullscreen) {
          git->second.last_fullscreen_search_ms = state_now_ms;
        }
        auto best_it = std::max_element(
            template_results.begin(), template_results.end(),
            [](const MatchResult &a, const MatchResult &b) {
              return a.score < b.score;
            });
        bool found = best_it != template_results.end() && best_it->matched;
        if (found) {
          git->second.miss_count = 0;
          if (git->second.track_roi_to_match) {
            git->second.roi = best_it->rect;
          }
        } else if (used_fullscreen) {
          git->second.miss_count = 0;
        } else {
          git->second.miss_count++;
        }
      }
    }
  }

  auto t_end = std::chrono::steady_clock::now();
  double elapsed_ms =
      std::chrono::duration<double, std::milli>(t_end - t_start).count();
  static int64_t last_timing_ms = 0;
  const int64_t now_ms = monotonic_ms();
  if (now_ms - last_timing_ms >= 2000) {
    LOGD("scan=%dx%d templates=%zu match=%.1fms hits=%zu", screen.cols,
         screen.rows, count, elapsed_ms,
         std::count_if(results.begin(), results.end(), [](const auto &r) { return r.matched; }));
    last_timing_ms = now_ms;
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
  if (!pixels) {
    AndroidBitmap_unlockPixels(env, bitmap);
    return false;
  }

  // Deep copy so we can safely unlock
  cv::Mat view(info.height, info.width, CV_8UC4, pixels, info.stride);
  try {
    view.copyTo(dst);
  } catch (...) {
    AndroidBitmap_unlockPixels(env, bitmap);
    throw;
  }

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
    JNIEnv *env, jobject, jint id, jobject bitmap, jint roiX, jint roiY,
    jint roiW, jint roiH, jfloat threshold, jboolean allowFullscreenFallback,
    jboolean trackRoiToMatch, jboolean moving) {
  try {
  cv::Mat mat;
  if (!bitmap_to_mat(env, bitmap, mat))
    return;
  vision_add_template((int)id, mat, (int)roiX, (int)roiY, (int)roiW,
                      (int)roiH, (float)threshold,
                      allowFullscreenFallback == JNI_TRUE,
                      trackRoiToMatch == JNI_TRUE, moving == JNI_TRUE);
  } catch (const std::exception &e) {
    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), e.what());
  }
}

JNIEXPORT void JNICALL
Java_com_autonion_automationcompanion_core_vision_VisionNativeBridge_nativeClearTemplates(
    JNIEnv *env, jobject) {
  vision_clear_templates();
}

JNIEXPORT void JNICALL
Java_com_autonion_automationcompanion_core_vision_VisionNativeBridge_nativeDestroy(
    JNIEnv *env, jobject) {
  vision_destroy();
}

JNIEXPORT void JNICALL
Java_com_autonion_automationcompanion_core_vision_VisionNativeBridge_nativeRequestFullscreenSearch(
    JNIEnv *env, jobject, jint id) {
  vision_request_fullscreen_search((int)id);
}

static jobjectArray java_results(JNIEnv *env, const std::vector<MatchResult> &results) {
  // Create Java Array of MatchResultNative
  jclass cls = env->FindClass(
      "com/autonion/automationcompanion/core/vision/MatchResultNative");
  if (!cls)
    return nullptr;

  jmethodID ctor = env->GetMethodID(cls, "<init>", "(IZFIIIIIFFI)V");
  if (!ctor)
    return nullptr;

  jobjectArray jobjArray =
      env->NewObjectArray((jsize)results.size(), cls, nullptr);

  for (size_t i = 0; i < results.size(); ++i) {
    jobject obj = env->NewObject(
        cls, ctor, (jint)results[i].id,
        results[i].matched ? JNI_TRUE : JNI_FALSE, (jfloat)results[i].score,
        (jint)results[i].rect.x, (jint)results[i].rect.y,
        (jint)results[i].rect.width, (jint)results[i].rect.height,
        (jint)results[i].track_id, (jfloat)results[i].velocity_x,
        (jfloat)results[i].velocity_y, (jint)results[i].observations);
    env->SetObjectArrayElement(jobjArray, (jsize)i, obj);
    env->DeleteLocalRef(obj);
  }

  return jobjArray;
}

JNIEXPORT jobjectArray JNICALL
Java_com_autonion_automationcompanion_core_vision_VisionNativeBridge_nativeMatch(
    JNIEnv *env, jobject, jobject bitmap, jlong frameMs) {
  try {
    cv::Mat screen;
    if (!bitmap_to_mat(env, bitmap, screen)) return java_results(env, {});
    return java_results(env, vision_match_all(screen, frameMs));
  } catch (const std::exception &e) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
    return nullptr;
  }
}

JNIEXPORT jobjectArray JNICALL
Java_com_autonion_automationcompanion_core_vision_VisionNativeBridge_nativeMatchRgba(
    JNIEnv *env, jobject, jobject buffer, jint width, jint height, jint rowStride,
    jlong frameMs) {
  auto *pixels = env->GetDirectBufferAddress(buffer);
  const auto capacity = env->GetDirectBufferCapacity(buffer);
  const int64_t needed = static_cast<int64_t>(height - 1) * rowStride + width * 4LL;
  if (!pixels || width <= 0 || height <= 0 || rowStride < width * 4LL || capacity < needed) {
    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid RGBA frame buffer");
    return nullptr;
  }
  try {
    // The caller holds the Image open until this synchronous call returns.
    cv::Mat screen(height, width, CV_8UC4, pixels, rowStride);
    return java_results(env, vision_match_all(screen, frameMs));
  } catch (const std::exception &e) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
    return nullptr;
  }
}

JNIEXPORT void JNICALL
Java_com_autonion_automationcompanion_core_vision_VisionNativeBridge_nativeResetMotion(
    JNIEnv *, jobject) {
  std::lock_guard<std::mutex> lock(g_mutex);
  for (auto &pair : g_templates) {
    if (pair.second.moving) pair.second.moving->reset();
  }
}
}
