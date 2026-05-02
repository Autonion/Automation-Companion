cmake_minimum_required(VERSION 3.22.1)

project(vision_engine LANGUAGES C CXX)

# Use modern C++
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ------------------------------------------------------------
# OpenCV
# ------------------------------------------------------------

set(OpenCV_DIR
        ${CMAKE_CURRENT_LIST_DIR}/../opencv/native/jni
)

find_package(OpenCV REQUIRED)
include_directories(${OpenCV_INCLUDE_DIRS})
# ------------------------------------------------------------
# Native library
# ------------------------------------------------------------
add_library(
        vision_engine
        SHARED
        vision_engine.cpp
)

# ------------------------------------------------------------
# Includes
# ------------------------------------------------------------
target_include_directories(
        vision_engine
        PRIVATE
        ${OpenCV_INCLUDE_DIRS}
)

# ------------------------------------------------------------
# Link libraries
# ------------------------------------------------------------
target_link_libraries(
        vision_engine
        jnigraphics
        ${OpenCV_LIBS}
        log
)
