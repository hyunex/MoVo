#pragma once

#include <stddef.h>
#include <string>

// Decodes UTF-8 from mpv, replacing each maximal ill-formed subsequence with U+FFFD.
std::u16string utf8_to_utf16(const char *utf8, size_t length);
