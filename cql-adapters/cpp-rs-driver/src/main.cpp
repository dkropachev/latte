#include <iostream>

#include "config.h"
#include "server.h"

int main() {
  auto config = latte::DriverConfig::from_env();

  std::cerr << "Starting latte cpp-rs-driver adapter" << std::endl;
  std::cerr << "  Socket: " << config.socket_path << std::endl;
  std::cerr << "  Max inflight: " << config.max_inflight << std::endl;

  return latte::run_server(config);
}
