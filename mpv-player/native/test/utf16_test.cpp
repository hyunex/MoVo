// Host test: g++ -std=c++11 -Wall -Wextra -I mpv-player/native/bridge mpv-player/native/test/utf16_test.cpp mpv-player/native/bridge/utf16.cpp -o /tmp/utf16_test && /tmp/utf16_test
#include <cstdio>
#include <string>

#include "utf16.h"

static int failures = 0;

static void expect(const char *name, const std::string &utf8, const std::u16string &expected)
{
    const std::u16string actual = utf8_to_utf16(utf8.data(), utf8.size());
    if (actual == expected) {
        std::printf("ok   %s\n", name);
        return;
    }
    failures++;
    std::printf("FAIL %s: got", name);
    for (char16_t c : actual)
        std::printf(" %04X", static_cast<unsigned>(c));
    std::printf(", want");
    for (char16_t c : expected)
        std::printf(" %04X", static_cast<unsigned>(c));
    std::printf("\n");
}

int main()
{
    expect("empty", "", u"");
    expect("ascii", "eng", u"eng");
    expect("two-byte", "\xc3\xa9", u"\u00e9");
    expect("three-byte", "\xe2\x82\xac", u"\u20ac");
    expect("four-byte becomes surrogate pair", "\xf0\x9f\x98\x80", u"\xd83d\xde00");
    expect("max code point", "\xf4\x8f\xbf\xbf", u"\xdbff\xdfff");
    expect("embedded NUL", std::string("a\0b", 3), std::u16string(u"a\0b", 3));
    expect("crafted mkv language ff fe fd", "\xff\xfe\xfd", u"\xfffd\xfffd\xfffd");
    expect("lone continuation", "x\x80y", u"x\xfffdy");
    expect("overlong NUL c0 80", "\xc0\x80", u"\xfffd\xfffd");
    expect("overlong three-byte", "\xe0\x80\x80", u"\xfffd\xfffd\xfffd");
    expect("overlong four-byte", "\xf0\x80\x80\x80", u"\xfffd\xfffd\xfffd\xfffd");
    expect("encoded surrogate", "\xed\xa0\x80", u"\xfffd\xfffd\xfffd");
    expect("above U+10FFFF", "\xf4\x90\x80\x80", u"\xfffd\xfffd\xfffd\xfffd");
    expect("truncated three-byte at end", "ab\xe2\x82", u"ab\xfffd");
    expect("truncated three-byte before ascii", "\xe2\x82" "A", u"\xfffd" "A");
    expect("truncated four-byte at end", "\xf0\x9f\x98", u"\xfffd");
    expect("valid after invalid", "\xff" "fr\xc3\xa9", u"\xfffd" "fr\u00e9");
    if (failures) {
        std::printf("%d failed\n", failures);
        return 1;
    }
    std::printf("all passed\n");
    return 0;
}
