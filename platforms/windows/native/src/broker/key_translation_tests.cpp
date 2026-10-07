#include "key_translation.hpp"

#include <cstdlib>
#include <iostream>

namespace rimes::windows::broker::tests {
namespace {

int g_failures = 0;

#define EXPECT(condition)                                                     \
  do {                                                                        \
    if (!(condition)) {                                                       \
      std::cerr << __FILE__ << ':' << __LINE__ << ": expectation failed: "   \
                << #condition << '\n';                                        \
      ++g_failures;                                                           \
    }                                                                         \
  } while (false)

constexpr std::uint32_t Flag(core::KeyModifiers value) {
  return static_cast<std::uint32_t>(value);
}

constexpr std::uint32_t Flag(core::KeyEventFlags value) {
  return static_cast<std::uint32_t>(value);
}

core::KeyEvent Key(std::uint32_t virtual_key) {
  core::KeyEvent key;
  key.session_id = 1;
  key.sequence_id = 1;
  key.virtual_key = virtual_key;
  key.event_flags = Flag(core::KeyEventFlags::kKeyDown);
  return key;
}

void TestLettersAndModifiers() {
  core::KeyEvent key = Key(0x41);
  key.modifiers = Flag(core::KeyModifiers::kShift) |
                  Flag(core::KeyModifiers::kCapsLock) |
                  Flag(core::KeyModifiers::kControl);
  const auto translated = TranslateWindowsKey(key);
  EXPECT(translated.has_value());
  EXPECT(translated->keycode == 'a');
  EXPECT(translated->modifiers == ((1 << 0) | (1 << 1) | (1 << 2)));
}

void TestPrintableLettersRespectShiftAndCapsLock() {
  for (std::uint32_t virtual_key = 0x41; virtual_key <= 0x5a; ++virtual_key) {
    for (const bool shift : {false, true}) {
      for (const bool caps : {false, true}) {
        auto key = Key(virtual_key);
        key.modifiers = (shift ? Flag(core::KeyModifiers::kShift) : 0U) |
                        (caps ? Flag(core::KeyModifiers::kCapsLock) : 0U);
        const auto translated = TranslateWindowsKey(key);
        const auto expected = static_cast<std::int32_t>(
            (shift != caps ? 'A' : 'a') + virtual_key - 0x41);
        EXPECT(translated.has_value());
        EXPECT(translated->keycode == expected);
        EXPECT(translated->modifiers ==
               ((shift ? 1 << 0 : 0) | (caps ? 1 << 1 : 0)));
        EXPECT(IsLikelyHandledForTest(key, false));

        key.event_flags = 0;
        const auto released = TranslateWindowsKey(key);
        EXPECT(released->keycode == expected);
        EXPECT(released->modifiers == (translated->modifiers | (1 << 30)));
      }
    }
  }
}

void TestShiftPunctuationAndDigits() {
  struct Case {
    std::uint32_t virtual_key;
    char plain;
    char shifted;
  };
  const Case cases[] = {
      {0x30, '0', ')'}, {0x31, '1', '!'}, {0x32, '2', '@'},
      {0x33, '3', '#'}, {0x34, '4', '$'}, {0x35, '5', '%'},
      {0x36, '6', '^'}, {0x37, '7', '&'}, {0x38, '8', '*'},
      {0x39, '9', '('}, {0xba, ';', ':'}, {0xbb, '=', '+'},
      {0xbc, ',', '<'}, {0xbd, '-', '_'}, {0xbe, '.', '>'},
      {0xbf, '/', '?'}, {0xc0, '`', '~'}, {0xdb, '[', '{'},
      {0xdc, '\\', '|'}, {0xdd, ']', '}'}, {0xde, '\'', '"'},
      {0xe2, '\\', '|'}};
  for (const auto& item : cases) {
    for (const bool caps : {false, true}) {
      auto key = Key(item.virtual_key);
      key.modifiers = caps ? Flag(core::KeyModifiers::kCapsLock) : 0U;
      EXPECT(TranslateWindowsKey(key)->keycode == item.plain);
      key.modifiers |= Flag(core::KeyModifiers::kShift);
      const auto translated = TranslateWindowsKey(key);
      EXPECT(translated->keycode == item.shifted);
      EXPECT((translated->modifiers & (1 << 0)) != 0);
      EXPECT(IsLikelyHandledForTest(key, true));
      key.event_flags = 0;
      const auto released = TranslateWindowsKey(key);
      EXPECT(released->keycode == item.shifted);
      EXPECT(released->modifiers == (translated->modifiers | (1 << 30)));
    }
  }
}

void TestCommandBindingsAndChordPhysicalKeysRemainUnchanged() {
  for (const auto command : {core::KeyModifiers::kControl,
                            core::KeyModifiers::kAlt,
                            core::KeyModifiers::kWindows}) {
    auto letter = Key(0x41);
    letter.modifiers = Flag(command) | Flag(core::KeyModifiers::kShift);
    EXPECT(TranslateWindowsKey(letter)->keycode == 'a');
    EXPECT(!IsLikelyHandledForTest(letter, true));
    auto slash = Key(0xbf);
    slash.modifiers = letter.modifiers;
    EXPECT(TranslateWindowsKey(slash)->keycode == '/');
    EXPECT(!IsLikelyHandledForTest(slash, true));
    auto grave = Key(0xc0);
    grave.modifiers = letter.modifiers;
    EXPECT(TranslateWindowsKey(grave)->keycode == '`');
  }
  for (const char character : {'d', 'v', 'i'}) {
    auto key = Key(static_cast<std::uint32_t>(character - 'a' + 0x41));
    const auto down = TranslateWindowsKey(key);
    EXPECT(down->keycode == character);
    EXPECT(down->modifiers == 0);
    key.event_flags = 0;
    const auto up = TranslateWindowsKey(key);
    EXPECT(up->keycode == character);
    EXPECT(up->modifiers == (1 << 30));
  }
}

void TestSpecialAndExtendedKeys() {
  EXPECT(TranslateWindowsKey(Key(0x08))->keycode == 0xff08);
  EXPECT(TranslateWindowsKey(Key(0x70))->keycode == 0xffbe);
  EXPECT(TranslateWindowsKey(Key(0x87))->keycode == 0xffd5);
  EXPECT(TranslateWindowsKey(Key(0xba))->keycode == ';');

  core::KeyEvent keypad_enter = Key(0x0d);
  keypad_enter.event_flags |= Flag(core::KeyEventFlags::kExtended);
  EXPECT(TranslateWindowsKey(keypad_enter)->keycode == 0xff8d);

  auto shifted_keypad = Key(0x61);
  shifted_keypad.modifiers = Flag(core::KeyModifiers::kShift);
  EXPECT(TranslateWindowsKey(shifted_keypad)->keycode == 0xffb1);
  auto shifted_f4 = Key(0x73);
  shifted_f4.modifiers = Flag(core::KeyModifiers::kShift);
  EXPECT(TranslateWindowsKey(shifted_f4)->keycode == 0xffc1);
}

void TestRealKeyUpCarriesReleaseMask() {
  core::KeyEvent key_up = Key(0x41);
  key_up.event_flags = 0;
  const auto translated = TranslateWindowsKey(key_up);
  EXPECT(translated.has_value());
  EXPECT(translated->keycode == 'a');
  EXPECT((translated->modifiers & (1 << 30)) != 0);
}

void TestAltGrFailsOpen() {
  core::KeyEvent key = Key(0x45);
  key.modifiers = Flag(core::KeyModifiers::kAltGr) |
                  Flag(core::KeyModifiers::kControl) |
                  Flag(core::KeyModifiers::kAlt);
  EXPECT(!TranslateWindowsKey(key).has_value());
  EXPECT(!IsLikelyHandledForTest(key, true));
}

void TestNonMutatingPrediction() {
  core::KeyEvent letter = Key(0x52);
  letter.event_flags |= Flag(core::KeyEventFlags::kTestOnly);
  EXPECT(IsLikelyHandledForTest(letter, false));

  core::KeyEvent space = Key(0x20);
  space.event_flags |= Flag(core::KeyEventFlags::kTestOnly);
  EXPECT(!IsLikelyHandledForTest(space, false));
  EXPECT(IsLikelyHandledForTest(space, true));

  letter.modifiers = Flag(core::KeyModifiers::kWindows);
  EXPECT(!IsLikelyHandledForTest(letter, true));
}

}  // namespace

int RunKeyTranslationTests() {
  TestLettersAndModifiers();
  TestPrintableLettersRespectShiftAndCapsLock();
  TestShiftPunctuationAndDigits();
  TestCommandBindingsAndChordPhysicalKeysRemainUnchanged();
  TestSpecialAndExtendedKeys();
  TestRealKeyUpCarriesReleaseMask();
  TestAltGrFailsOpen();
  TestNonMutatingPrediction();
  return g_failures == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}

}  // namespace rimes::windows::broker::tests

#if defined(RIMES_KEY_TRANSLATION_TEST_MAIN)
int main() {
  return rimes::windows::broker::tests::RunKeyTranslationTests();
}
#endif
