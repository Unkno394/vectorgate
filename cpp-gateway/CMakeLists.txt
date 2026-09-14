cmake_minimum_required(VERSION 3.16)

project(cpp_reverse_proxy VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_executable(reverse_proxy
    src/main.cpp
)

target_compile_options(reverse_proxy PRIVATE
    $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic>
)

find_package(Threads REQUIRED)
target_link_libraries(reverse_proxy PRIVATE Threads::Threads)
