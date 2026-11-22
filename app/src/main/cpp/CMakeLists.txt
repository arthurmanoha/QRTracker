
cmake_minimum_required(VERSION 3.22.1)
project(qrtracker_vr)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED True)

add_compile_options(-Wall -Wextra)

find_library(ANDROID_LIB android)
find_library(GLESv2_LIB GLESv2)
find_library(GLESv3_LIB GLESv3)
find_library(LOG_LIB log)

set(LIBS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/libraries)

set(CARDBOARD_LIB
    ${LIBS_DIR}/jni/${ANDROID_ABI}/libGfxPluginCardboard.so
)

set(CARDBOARD_INCLUDE_DIR ${LIBS_DIR})

file(GLOB NATIVE_SOURCES
    "${CMAKE_CURRENT_SOURCE_DIR}/jni/*.cc"
)

add_library(cardboard_jni SHARED ${NATIVE_SOURCES})

target_include_directories(cardboard_jni PRIVATE
    ${CARDBOARD_INCLUDE_DIR}
)

target_link_libraries(cardboard_jni
    ${ANDROID_LIB}
    ${GLESv2_LIB}
    ${GLESv3_LIB}
    ${LOG_LIB}
    ${CARDBOARD_LIB}
)

target_link_options(cardboard_jni PRIVATE
    "-Wl,-z,max-page-size=16384"
)
