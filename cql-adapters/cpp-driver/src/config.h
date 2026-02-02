#pragma once

#include <cstdint>
#include <cstdlib>
#include <string>

namespace latte {

struct DriverConfig {
  std::string socket_path;
  uint32_t max_inflight;

  static DriverConfig from_env() {
    DriverConfig config;

    if (const char *env = std::getenv("LATTE_DRIVER_SOCKET")) {
      config.socket_path = env;
    } else {
      config.socket_path = "/tmp/latte-driver.sock";
    }

    if (const char *env = std::getenv("LATTE_DRIVER_INFLIGHT")) {
      config.max_inflight = static_cast<uint32_t>(std::stoul(env));
    } else {
      config.max_inflight = 512;
    }

    return config;
  }
};

} // namespace latte
