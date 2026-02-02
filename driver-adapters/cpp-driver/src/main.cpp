#include <iostream>

#include "config.h"
#include "server.h"

int main() {
  auto config = latte::DriverConfig::from_env();

  std::cerr << "Starting latte cpp-driver adapter" << std::endl;
  std::cerr << "  Socket: " << config.socket_path << std::endl;
  std::cerr << "  Contact points: " << config.contact_points << std::endl;
  std::cerr << "  Max inflight: " << config.max_inflight << std::endl;
  if (!config.keyspace.empty()) {
    std::cerr << "  Keyspace: " << config.keyspace << std::endl;
  }

  return latte::run_server(config);
}
