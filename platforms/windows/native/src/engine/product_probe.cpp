#include <filesystem>
#include <iostream>
#include <vector>

#include "rime_engine.hpp"
#include "../broker/key_translation.hpp"
using namespace rimes::windows::engine;
int wmain(int argc, wchar_t** argv) {
  if (argc != 5) return 2;
  RimeEngineOptions options;
  options.dll_path = argv[1];
  options.shared_data_dir = argv[2];
  options.user_data_dir = argv[3];
  options.log_dir = argv[4];
  options.full_maintenance_check = true;
  std::filesystem::create_directories(options.user_data_dir);
  std::filesystem::create_directories(options.log_dir);
  RimeEngine engine;
  std::string error;
  if (!engine.Start(options, &error)) {
    std::cerr << error;
    return 1;
  }
  auto session = engine.CreateSession(&error);
  if (!session) return 1;
  struct Case {
    const char* schema;
    const char* keys;
    bool traditional;
    const char* expected;
  };
  const std::vector<Case> cases = {{"rime_ice", "nihao", false, "你好"},
                                   {"double_pinyin", "ni", false, "你"},
                                   {"double_pinyin_flypy", "ni", false, "你"},
                                   {"wubi86", "wq", false, "你"},
                                   {"english", "hello", false, "hello"},
                                   {"rime_ice", "han", true, "漢"},
                                   {"wubi86", "ic", true, "漢"}};
  for (const auto& item : cases) {
    if (!engine.Configure(session, item.schema, false, item.traditional, false,
                          &error)) {
      std::cerr << "Configure failed " << item.schema;
      return 1;
    }
    EngineSnapshot snapshot;
    std::string committed;
    for (const char* key = item.keys; *key; ++key) {
      if (!engine.ProcessKey(session, *key, 0, &snapshot, &error)) return 1;
      committed += snapshot.commit_text;
    }
    bool found = committed.find(item.expected) != std::string::npos;
    for (const auto& candidate : snapshot.candidates)
      if (candidate.text == item.expected) found = true;
    if (!found) {
      std::cerr << "Product candidate assertion failed: " << item.schema
                << " traditional=" << item.traditional
                << " candidate_count=" << snapshot.candidates.size() << '\n';
      return 1;
    }
    std::cout << "Product scheme passed: " << item.schema
              << " traditional=" << item.traditional << '\n';
  }
  if (!engine.Configure(session, "my_combo", false, false, false, &error)) {
    std::cerr << "Configure failed my_combo: " << error; return 1;
  }
  EngineSnapshot chord;
  constexpr int released = 1 << 30;
  for (const char key : {'d', 'v', 'i'}) {
    if (!engine.ProcessKey(session, key, 0, &chord, &error) || !chord.commit_text.empty()) {
      std::cerr << "Chord key-down committed prematurely"; return 1;
    }
  }
  for (const char key : {'v', 'd', 'i'}) {
    if (!engine.ProcessKey(session, key, released, &chord, &error) || !chord.commit_text.empty()) {
      std::cerr << "Chord release failed or bypassed selection"; return 1;
    }
    if (key != 'i' && !chord.candidates.empty()) {
      std::cerr << "Chord resolved before the final key-up"; return 1;
    }
  }
  if (chord.candidates.empty() || chord.candidates.front().text != "你") {
    std::cerr << "Chord d+v+i did not resolve to ni / 你"; return 1;
  }
  if (!engine.ProcessKey(session, ' ', 0, &chord, &error) || chord.commit_text != "你") {
    std::cerr << "Chord candidate failed to commit exactly once"; return 1;
  }
  if (!engine.ProcessKey(session, ' ', released, &chord, &error) || !chord.commit_text.empty()) {
    std::cerr << "Chord space release duplicated commit"; return 1;
  }
  std::cout << "Product scheme passed: my_combo chord down/up and commit\n";
  // Exercise the actual Windows-to-Rime boundary, not only hand-written
  // keysyms. A shifted slash previously remained '/' on this path (#59).
  using namespace rimes::windows;
  constexpr auto shift = static_cast<std::uint32_t>(core::KeyModifiers::kShift);
  constexpr auto caps = static_cast<std::uint32_t>(core::KeyModifiers::kCapsLock);
  auto windows_key = [&](std::uint32_t vk, std::uint32_t modifiers,
                         bool down, EngineSnapshot* snapshot) {
    core::KeyEvent event;
    event.virtual_key = vk;
    event.modifiers = modifiers;
    event.event_flags = down ? static_cast<std::uint32_t>(core::KeyEventFlags::kKeyDown) : 0;
    const auto translated = broker::TranslateWindowsKey(event);
    return translated && engine.ProcessKey(session, translated->keycode,
                                          translated->modifiers, snapshot, &error);
  };
  for (const bool ascii_punctuation : {false, true}) {
    if (!engine.Configure(session, "rime_ice", false, false, ascii_punctuation, &error)) return 1;
    EngineSnapshot punctuation;
    if (!windows_key(0xbf, shift, true, &punctuation) ||
        punctuation.commit_text != (ascii_punctuation ? "?" : "？")) {
      std::cerr << "Shift+/ did not commit the configured question mark"; return 1;
    }
    // Releasing Shift first must not cause a slash or a duplicate commit.
    if (!windows_key(0xbf, 0, false, &punctuation) || !punctuation.commit_text.empty()) {
      std::cerr << "Question mark key-up duplicated text"; return 1;
    }
    if (!windows_key(0xbf, 0, true, &punctuation) || punctuation.commit_text != "/") {
      std::cerr << "Unshifted slash changed"; return 1;
    }
    if (!windows_key(0xbf, 0, false, &punctuation) || !punctuation.commit_text.empty()) return 1;
  }
  if (!engine.Configure(session, "english", false, false, false, &error)) return 1;
  EngineSnapshot english;
  for (const auto& [vk, modifiers] : std::vector<std::pair<std::uint32_t, std::uint32_t>>{
           {'H', shift}, {'E', 0}, {'L', caps}, {'L', shift | caps}, {'O', 0}}) {
    if (!windows_key(vk, modifiers, true, &english) ||
        !windows_key(vk, 0, false, &english)) return 1;
  }
  bool exact_case = english.composition == "HeLlo";
  for (const auto& candidate : english.candidates)
    exact_case = exact_case || candidate.text == "HeLlo";
  if (!exact_case) {
    std::cerr << "English discarded Shift/Caps capitalization"; return 1;
  }
  std::cout << "Windows printable keys passed: question/slash, Shift release, English case\n";
  engine.DestroySession(session);
  return 0;
}
