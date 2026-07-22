cmake_minimum_required(VERSION 3.22.1)
project(noteapp_whisper LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

get_filename_component(NOTEAPP_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
set(WHISPER_ROOT "${NOTEAPP_ROOT}/third_party/whisper.cpp")

if(NOT EXISTS "${WHISPER_ROOT}/include/whisper.h")
    message(FATAL_ERROR "whisper.cpp submodule is missing. Run: git submodule update --init --recursive")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

add_subdirectory("${WHISPER_ROOT}" "${CMAKE_CURRENT_BINARY_DIR}/whisper" EXCLUDE_FROM_ALL)

add_library(noteapp_whisper SHARED whisper_jni.cpp)
target_include_directories(noteapp_whisper PRIVATE "${WHISPER_ROOT}/include")
target_compile_options(noteapp_whisper PRIVATE
    $<$<CONFIG:Release>:-O3>
    -fvisibility=hidden
    -fvisibility-inlines-hidden
)
target_link_options(noteapp_whisper PRIVATE "-Wl,-z,max-page-size=16384")

find_library(ANDROID_LOG_LIB log)
target_link_libraries(noteapp_whisper PRIVATE whisper ${ANDROID_LOG_LIB})
