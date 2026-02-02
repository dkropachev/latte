#pragma once

#include <cstdlib>
#include <ctime>
#include <iostream>
#include <mutex>
#include <sstream>
#include <string>

namespace latte {

// Log levels - use full names to avoid macro conflicts
enum class LogLevel {
  LOG_TRACE = 0,
  LOG_DEBUG = 1,
  LOG_INFO = 2,
  LOG_WARN = 3,
  LOG_ERROR = 4,
  LOG_OFF = 5,
};

// Global log level (default: INFO)
inline LogLevel &global_log_level() {
  static LogLevel level = LogLevel::LOG_INFO;
  return level;
}

// Thread-safe logging mutex
inline std::mutex &log_mutex() {
  static std::mutex mtx;
  return mtx;
}

// Get log level name
inline const char *log_level_name(LogLevel level) {
  switch (level) {
  case LogLevel::LOG_TRACE:
    return "TRACE";
  case LogLevel::LOG_DEBUG:
    return "DEBUG";
  case LogLevel::LOG_INFO:
    return "INFO";
  case LogLevel::LOG_WARN:
    return "WARN";
  case LogLevel::LOG_ERROR:
    return "ERROR";
  default:
    return "UNKNOWN";
  }
}

// Parse log level from string
inline LogLevel parse_log_level(const std::string &s) {
  if (s == "trace" || s == "TRACE")
    return LogLevel::LOG_TRACE;
  if (s == "debug" || s == "DEBUG")
    return LogLevel::LOG_DEBUG;
  if (s == "info" || s == "INFO")
    return LogLevel::LOG_INFO;
  if (s == "warn" || s == "WARN" || s == "warning" || s == "WARNING")
    return LogLevel::LOG_WARN;
  if (s == "error" || s == "ERROR")
    return LogLevel::LOG_ERROR;
  if (s == "off" || s == "OFF")
    return LogLevel::LOG_OFF;
  return LogLevel::LOG_INFO;
}

// Initialize logging from environment
inline void init_logging() {
  if (const char *env = std::getenv("LATTE_LOG_LEVEL")) {
    global_log_level() = parse_log_level(env);
  } else if (const char *env = std::getenv("RUST_LOG")) {
    // Support RUST_LOG for compatibility
    global_log_level() = parse_log_level(env);
  }
}

// Log message
inline void log_message(LogLevel level, const std::string &message) {
  if (level < global_log_level()) {
    return;
  }

  std::lock_guard<std::mutex> lock(log_mutex());

  // Get current time
  time_t now = time(nullptr);
  struct tm tm_buf;
  localtime_r(&now, &tm_buf);
  char time_str[32];
  strftime(time_str, sizeof(time_str), "%Y-%m-%d %H:%M:%S", &tm_buf);

  std::cerr << time_str << " [" << log_level_name(level) << "] " << message
            << std::endl;
}

} // namespace latte

// Log macros for convenience (defined outside namespace)
#define LOG_TRACE(msg)                                                         \
  do {                                                                         \
    if (latte::LogLevel::LOG_TRACE >= latte::global_log_level()) {             \
      std::ostringstream _oss;                                                 \
      _oss << msg;                                                             \
      latte::log_message(latte::LogLevel::LOG_TRACE, _oss.str());              \
    }                                                                          \
  } while (0)

#define LOG_DEBUG(msg)                                                         \
  do {                                                                         \
    if (latte::LogLevel::LOG_DEBUG >= latte::global_log_level()) {             \
      std::ostringstream _oss;                                                 \
      _oss << msg;                                                             \
      latte::log_message(latte::LogLevel::LOG_DEBUG, _oss.str());              \
    }                                                                          \
  } while (0)

#define LOG_INFO(msg)                                                          \
  do {                                                                         \
    if (latte::LogLevel::LOG_INFO >= latte::global_log_level()) {              \
      std::ostringstream _oss;                                                 \
      _oss << msg;                                                             \
      latte::log_message(latte::LogLevel::LOG_INFO, _oss.str());               \
    }                                                                          \
  } while (0)

#define LOG_WARN(msg)                                                          \
  do {                                                                         \
    if (latte::LogLevel::LOG_WARN >= latte::global_log_level()) {              \
      std::ostringstream _oss;                                                 \
      _oss << msg;                                                             \
      latte::log_message(latte::LogLevel::LOG_WARN, _oss.str());               \
    }                                                                          \
  } while (0)

#define LOG_ERROR(msg)                                                         \
  do {                                                                         \
    if (latte::LogLevel::LOG_ERROR >= latte::global_log_level()) {             \
      std::ostringstream _oss;                                                 \
      _oss << msg;                                                             \
      latte::log_message(latte::LogLevel::LOG_ERROR, _oss.str());              \
    }                                                                          \
  } while (0)
