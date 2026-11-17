/* Single translation unit holding the stb_image implementation (PNG only). */
#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_PNG
#define STBI_NO_STDIO
/* No float (HDR / linear) API: it calls the platform pow(). */
#define STBI_NO_LINEAR
#define STBI_NO_HDR
#include "stb_image.h"
