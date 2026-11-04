#include "utf16.h"

std::u16string utf8_to_utf16(const char *utf8, size_t length)
{
    const unsigned char *in = reinterpret_cast<const unsigned char *>(utf8);
    std::u16string out;
    out.reserve(length);
    size_t i = 0;
    while (i < length) {
        const unsigned char lead = in[i];
        if (lead < 0x80) {
            out.push_back(lead);
            i++;
            continue;
        }
        size_t size;
        char32_t code;
        // Second-byte bounds from Unicode Table 3-7 reject overlongs, surrogates and > U+10FFFF.
        unsigned char low = 0x80, high = 0xbf;
        if (lead >= 0xc2 && lead <= 0xdf) {
            size = 2;
            code = lead & 0x1f;
        } else if (lead >= 0xe0 && lead <= 0xef) {
            size = 3;
            code = lead & 0x0f;
            if (lead == 0xe0)
                low = 0xa0;
            else if (lead == 0xed)
                high = 0x9f;
        } else if (lead >= 0xf0 && lead <= 0xf4) {
            size = 4;
            code = lead & 0x07;
            if (lead == 0xf0)
                low = 0x90;
            else if (lead == 0xf4)
                high = 0x8f;
        } else {
            out.push_back(0xfffd);
            i++;
            continue;
        }
        size_t used = 1;
        while (used < size && i + used < length && in[i + used] >= low && in[i + used] <= high) {
            code = (code << 6) | (in[i + used] & 0x3f);
            low = 0x80;
            high = 0xbf;
            used++;
        }
        i += used;
        if (used < size) {
            out.push_back(0xfffd);
        } else if (code >= 0x10000) {
            code -= 0x10000;
            out.push_back(static_cast<char16_t>(0xd800 + (code >> 10)));
            out.push_back(static_cast<char16_t>(0xdc00 + (code & 0x3ff)));
        } else {
            out.push_back(static_cast<char16_t>(code));
        }
    }
    return out;
}
