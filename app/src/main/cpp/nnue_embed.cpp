// Stockfish is GPL-3.0; fetched sources and network provenance: tools/fetch_stockfish.sh.
// These names match src/nnue/network.cpp's UNIVERSAL_BINARY declarations.
#define INCBIN_SILENCE_BITCODE_WARNING
#include "incbin/incbin.h"
#include "evaluate.h"
INCBIN(EmbeddedNNUE, EvalFileDefaultName);
