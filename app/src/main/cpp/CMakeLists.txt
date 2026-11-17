cmake_minimum_required(VERSION 3.22.1)
project(chessassist)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(SF_SRC ${CMAKE_CURRENT_SOURCE_DIR}/stockfish/src)
file(GLOB_RECURSE SF_SOURCES CONFIGURE_DEPENDS ${SF_SRC}/*.cpp)
list(FILTER SF_SOURCES EXCLUDE REGEX ".*/(universal|temp_builds)/.*")

# Same architecture isolation as upstream's arm64-universal build: each variant
# has its own Stockfish namespace and inert constructors, invoked only after
# checking HWCAP_ASIMDDP. No dotprod code runs on baseline ARMv8 devices.
foreach(variant IN ITEMS armv8 armv8_dotprod)
    set(target sf_${variant})
    add_library(${target} OBJECT ${SF_SOURCES})
    target_include_directories(${target} PRIVATE ${SF_SRC} ${SF_SRC}/nnue)
    target_compile_definitions(${target} PRIVATE
        UNIVERSAL_BINARY Stockfish=Stockfish_${variant}
        USE_PTHREADS USE_NEON=8 USE_POPCNT NDEBUG ARCH=${variant})
    target_compile_options(${target} PRIVATE -O3 -fno-exceptions -funroll-loops -flto=thin)
    set_target_properties(${target} PROPERTIES POSITION_INDEPENDENT_CODE ON)
    if(variant STREQUAL armv8_dotprod)
        target_compile_definitions(${target} PRIVATE USE_NEON_DOTPROD)
        target_compile_options(${target} PRIVATE -march=armv8.2-a+dotprod)
    else()
        target_compile_options(${target} PRIVATE -march=armv8-a)
    endif()

    set(bundle ${CMAKE_CURRENT_BINARY_DIR}/${variant}.o)
    add_custom_command(OUTPUT ${bundle}
        COMMAND ${CMAKE_LINKER} -r -o ${bundle} $<TARGET_OBJECTS:${target}>
        COMMAND ${CMAKE_OBJCOPY} -R .group ${bundle}
        COMMAND ${CMAKE_OBJCOPY} --rename-section .init_array=${variant}_init ${bundle}
        COMMAND ${CMAKE_OBJCOPY} --set-section-flags ${variant}_init=alloc,data ${bundle}
        DEPENDS ${target} $<TARGET_OBJECTS:${target}>
        COMMAND_EXPAND_LISTS VERBATIM)
    set_source_files_properties(${bundle} PROPERTIES EXTERNAL_OBJECT TRUE GENERATED TRUE)
    list(APPEND SF_BUNDLES ${bundle})
endforeach()

# One network shared by both namespaces; retain offline, embedded NNUE loading.
add_executable(stockfish-bin cpu_dispatch.cpp nnue_embed.cpp ${SF_BUNDLES})
target_include_directories(stockfish-bin PRIVATE ${SF_SRC})
target_compile_options(stockfish-bin PRIVATE -O3 -march=armv8-a)
target_link_options(stockfish-bin PRIVATE -Wl,-z,nostart-stop-gc -Wl,-z,max-page-size=16384)
set_target_properties(stockfish-bin PROPERTIES
    OUTPUT_NAME libstockfish PREFIX "" SUFFIX .so
    LIBRARY_OUTPUT_DIRECTORY ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}
    RUNTIME_OUTPUT_DIRECTORY ${CMAKE_LIBRARY_OUTPUT_DIRECTORY})
