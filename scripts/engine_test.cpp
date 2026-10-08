// Teste nativo: reproduz exatamente as chamadas que o shim JNI faz
// (carregar os pesos da memoria, init com tools, complete com buffer de saida).
// Compila com clang++ e roda no proprio Android (aarch64/bionic).
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <string>
#include <vector>

#include "needle.h"

static std::vector<unsigned char> read_bytes(const char* path) {
    std::ifstream f(path, std::ios::binary);
    return std::vector<unsigned char>((std::istreambuf_iterator<char>(f)),
                                      std::istreambuf_iterator<char>());
}

static std::string read_text(const char* path) {
    std::ifstream f(path);
    return std::string((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
}

int main(int argc, char** argv) {
    if (argc < 4) {
        std::fprintf(stderr, "uso: %s <needle3.cact> <tools.json> <query>\n", argv[0]);
        return 2;
    }

    auto weights = read_bytes(argv[1]);
    std::printf("pesos: %zu bytes\n", weights.size());

    int rc = needle_load(weights.data(), (unsigned long long)weights.size());
    std::printf("needle_load -> %d (models=0x%x)%s\n", rc, needle_models(),
                rc < 0 ? needle_last_error() : "");

    if (argc >= 5) {
        auto speech = read_bytes(argv[4]);
        int sr = needle_load(speech.data(), (unsigned long long)speech.size());
        std::printf("needle_load(whistle %zu bytes) -> %d (models=0x%x)%s\n", speech.size(), sr,
                    needle_models(), sr < 0 ? needle_last_error() : "");
    }

    std::string tools = read_text(argv[2]);
    rc = needle_init(nullptr, tools.c_str(), nullptr);
    std::printf("needle_init -> %d%s\n", rc, rc < 0 ? needle_last_error() : "");

    std::vector<char> out(65536);
    out[0] = '\0';
    out.back() = '\0';
    rc = needle_complete(argv[3], nullptr, 0, 512, out.data(), (int)out.size());
    std::printf("needle_complete -> %d\n%s\n", rc, out.data());
    return 0;
}
