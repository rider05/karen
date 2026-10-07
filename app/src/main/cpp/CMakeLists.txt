cmake_minimum_required(VERSION 3.22.1)
project(karen_llama)

# Lean core-only build: no tests, tools, examples, server or common utils.
set(LLAMA_BUILD_TESTS OFF)
set(LLAMA_BUILD_TOOLS OFF)
set(LLAMA_BUILD_EXAMPLES OFF)
set(LLAMA_BUILD_SERVER OFF)
set(LLAMA_BUILD_APP OFF)
set(LLAMA_BUILD_COMMON OFF)
set(BUILD_SHARED_LIBS OFF)
set(GGML_BUILD_TESTS OFF)
set(GGML_BUILD_EXAMPLES OFF)

add_subdirectory(llama.cpp)

add_library(karen_llama SHARED karen_llama.cpp)

target_link_libraries(karen_llama PRIVATE llama)

target_include_directories(karen_llama PRIVATE
    llama.cpp/include
    llama.cpp/src
    llama.cpp/ggml/include
)

# Android 15+ install requirement: 16 KB ELF alignment.
if(ANDROID)
    target_link_options(karen_llama PRIVATE "-Wl,-z,max-page-size=16384")
endif()
