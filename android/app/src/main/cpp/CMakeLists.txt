cmake_minimum_required(VERSION 3.16.9)
project("rpcs3-android")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_POSITION_INDEPENDENT_CODE on)

# Build the Android frontend against the source tree that contains this
# project. Keeping the frontend in-tree avoids a second RPCS3 submodule and
# ensures TeknoParrot Arcade changes are present in every APK.
get_filename_component(RPCS3_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../.." ABSOLUTE)

set(FFMPEG_VERSION 5.1)
set(FFMPEG_SHA256 "2b5b6d028a2b2b57b17da26c1050aea8dc46b6302d05517c87bf75e184b6a3a6")
set(TURNIP_VERSION "gen8-v33")
set(TURNIP_SHA256 "b8b67b04c1ba1e5243faa693eaed813da24f3203c2b11b52664dec00c5e66f43")
set(TURNIP_URL "https://github.com/StevenMXZ/Adreno-Tools-Drivers/releases/download/v33/Turnip_Gen8_V33.zip")

include(FetchContent)
set(VOLK_PULL_IN_VULKAN ON CACHE BOOL "" FORCE)
set(VOLK_STATIC_DEFINES VK_USE_PLATFORM_ANDROID_KHR CACHE STRING "" FORCE)
FetchContent_Declare(
    volk
    GIT_REPOSITORY https://github.com/zeux/volk.git
    GIT_TAG 4f3bcee79618a9abe79f4c717c50379197c77512
)
FetchContent_MakeAvailable(volk)

FetchContent_Declare(
    adrenotools
    GIT_REPOSITORY https://github.com/bylaws/libadrenotools.git
    GIT_TAG 8fae8ce254dfc1344527e05301e43f37dea2df80
    GIT_SUBMODULES lib/linkernsbypass
    GIT_SUBMODULES_RECURSE TRUE
)
FetchContent_MakeAvailable(adrenotools)

set(TURNIP_ARCHIVE "${CMAKE_BINARY_DIR}/mesa-turnip-android-${TURNIP_VERSION}.zip")
set(TURNIP_PATH "${CMAKE_BINARY_DIR}/mesa-turnip-android-${TURNIP_VERSION}")
set(TURNIP_LIBRARY "${TURNIP_PATH}/libvulkan_freedreno.so")

if(NOT EXISTS "${TURNIP_ARCHIVE}")
    message(STATUS "Downloading Mesa Turnip ${TURNIP_VERSION}")
    file(DOWNLOAD
        "${TURNIP_URL}"
        "${TURNIP_ARCHIVE}"
        EXPECTED_HASH SHA256=${TURNIP_SHA256}
        TLS_VERIFY ON
        SHOW_PROGRESS
    )
endif()

file(SHA256 "${TURNIP_ARCHIVE}" TURNIP_ACTUAL_SHA256)
if(NOT TURNIP_ACTUAL_SHA256 STREQUAL TURNIP_SHA256)
    message(FATAL_ERROR "Mesa Turnip archive SHA-256 mismatch")
endif()

if(NOT EXISTS "${TURNIP_LIBRARY}")
    file(MAKE_DIRECTORY "${TURNIP_PATH}")
    execute_process(
        COMMAND "${CMAKE_COMMAND}" -E tar xf "${TURNIP_ARCHIVE}"
        WORKING_DIRECTORY "${TURNIP_PATH}"
        RESULT_VARIABLE TURNIP_EXTRACT_RESULT
    )
    if(NOT TURNIP_EXTRACT_RESULT EQUAL 0)
        message(FATAL_ERROR "Mesa Turnip archive extraction failed: ${TURNIP_EXTRACT_RESULT}")
    endif()
endif()

if(NOT EXISTS "${TURNIP_LIBRARY}")
    message(FATAL_ERROR "Mesa Turnip library was not extracted")
endif()

if (TEST_OVERRIDE_CPU)
    if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64")
        set(ARCH_FLAGS "-mcpu=cortex-a53")
    else()
        set(ARCH_FLAGS "-mno-avx")
    endif()


    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${ARCH_FLAGS}")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} ${ARCH_FLAGS}")
    set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} ${ARCH_FLAGS}")
    set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} ${ARCH_FLAGS}")
endif()

if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64")
    set(RPCS3_DOWNLOAD_ARCH "arm64-v8a")
else()
    set(RPCS3_DOWNLOAD_ARCH "x86-64")
endif()

