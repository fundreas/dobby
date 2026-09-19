// Feeds a .gbnf file to llama.cpp's own parser and reports what it made of it.
//
// This is the contract test the plan defers to the native spike, and it does not need a phone:
// llama_grammar_parser is pure C++ over a string, so the generated grammar can be checked
// against the pinned parser on any machine that can build llama.cpp. See ../README.md for the
// exact build line and for what it answered.
//
// It is a tool rather than a Gradle task on purpose — it needs a host toolchain the Android
// build does not have, and a grammar question is asked when the generator changes, not on
// every build. GeneratorGoldenTest is what guards the generator in between.
#include "llama-grammar.h"
#include <cstdio>
#include <fstream>
#include <sstream>
int main(int argc, char** argv) {
    if (argc < 3) { fprintf(stderr, "usage: check <file.gbnf> <root>\n"); return 2; }
    std::ifstream in(argv[1]);
    std::stringstream ss; ss << in.rdbuf();
    const std::string text = ss.str();
    llama_grammar_parser parser;
    if (!parser.parse(text.c_str())) { fprintf(stderr, "PARSE FAILED\n"); return 1; }
    auto it = parser.symbol_ids.find(argv[2]);
    if (it == parser.symbol_ids.end()) { fprintf(stderr, "ROOT '%s' NOT FOUND\n", argv[2]); return 1; }
    printf("OK: %zu rules, root '%s' = id %u\n", parser.rules.size(), argv[2], it->second);
    return 0;
}
