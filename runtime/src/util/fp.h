#pragma once

namespace sr {

// Returns a * b + c with the product rounded to float before the add.
// Lives in its own translation unit so callers cannot fold it.
float mul_add_unfused(float a, float b, float c);

}  // namespace sr
