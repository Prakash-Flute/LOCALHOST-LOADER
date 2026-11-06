cmake_minimum_required(VERSION 3.22.1)
project(localhostloader)

set(NODE_LIB "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libnode.so")
set(NODE_INCLUDE "${CMAKE_CURRENT_SOURCE_DIR}/vendor/node/include/node")

if(EXISTS "${NODE_LIB}" AND EXISTS "${NODE_INCLUDE}/node.h")
    add_library(node SHARED IMPORTED)
    set_target_properties(node PROPERTIES IMPORTED_LOCATION "${NODE_LIB}")
    add_library(nodebridge SHARED node_bridge.cpp)
    target_compile_definitions(nodebridge PRIVATE LL_WITH_NODE=1)
    target_include_directories(nodebridge PRIVATE "${NODE_INCLUDE}")
    target_link_libraries(nodebridge node log android)
else()
    # The app remains buildable without downloading the optional Node engine.
    # The production CI workflow downloads the upstream mobile runtime first.
    add_library(nodebridge SHARED node_bridge_stub.cpp)
    target_link_libraries(nodebridge log android)
endif()
