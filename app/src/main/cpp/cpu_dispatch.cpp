// SPDX-License-Identifier: GPL-3.0-or-later
// Architecture namespace/init-array scheme follows Stockfish's
// src/universal/entry_arm64.cpp (GPL-3.0, Copyright 2004-2026 Stockfish developers).
#include <cstring>
#include <iostream>
#include <sys/auxv.h>
#include <vector>
#include "cpu_features.h"

#define DECLARE_VARIANT(name) \
    namespace Stockfish_##name { int main(int, char**); } \
    extern "C" void (*__start_##name##_init[])(void); \
    extern "C" void (*__stop_##name##_init[])(void); \
    static int run_##name(int argc, char** argv) { \
        for (auto init = __start_##name##_init; init != __stop_##name##_init; ++init) (*init)(); \
        return Stockfish_##name::main(argc, argv); \
    }

DECLARE_VARIANT(armv8)
DECLARE_VARIANT(armv8_dotprod)

int main(int argc, char** argv) {
    bool baseline = false;
    std::vector<char*> args{argv[0]};
    for (int i = 1; i < argc; ++i) {
        if (std::strcmp(argv[i], "--chesspilot-baseline") == 0) baseline = true;
        else args.push_back(argv[i]);
    }
    const int count = static_cast<int>(args.size());
    args.push_back(nullptr);
    const bool dotprod = chesspilot::use_dotprod(getauxval(AT_HWCAP), baseline);
    std::cout << "info string ChessPilot CPU " << (dotprod ? "armv8-dotprod" : "armv8") << std::endl;
    return dotprod ? run_armv8_dotprod(count, args.data()) : run_armv8(count, args.data());
}
