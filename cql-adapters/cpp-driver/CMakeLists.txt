cmake_minimum_required(VERSION 3.16)
project(latte-cpp-driver VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Export compile_commands.json for clang-tidy
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

# Build type defaults to Release
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

# Compiler flags
set(CMAKE_CXX_FLAGS_DEBUG "-g -O0 -DDEBUG")
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG -flto")

# Enable LTO for release builds
set(CMAKE_INTERPROCEDURAL_OPTIMIZATION_RELEASE ON)

# Find required packages
find_package(PkgConfig REQUIRED)

# Find libuv
pkg_check_modules(LIBUV REQUIRED libuv)

# Find cpp-driver (cassandra)
# Try pkg-config first, then fall back to manual search
pkg_check_modules(CASSANDRA_DRIVER cassandra-cpp-driver)
if(NOT CASSANDRA_DRIVER_FOUND)
    # Manual search for the library
    find_library(CASSANDRA_DRIVER_LIB
        NAMES cassandra cassandra_static scylla-cpp-driver
        PATHS /usr/local/lib /usr/lib /usr/local/lib64 /usr/lib64
        ENV LD_LIBRARY_PATH
    )
    find_path(CASSANDRA_DRIVER_INCLUDE
        NAMES cassandra.h
        PATHS /usr/local/include /usr/include
        PATH_SUFFIXES cassandra
    )
    if(CASSANDRA_DRIVER_LIB AND CASSANDRA_DRIVER_INCLUDE)
        set(CASSANDRA_DRIVER_LIBRARIES ${CASSANDRA_DRIVER_LIB})
        set(CASSANDRA_DRIVER_INCLUDE_DIRS ${CASSANDRA_DRIVER_INCLUDE})
        message(STATUS "Found cpp-driver: ${CASSANDRA_DRIVER_LIB}")
    else()
        message(FATAL_ERROR "cpp-driver (libcassandra) not found. Please install it or set CASSANDRA_DRIVER_PATH.")
    endif()
endif()

# Source files
set(SOURCES
    src/main.cpp
    src/server.cpp
    src/protocol.cpp
    src/session.cpp
    src/types.cpp
)

# Header files
set(HEADERS
    src/server.h
    src/protocol.h
    src/session.h
    src/types.h
    src/config.h
    src/logging.h
)

# Create executable
add_executable(latte-cpp-driver ${SOURCES} ${HEADERS})

# Include directories
target_include_directories(latte-cpp-driver PRIVATE
    ${CMAKE_SOURCE_DIR}/src
    ${LIBUV_INCLUDE_DIRS}
    ${CASSANDRA_DRIVER_INCLUDE_DIRS}
)

# Link libraries
target_link_libraries(latte-cpp-driver PRIVATE
    ${LIBUV_LIBRARIES}
    ${CASSANDRA_DRIVER_LIBRARIES}
    pthread
)

# Compiler definitions
target_compile_definitions(latte-cpp-driver PRIVATE
    IPC_PROTOCOL_VERSION=1
)

# Enable warnings
target_compile_options(latte-cpp-driver PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    -Werror
    -Wno-unused-parameter
)

# Install target
install(TARGETS latte-cpp-driver DESTINATION bin)
