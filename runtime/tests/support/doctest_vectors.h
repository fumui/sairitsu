#pragma once

#include <sstream>
#include <vector>

#include "doctest.h"

// Prints a std::vector<float> in a failed CHECK as {a, b, ...}.
namespace doctest {
template <>
struct StringMaker<std::vector<float>> {
    static String convert(const std::vector<float>& v) {
        std::ostringstream out;
        out << '{';
        for (size_t i = 0; i < v.size(); ++i) out << (i ? ", " : "") << v[i];
        out << '}';
        return out.str().c_str();
    }
};
}  // namespace doctest
