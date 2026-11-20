#include <limits.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    char path[PATH_MAX];
    ssize_t length = readlink("/proc/self/exe", path, sizeof(path) - 1);
    if (argc < 1 || length < 0) {
        perror("locating LLD");
        return 127;
    }
    path[length] = '\0';
    char *base = strrchr(path, '/');
    const char name[] = "libmm3d_lld_driver.so";
    if (!base || (size_t)(base - path) + sizeof(name) >= sizeof(path)) return 127;
    memcpy(base + 1, name, sizeof(name));
    argv[0] = "ld.lld";
    execv(path, argv);
    perror("starting LLD");
    return 127;
}
