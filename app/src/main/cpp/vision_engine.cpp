#include "vision_engine.h"
#include <android/bitmap.h>
#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <exception>
#include <future>
#include <mutex>

#define LOG_TAG "VisionEngineNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── Global state ──────────────────────────────────────────────────────

std::map<int, TemplateData> g_templates;
std::mutex g_mutex; // Protects g_templates from concurrent access

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
                         int roi_w, int roi_h, float threshold) {
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

  TemplateData data;
  data.templ = gray;
  data.roi = cv::Rect(roi_x, roi_y, roi_w, roi_h);
  data.threshold = (threshold > 0.0f)
                       ? std::max(0.5f, std::min(1.0f, threshold))
                       : 0.75f;
  data.miss_count = 0;
  data.force_fullscreen_once = false;

  std::lock_guard<std::mutex> lock(g_mutex);
  g_templates[id] = data;
  LOGD("Added template ID=%d: %dx%d, ROI=(%d,%d,%d,%d), threshold=%.2f", id,
       gray.cols, gray.rows, roi_x, roi_y, roi_w, roi_h, data.threshold);
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

// Matches a single template against a (possibly ROI-cropped) screen region.
// Returns the match rect in FULL SCREEN coordinates.
static bool match_one(const cv::Mat &screen_gray, const TemplateData &tdata,
                      cv::Rect &out_rect, float &out_score,
                      bool &out_used_fullscreen, int id) {

  if (screen_gray.empty() || tdata.templ.empty())
    return false;

  const cv::Mat &templ_gray = tdata.templ;

  // ── Determine search region ──
  // Use ROI if available and miss_count < 3; otherwise full screen
  cv::Mat search_region;
  int offset_x = 0, offset_y = 0;
  bool using_roi = false;

  bool has_roi = tdata.roi.width > 0 && tdata.roi.height > 0;
  bool should_use_roi =
      has_roi && tdata.miss_count < 3 && !tdata.force_fullscreen_once;

  if (should_use_roi) {
    // Proportional padding: 25% of region dimensions, minimum 30px
    int pad_x = std::max(30, (int)(tdata.roi.width * 0.25f));
    int pad_y = std::max(30, (int)(tdata.roi.height * 0.25f));

    // Expand ROI by padding, clamp to screen bounds
    int x1 = std::max(0, tdata.roi.x - pad_x);
    int y1 = std::max(0, tdata.roi.y - pad_y);
    int x2 =
        std::min(screen_gray.cols, tdata.roi.x + tdata.roi.width + pad_x);
    int y2 =
        std::min(screen_gray.rows, tdata.roi.y + tdata.roi.height + pad_y);

    int crop_w = x2 - x1;
    int crop_h = y2 - y1;

    // ROI crop must be larger than the template
    if (crop_w >= templ_gray.cols && crop_h >= templ_gray.rows) {
      search_region = screen_gray(cv::Rect(x1, y1, crop_w, crop_h));
      offset_x = x1;
      offset_y = y1;
      using_roi = true;
    } else {
      // ROI too small (edge case) — fall back to full screen
      search_region = screen_gray;
    }
  } else {
    if (has_roi && tdata.force_fullscreen_once) {
      LOGD("ID=%d: forced full-screen search for stuck step", id);
    } else if (has_roi && tdata.miss_count >= 3) {
      LOGD("ID=%d: ROI miss #%d, falling back to full-screen search", id,
           tdata.miss_count);
    }
    search_region = screen_gray;
  }

  out_used_fullscreen = !using_roi;

  // Template must fit within search region
  if (templ_gray.cols > search_region.cols ||
      templ_gray.rows > search_region.rows) {
    LOGD("ID=%d: template (%dx%d) larger than search region (%dx%d), skip", id,
         templ_gray.cols, templ_gray.rows, search_region.cols,
         search_region.rows);
    return false;
  }

  float best_score = -1.0f;
  cv::Point best_loc;
  float best_scale = 1.0f;

  // Reduced multi-scale: 3 scales instead of 7
  float scales[] = {1.0f, 0.95f, 1.05f};
  int num_scales = 3;

  for (int s = 0; s < num_scales; s++) {
    float scale = scales[s];

    cv::Mat scaled_templ;
    if (scale == 1.0f) {
      scaled_templ = templ_gray;
    } else {
      int new_w = (int)(templ_gray.cols * scale);
      int new_h = (int)(templ_gray.rows * scale);
      if (new_w <= 0 || new_h <= 0 || new_w > search_region.cols ||
          new_h > search_region.rows)
        continue;
      cv::resize(templ_gray, scaled_templ, cv::Size(new_w, new_h));
    }

    cv::Mat result;
    cv::matchTemplate(search_region, scaled_templ, result,
                      cv::TM_CCOEFF_NORMED);

    double minVal, maxVal;
    cv::Point minLoc, maxLoc;
    cv::minMaxLoc(result, &minVal, &maxVal, &minLoc, &maxLoc);

    if ((float)maxVal > best_score) {
      best_score = (float)maxVal;
      best_loc = maxLoc;
      best_scale = scale;
    }

    // Early exit on strong match at any scale (improved from s==0 && >0.90)
    if (best_score >= 0.85f)
      break;
  }

  out_score = best_score;

  int w = (int)(templ_gray.cols * best_scale);
  int h = (int)(templ_gray.rows * best_scale);

  // Remap coordinates back to full-screen space
  out_rect = cv::Rect(best_loc.x + offset_x, best_loc.y + offset_y, w, h);

  bool matched = best_score >= tdata.threshold;

  LOGD("ID=%d: score=%.3f (threshold=%.2f) scale=%.2f at=(%d,%d) %dx%d %s "
       "[%s]",
       id, best_score, tdata.threshold, best_scale, out_rect.x, out_rect.y, w,
       h, matched ? "MATCHED" : "no match",
       using_roi ? "ROI" : "FULLSCREEN");

  return matched;
}

std::vector<MatchResult> vision_match_all(const cv::Mat &screen) {
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

  LOGD("vision_match_all: screen=%dx%d ch=%d, templates=%zu", screen.cols,
       screen.rows, screen.channels(), templates_snapshot.size());

  cv::Mat screen_gray;
  if (screen.channels() == 4) {
    cv::cvtColor(screen, screen_gray, cv::COLOR_RGBA2GRAY);
  } else if (screen.channels() == 3) {
    cv::cvtColor(screen, screen_gray, cv::COLOR_RGB2GRAY);
  } else {
    screen_gray = screen;
  }

  // Pre-allocate results with correct size
  size_t count = templates_snapshot.size();
  results.resize(count);

  if (count == 1) {
    // Single template — run directly, no pool overhead
    auto it = templates_snapshot.begin();
    int id = it->first;
    TemplateData &tdata = it->second;

    cv::Rect r;
    float score = 0;
    bool used_fullscreen = false;
    bool found = match_one(screen_gray, tdata, r, score, used_fullscreen, id);

    results[0] = {id, found, score, r, used_fullscreen};

    // Update miss count in global templates
    std::lock_guard<std::mutex> lock(g_mutex);
    auto git = g_templates.find(id);
    if (git != g_templates.end()) {
      git->second.force_fullscreen_once = false;
      if (found) {
        git->second.miss_count = 0;
        git->second.roi = r;
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
    std::mutex results_mutex;
    size_t idx = 0;

    for (auto &pair : templates_snapshot) {
      int id = pair.first;
      TemplateData tdata = pair.second; // Copy for thread safety
      size_t result_idx = idx++;

      futures.push_back(pool_submit([&screen_gray, &results, &results_mutex,
                                     tdata, id, result_idx]() {
        cv::Rect r;
        float score = 0;
        bool used_fullscreen = false;
        // tdata is a local copy — safe to read in thread
        bool found =
            match_one(screen_gray, tdata, r, score, used_fullscreen, id);

        MatchResult res;
        res.id = id;
        res.matched = found;
        res.score = score;
        res.rect = r;
        res.used_fullscreen = used_fullscreen;

        std::lock_guard<std::mutex> lock(results_mutex);
        results[result_idx] = res;
      }));
    }

    // Wait for all matches to complete
    for (auto &f : futures) {
      f.get();
    }

    // Update miss counts in global templates
    std::lock_guard<std::mutex> lock(g_mutex);
    for (const auto &res : results) {
      auto git = g_templates.find(res.id);
      if (git != g_templates.end()) {
        git->second.force_fullscreen_once = false;
        if (res.matched) {
          git->second.miss_count = 0;
          git->second.roi = res.rect;
        } else if (res.used_fullscreen) {
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
  LOGD("vision_match_all: %zu templates in %.1fms", count, elapsed_ms);

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
    JNIEnv *env, jobject, jint id, jobject bitmap, jint roiX, jint roiY,
    jint roiW, jint roiH, jfloat threshold) {
  cv::Mat mat;
  if (!bitmap_to_mat(env, bitmap, mat))
    return;
  vision_add_template((int)id, mat, (int)roiX, (int)roiY, (int)roiW,
                      (int)roiH, (float)threshold);
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
