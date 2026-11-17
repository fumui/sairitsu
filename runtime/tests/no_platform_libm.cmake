# Fails if the library references a platform libm function: every float function must come from the
# vendored musl (sr_musl_*) or compile to an instruction, so host and device give the same bits.
# Usage: cmake -DNM=<llvm-nm> -DLIB=<library> -P no_platform_libm.cmake

if(NOT NM OR NM MATCHES "NOTFOUND$")
    message(FATAL_ERROR "llvm-nm not found (looked in PATH and C:/Program Files/LLVM/bin)")
endif()
if(NOT EXISTS "${LIB}")
    message(FATAL_ERROR "library not found: ${LIB}")
endif()

execute_process(COMMAND "${NM}" -u "${LIB}"
    OUTPUT_VARIABLE out ERROR_VARIABLE err RESULT_VARIABLE rc)
if(NOT rc EQUAL 0)
    message(FATAL_ERROR "llvm-nm failed (${rc}): ${err}")
endif()

set(names "sin|cos|tan|asin|acos|atan|atan2|sinh|cosh|tanh|exp|exp2|log|log2|log10|pow|floor|ceil|round|trunc|fmod")
string(APPEND names "|sqrt|hypot|expm1|log1p|cbrt|ldexp|scalbn|frexp|modf|rint|nearbyint|lround|remainder|fma")
# Optional COFF import prefix and Mach-O underscore, then the name and its f / l variant.
set(pattern "^(__imp_)?_?(${names})[fl]?$")

string(REPLACE ";" "," out "${out}")
string(REPLACE "\n" ";" lines "${out}")
set(found "")
set(checked 0)
foreach(line IN LISTS lines)
    string(STRIP "${line}" line)
    if(line MATCHES "^U +([^ ]+)$")
        math(EXPR checked "${checked} + 1")
        set(symbol "${CMAKE_MATCH_1}")
        if(symbol MATCHES "${pattern}")
            list(APPEND found "${symbol}")
        endif()
    endif()
endforeach()

if(checked EQUAL 0)
    message(FATAL_ERROR "no undefined symbols listed for ${LIB}; is it the right file?")
endif()
if(found)
    list(REMOVE_DUPLICATES found)
    message(FATAL_ERROR "platform libm symbols referenced by ${LIB}: ${found}")
endif()
message(STATUS "${checked} undefined symbols, none from the platform libm")
