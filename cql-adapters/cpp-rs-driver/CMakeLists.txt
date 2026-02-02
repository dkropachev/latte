cmake_minimum_required(VERSION 3.16)
project(latte-cpp-rs-driver VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Build type defaults to Release
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

# Compiler flags
set(CMAKE_CXX_FLAGS_DEBUG "-g -O0 -DDEBUG")
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")

# Find required packages
find_package(PkgConfig REQUIRED)

# Find libuv
pkg_check_modules(LIBUV REQUIRED libuv)

# Find cpp-rs-driver (scylla_cpp_driver)
# Try pkg-config first, then fall back to manual search
pkg_check_modules(SCYLLA_CPP_DRIVER scylla-cpp-driver)
if(NOT SCYLLA_CPP_DRIVER_FOUND)
    # Manual search for the library
    find_library(SCYLLA_CPP_DRIVER_LIB
        NAMES scylla_cpp_driver scylla-cpp-driver cassandra
        PATHS /usr/local/lib /usr/lib /usr/local/lib64 /usr/lib64
        ENV LD_LIBRARY_PATH
    )
    find_path(SCYLLA_CPP_DRIVER_INCLUDE
        NAMES cassandra.h
        PATHS /usr/local/include /usr/include
        PATH_SUFFIXES cassandra
    )
    if(SCYLLA_CPP_DRIVER_LIB AND SCYLLA_CPP_DRIVER_INCLUDE)
        set(SCYLLA_CPP_DRIVER_LIBRARIES ${SCYLLA_CPP_DRIVER_LIB})
        set(SCYLLA_CPP_DRIVER_INCLUDE_DIRS ${SCYLLA_CPP_DRIVER_INCLUDE})
        message(STATUS "Found cpp-rs-driver: ${SCYLLA_CPP_DRIVER_LIB}")
    else()
        message(FATAL_ERROR "cpp-rs-driver (scylla_cpp_driver) not found. Please install it or set CPP_RS_DRIVER_PATH.")
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
)

# Create executable
add_executable(latte-cpp-rs-driver ${SOURCES} ${HEADERS})

# Include directories
target_include_directories(latte-cpp-rs-driver PRIVATE
    ${CMAKE_SOURCE_DIR}/src
    ${LIBUV_INCLUDE_DIRS}
    ${SCYLLA_CPP_DRIVER_INCLUDE_DIRS}
)

# Link libraries
target_link_libraries(latte-cpp-rs-driver PRIVATE
    ${LIBUV_LIBRARIES}
    ${SCYLLA_CPP_DRIVER_LIBRARIES}
    pthread
)

# Compiler definitions
target_compile_definitions(latte-cpp-rs-driver PRIVATE
    IPC_PROTOCOL_VERSION=1
)

# Enable warnings
target_compile_options(latte-cpp-rs-driver PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    -Werror
    -Wno-unused-parameter
)

# Install target
install(TARGETS latte-cpp-rs-driver DESTINATION bin)
