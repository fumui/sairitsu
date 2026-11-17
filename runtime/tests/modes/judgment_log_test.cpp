// The Play acceptance judgment line (modes/judgment_log.h).
#include <cstring>

#include "doctest.h"
#include "modes/judgment_log.h"

using namespace sr;

TEST_CASE("judgment line has the documented fields") {
    char buf[128];
    const modes::StatInput in{149, 3, 12.5f, 1.0f, -0.0125f};
    const size_t n = modes::format_judgment(buf, sizeof buf, in, 12.5);
    CHECK(std::strcmp(buf, "J e=149 a=3 j=1 acc=-0.012500 t=12.5000 ft=12.5000") == 0);
    CHECK(n == std::strlen(buf));
}

TEST_CASE("judgment line truncates to the buffer and stays NUL terminated") {
    char buf[10];
    const modes::StatInput in{1, 0, 1.0f, 1.0f, 0.0f};
    const size_t n = modes::format_judgment(buf, sizeof buf, in, 1.0);
    CHECK(n == 9);
    CHECK(buf[9] == '\0');
    CHECK(modes::format_judgment(buf, 0, in, 1.0) == 0);
}
