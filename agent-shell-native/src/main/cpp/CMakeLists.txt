# agsh_native — CMake 构建
#
# 两种构建形态：
#   1) Android 交叉编译（NDK 工具链，CI native-android job）：
#      cmake -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
#            -DANDROID_ABI=arm64-v8a -DANDROID_STL=c++_static \
#            -DANDROID_PLATFORM=android-29 ..
#      注意：Bionic 的 forkpty 为 API 23+，且 minSdk=29（Android 10）与
#      yl-ai 一致 —— W^X 执行策略的分界线，必须显式指定 ANDROID_PLATFORM。
#   2) Host 构建（CI native-host job + 开发机）：
#      cmake .. && cmake --build . && ctest
#      产出 libagsh_native.so，供 JVM 测试通过 -Dagsh.native.lib=<绝对路径> 加载。
cmake_minimum_required(VERSION 3.18)
project(agsh_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(AGSH_CORE_SOURCES
    agsh/pty_process.cpp
    agsh/ansi_stripper.cpp
    agsh/elf_patcher.cpp
)

# ---------------- 主库（JNI） ----------------
find_package(JNI QUIET)
if (JNI_FOUND)
    message(STATUS "agsh_native: JNI headers found (host build)")
else()
    # NDK 工具链自带 jni.h；纯交叉编译环境也可能没有 host JDK —— 允许显式跳过。
    if (NOT DEFINED ENV{JAVA_HOME} AND NOT ANDROID)
        message(WARNING "agsh_native: JNI headers not found; pass -DJNI_INCLUDE_DIRS or set JAVA_HOME")
    endif()
endif()

add_library(agsh_native SHARED
    ${AGSH_CORE_SOURCES}
    agsh/jni_entry.cpp
)
target_include_directories(agsh_native PRIVATE ${JNI_INCLUDE_DIRS})
target_compile_options(agsh_native PRIVATE -Wall -Wextra -fvisibility=hidden)
if (CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang" AND NOT ANDROID)
    # host 构建开启硬化选项（库会被测试直接加载）
    target_compile_options(agsh_native PRIVATE -fno-omit-frame-pointer)
endif()

if (ANDROID)
    find_library(log-lib log)
    target_link_libraries(agsh_native ${log-lib})
endif()

# ---------------- Host 测试（不进 Android 目标） ----------------
if (NOT ANDROID)
    enable_testing()
    add_executable(agsh_native_tests
        tests/test_main.cpp
        ${AGSH_CORE_SOURCES}
    )
    target_compile_options(agsh_native_tests PRIVATE -Wall -Wextra)
    add_test(NAME agsh_native_tests COMMAND agsh_native_tests)
endif()
