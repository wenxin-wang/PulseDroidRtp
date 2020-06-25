cmake_minimum_required(VERSION 3.4.1)

### INCLUDE OBOE LIBRARY ###

# Set the path to the Oboe library directory
set (PROJ_DIR ../../../..)
set (VENDOR_DIR ${PROJ_DIR}/thirdparty/vendor)
set (BUILD_DIR ${PROJ_DIR}/build)
set (OBOE_DIR ${VENDOR_DIR}/oboe)

# Add the Oboe library as a subproject. Since Oboe is an out-of-tree source library we must also
# specify a binary directory
add_subdirectory(${OBOE_DIR} ${BUILD_DIR}/oboe-bin)

# Include the Oboe headers and shared sample code
include_directories(${OBOE_DIR}/include ${OBOE_DIR}/samples/shared)
include_directories(${VENDOR_DIR}/asio/asio/include)

# Debug utilities
set (DEBUG_UTILS_PATH "${OBOE_DIR}/samples/debug-utils")
set (DEBUG_UTILS_SOURCES ${DEBUG_UTILS_PATH}/trace.cpp)
include_directories(${DEBUG_UTILS_PATH})


### END OBOE INCLUDE SECTION ###


# App specific sources
set (APP_SOURCES
    jni_bridge.cpp
        OboeEngine.cpp
    )

# Build the librevive-xiaoxun library
add_library(revive-xiaoxun SHARED
    ${DEBUG_UTILS_SOURCES}
    ${APP_SOURCES}
    )

# Specify the libraries needed for hello-oboe
target_link_libraries(revive-xiaoxun android log oboe)

# Enable optimization flags: if having problems with source level debugging,
# disable -Ofast ( and debug ), re-enable after done debugging.
target_compile_options(revive-xiaoxun PRIVATE -Wall -Werror "$<$<CONFIG:RELEASE>:-Ofast>")
