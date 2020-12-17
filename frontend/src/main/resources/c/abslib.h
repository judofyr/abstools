#include <stdlib.h>
#include <unistd.h>
#include <stdio.h>
#include <string.h>

// String representation

typedef struct absstr {
    size_t size;
    char *data;
} absstr;

int absstr_alloc(absstr *str, size_t size) {
    str->data = malloc(size+1);
    str->data[size] = 0;
    str->size = size;
    return 0;
}

int absstr_free(absstr *str) {
    free(str->data);
    str->data = 0;
    str->size = 0;
    return 0;
}

void absstr_print(absstr str) {
    fwrite(str.data, 1, str.size, stdout);
}

void absstr_println(absstr str) {
    fwrite(str.data, 1, str.size, stdout);
    fwrite("\n", 1, 1, stdout);
    fflush(stdout);
}

void absstr_literal(absstr *str, char *data, size_t size) {
    str->data = data;
    str->size = size;
}

void absstr_concat(absstr *result, absstr left, absstr right) {
    absstr_alloc(result, left.size + right.size);
    memcpy(result->data, left.data, left.size);
    memcpy(&result->data[left.size], right.data, right.size);
}
