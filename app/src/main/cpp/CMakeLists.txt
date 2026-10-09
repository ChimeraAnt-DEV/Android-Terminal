cmake_minimum_required(VERSION 3.22.1)
project(chimera_pty C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(chimera_pty SHARED
        pty_bridge.cpp)

find_library(log-lib log)

# forkpty(3) lives in libc on Android; there is no separate libutil.
target_link_libraries(chimera_pty
        ${log-lib})