if(NOT EXISTS ${CMAKE_BINARY_DIR}/ffmpeg-${FFMPEG_VERSION}.tar.gz)
    message(STATUS "Downloading ffmpeg-${FFMPEG_VERSION}")
    file(DOWNLOAD
        https://github.com/RPCS3-Android/ffmpeg-android/releases/download/${FFMPEG_VERSION}/ffmpeg-${RPCS3_DOWNLOAD_ARCH}-Android.tar.gz
        ${CMAKE_BINARY_DIR}/ffmpeg-${FFMPEG_VERSION}.tar.gz
        EXPECTED_HASH SHA256=${FFMPEG_SHA256}
        TLS_VERIFY ON
        SHOW_PROGRESS
    )
endif()

file(SHA256 ${CMAKE_BINARY_DIR}/ffmpeg-${FFMPEG_VERSION}.tar.gz FFMPEG_ACTUAL_SHA256)
if(NOT FFMPEG_ACTUAL_SHA256 STREQUAL FFMPEG_SHA256)
    message(FATAL_ERROR "FFmpeg archive SHA-256 mismatch")
endif()

set(FFMPEG_PATH "${CMAKE_BINARY_DIR}/ffmpeg-${FFMPEG_VERSION}")

make_directory(${FFMPEG_PATH})
add_custom_command(
    OUTPUT ${FFMPEG_PATH}/src
    COMMAND ${CMAKE_COMMAND} -E tar xzf ${CMAKE_BINARY_DIR}/ffmpeg-${FFMPEG_VERSION}.tar.gz
    DEPENDS ${CMAKE_BINARY_DIR}/ffmpeg-${FFMPEG_VERSION}.tar.gz
    WORKING_DIRECTORY ${FFMPEG_PATH}
    COMMENT "Unpacking ${CMAKE_BINARY_DIR}/ffmpeg-${FFMPEG_VERSION}.tar.gz"
)

add_custom_target(ffmpeg-unpack DEPENDS ${FFMPEG_PATH}/src)

function(import_ffmpeg_library name)
    add_custom_command(
        OUTPUT "${FFMPEG_PATH}/lib${name}/lib${name}.a"
        DEPENDS ffmpeg-unpack
        WORKING_DIRECTORY ${FFMPEG_PATH}
    )

    add_custom_target(ffmpeg-unpack-${name} DEPENDS "${FFMPEG_PATH}/lib${name}/lib${name}.a")
    add_library(ffmpeg::${name} STATIC IMPORTED GLOBAL)
    set_property(TARGET ffmpeg::${name} PROPERTY IMPORTED_LOCATION "${FFMPEG_PATH}/lib${name}/lib${name}.a")
    set_property(TARGET ffmpeg::${name} PROPERTY INTERFACE_INCLUDE_DIRECTORIES "${FFMPEG_PATH}" "${FFMPEG_PATH}/src")
    add_dependencies(ffmpeg::${name} ffmpeg-unpack-${name})
endfunction()

import_ffmpeg_library(avcodec)
import_ffmpeg_library(avformat)
import_ffmpeg_library(avfilter)
import_ffmpeg_library(avdevice)
import_ffmpeg_library(avutil)
import_ffmpeg_library(swscale)
import_ffmpeg_library(swresample)

add_library(3rdparty_ffmpeg INTERFACE)
target_link_libraries(3rdparty_ffmpeg INTERFACE
    ffmpeg::avformat
    ffmpeg::avcodec
    ffmpeg::avutil
    ffmpeg::swscale
    ffmpeg::swresample
)

add_dependencies(3rdparty_ffmpeg ffmpeg-unpack)

set(USE_SYSTEM_LIBUSB off)
set(USE_SYSTEM_CURL off)
set(USE_DISCORD_RPC off)
set(USE_SYSTEM_OPENCV off)
set(USE_SYSTEM_FFMPEG off)
set(USE_FAUDIO off)
set(USE_SDL off)
set(LLVM_HOST_TRIPLE "${CMAKE_SYSTEM_PROCESSOR}-none-linux-android${ANDROID_NATIVE_API_LEVEL}" CACHE STRING "" FORCE)
set(LLVM_DEFAULT_TARGET_TRIPLE "${LLVM_HOST_TRIPLE}" CACHE STRING "" FORCE)
set(BUILD_LLVM on)
set(STATIC_LINK_LLVM on)
set(DISABLE_LTO on)
set(USE_LTO off)
set(USE_OPENSL off)
set(ASMJIT_NO_SHM_OPEN on)
set(USE_SYSTEM_ZLIB on)
set(USE_LIBEVDEV off)

add_subdirectory("${RPCS3_ROOT}" "${CMAKE_BINARY_DIR}/rpcs3" EXCLUDE_FROM_ALL)

