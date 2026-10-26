cmake_minimum_required(VERSION 3.22.1)
project(secvault C)

# 生成的分片头（构建期由 generateTokenShards 写入，gitignored）
include_directories(${CMAKE_CURRENT_SOURCE_DIR})

add_library(secvault SHARED
        secvault.c
)

# 注意：JNI 导出函数（Java_...）需要默认可见性，不能用 -fvisibility=hidden，
# 否则 dlopen/dlsym 找不到入口（改用 NDK 默认导出 + -O3 优化 + 段裁剪）。
target_compile_options(secvault PRIVATE
        -ffunction-sections
        -fdata-sections
        -O3
)

# 去掉未用段（保留动态符号，JNI 注册依赖）
target_link_options(secvault PRIVATE
        -Wl,--gc-sections
)
