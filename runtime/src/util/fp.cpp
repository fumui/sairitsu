#include "util/fp.h"

namespace sr {

float mul_add_unfused(float a, float b, float c) {
    return a * b + c;
}

}  // namespace sr