# Android Clang does not infer the noreturn property from RPCS3's temporary
# throw_exception helper when exceptions are disabled. These remain warnings
# rather than blocking an otherwise valid core build.
target_compile_options(rpcs3_emu PRIVATE
    -Wno-error=return-type
    -Wno-error=implicit-fallthrough
)

# VulkanAndroidLoader.cpp is part of rpcs3_emu, so make the custom-driver
# headers and static implementation available to that target directly.
target_link_libraries(rpcs3_emu PRIVATE adrenotools)

add_library(${CMAKE_PROJECT_NAME} SHARED
    native-lib.cpp
    iso.cpp
    "${RPCS3_ROOT}/rpcs3/Input/android_pad_handler.cpp"
    "${RPCS3_ROOT}/rpcs3/Input/ds3_pad_handler.cpp"
    "${RPCS3_ROOT}/rpcs3/Input/ds4_pad_handler.cpp"
    "${RPCS3_ROOT}/rpcs3/Input/dualsense_pad_handler.cpp"
    "${RPCS3_ROOT}/rpcs3/Input/evdev_joystick_handler.cpp"
    "${RPCS3_ROOT}/rpcs3/Input/hid_pad_handler.cpp"
    "${RPCS3_ROOT}/rpcs3/Input/mm_joystick_handler.cpp"
    "${RPCS3_ROOT}/rpcs3/Input/pad_thread.cpp"
    "${RPCS3_ROOT}/rpcs3/Input/product_info.cpp"
    "${RPCS3_ROOT}/rpcs3/Input/ps_move_calibration.cpp"
    "${RPCS3_ROOT}/rpcs3/Input/ps_move_config.cpp"
    "${RPCS3_ROOT}/rpcs3/Input/ps_move_handler.cpp"
    "${RPCS3_ROOT}/rpcs3/Input/ps_move_tracker.cpp"
    "${RPCS3_ROOT}/rpcs3/Input/raw_mouse_config.cpp"
    "${RPCS3_ROOT}/rpcs3/Input/raw_mouse_handler.cpp"
    "${RPCS3_ROOT}/rpcs3/Input/sdl_pad_handler.cpp"
    "${RPCS3_ROOT}/rpcs3/Input/skateboard_pad_handler.cpp"
    "${RPCS3_ROOT}/rpcs3/rpcs3_version.cpp"
)

target_include_directories(${CMAKE_PROJECT_NAME} PUBLIC "${RPCS3_ROOT}/rpcs3")
target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE
    -Wno-error=return-type
    -Wno-error=implicit-fallthrough
)

target_link_libraries(${CMAKE_PROJECT_NAME}
    android
    log
    rpcs3_emu
    nativehelper
    3rdparty::libusb
    3rdparty::hidapi
    3rdparty::wolfssl
    3rdparty::libcurl
    3rdparty::zlib
    3rdparty::fusion
    adrenotools
)

# Gradle packages native libraries from the CMake target output directory.
# Keep Turnip and libadrenotools' runtime hooks beside the frontend library.
# The app copies Turnip into its private data directory before asking
# libadrenotools to load it through Android's real Vulkan loader.
add_dependencies(${CMAKE_PROJECT_NAME}
    main_hook
    hook_impl
    file_redirect_hook
    gsl_alloc_hook
)
add_custom_command(TARGET ${CMAKE_PROJECT_NAME} POST_BUILD
    COMMAND "${CMAKE_COMMAND}" -E copy_if_different
        "${TURNIP_LIBRARY}"
        "$<TARGET_FILE_DIR:${CMAKE_PROJECT_NAME}>/libvulkan_freedreno.so"
    COMMAND "${CMAKE_COMMAND}" -E copy_if_different
        "$<TARGET_FILE:main_hook>"
        "$<TARGET_FILE_DIR:${CMAKE_PROJECT_NAME}>/$<TARGET_FILE_NAME:main_hook>"
    COMMAND "${CMAKE_COMMAND}" -E copy_if_different
        "$<TARGET_FILE:hook_impl>"
        "$<TARGET_FILE_DIR:${CMAKE_PROJECT_NAME}>/$<TARGET_FILE_NAME:hook_impl>"
    COMMAND "${CMAKE_COMMAND}" -E copy_if_different
        "$<TARGET_FILE:file_redirect_hook>"
        "$<TARGET_FILE_DIR:${CMAKE_PROJECT_NAME}>/$<TARGET_FILE_NAME:file_redirect_hook>"
    COMMAND "${CMAKE_COMMAND}" -E copy_if_different
        "$<TARGET_FILE:gsl_alloc_hook>"
        "$<TARGET_FILE_DIR:${CMAKE_PROJECT_NAME}>/$<TARGET_FILE_NAME:gsl_alloc_hook>"
)
