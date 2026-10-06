#include "ui_command.hpp"
#include "win32_security.hpp"
#include "../core/control.hpp"

#include <algorithm>
#include <array>
#include <cstring>
#include <memory>
#include <vector>

namespace rimes::windows::broker {
namespace {
struct CloseHandleOwner {
  void operator()(void* handle) const {
    if (handle && handle != INVALID_HANDLE_VALUE) CloseHandle(handle);
  }
};
using Handle = std::unique_ptr<void, CloseHandleOwner>;

bool Fail(std::wstring* error, const wchar_t* message) {
  try { if (error) *error = message; } catch (...) {}
  return false;
}
DWORD Remaining(ULONGLONG deadline) {
  const auto now = GetTickCount64();
  return now < deadline ? static_cast<DWORD>(deadline - now) : 0;
}
bool Transfer(HANDLE pipe, void* buffer, std::size_t size, bool write,
              ULONGLONG deadline, std::wstring* error) {
  auto* bytes = static_cast<std::byte*>(buffer);
  for (std::size_t offset = 0; offset < size;) {
    const auto remaining = Remaining(deadline);
    if (!remaining) return Fail(error, L"Settings request timed out.");
    Handle event(CreateEventW(nullptr, TRUE, FALSE, nullptr));
    if (!event) return Fail(error, L"Could not create the settings I/O event.");
    OVERLAPPED overlapped{};
    overlapped.hEvent = event.get();
    const auto requested = static_cast<DWORD>(size - offset);
    const BOOL started = write
        ? WriteFile(pipe, bytes + offset, requested, nullptr, &overlapped)
        : ReadFile(pipe, bytes + offset, requested, nullptr, &overlapped);
    if (!started && GetLastError() != ERROR_IO_PENDING)
      return Fail(error, L"The settings connection was closed.");
    if (WaitForSingleObject(event.get(), remaining) != WAIT_OBJECT_0) {
      CancelIoEx(pipe, &overlapped);
      DWORD ignored = 0;
      GetOverlappedResult(pipe, &overlapped, &ignored, TRUE);
      return Fail(error, L"Settings request timed out.");
    }
    DWORD transferred = 0;
    if (!GetOverlappedResult(pipe, &overlapped, &transferred, FALSE) ||
        !transferred || transferred > requested)
      return Fail(error, L"The settings connection did not complete.");
    offset += transferred;
  }
  return true;
}
bool Exchange(HANDLE pipe, core::MessageType request_type,
              core::MessageType response_type, std::uint32_t request_id,
              std::vector<std::byte> payload, ULONGLONG deadline,
              core::Frame* response, std::wstring* error) {
  core::Frame request;
  request.header.message_type = request_type;
  request.header.request_id = request_id;
  request.payload = std::move(payload);
  std::vector<std::byte> bytes;
  if (!core::EncodeFrame(request, &bytes) ||
      !Transfer(pipe, bytes.data(), bytes.size(), true, deadline, error))
    return false;
  std::array<std::byte, core::kFrameHeaderSize> header_bytes{};
  if (!Transfer(pipe, header_bytes.data(), header_bytes.size(), false,
                deadline, error))
    return false;
  const auto header = core::DecodeFrameHeader(header_bytes);
  if (header.status != core::DecodeStatus::kComplete)
    return Fail(error, L"Invalid settings response header.");
  bytes.resize(core::kFrameHeaderSize + header.header.payload_size);
  std::memcpy(bytes.data(), header_bytes.data(), header_bytes.size());
  if (header.header.payload_size &&
      !Transfer(pipe, bytes.data() + core::kFrameHeaderSize,
                header.header.payload_size, false, deadline, error))
    return false;
  const auto decoded = core::DecodeFrame(bytes);
  if (decoded.status != core::DecodeStatus::kComplete ||
      decoded.bytes_consumed != bytes.size() ||
      decoded.frame.header.message_type != response_type ||
      decoded.frame.header.request_id != request_id ||
      decoded.frame.header.flags !=
          static_cast<std::uint32_t>(core::FrameFlags::kResponse))
    return Fail(error, L"The running Broker does not support this settings request.");
  *response = decoded.frame;
  return true;
}
bool VerifyServer(HANDLE pipe, const UserSecurityContext& security,
                  DWORD* server_id, std::wstring* error) {
  ULONG process_id = 0;
  DWORD session_id = 0;
  if (!GetNamedPipeServerProcessId(pipe, &process_id) || !process_id ||
      !ProcessIdToSessionId(process_id, &session_id) ||
      session_id != security.session_id())
    return Fail(error, L"Settings server logon identity could not be verified.");
  Handle process(OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE,
                             process_id));
  HANDLE raw_token = nullptr;
  if (!process || !OpenProcessToken(process.get(), TOKEN_QUERY, &raw_token))
    return Fail(error, L"Settings server user could not be verified.");
  Handle token(raw_token);
  DWORD required = 0;
  GetTokenInformation(token.get(), TokenUser, nullptr, 0, &required);
  if (!required || GetLastError() != ERROR_INSUFFICIENT_BUFFER)
    return Fail(error, L"Settings server user could not be read.");
  std::vector<std::byte> storage(required);
  if (!GetTokenInformation(token.get(), TokenUser, storage.data(), required,
                           &required))
    return Fail(error, L"Settings server user could not be read.");
  const auto* user = reinterpret_cast<const TOKEN_USER*>(storage.data());
  if (!IsValidSid(user->User.Sid) ||
      !EqualSid(security.sid(), user->User.Sid))
    return Fail(error, L"Settings server belongs to another user.");
  *server_id = process_id;
  return true;
}
}

bool RequestSettings(const UserSecurityContext& security,
                     std::wstring* error) noexcept {
  try {
    const auto deadline = GetTickCount64() + 15000;
    Handle pipe;
    while (Remaining(deadline)) {
      pipe.reset(CreateFileW(security.pipe_name().c_str(),
                            GENERIC_READ | GENERIC_WRITE, 0, nullptr,
                            OPEN_EXISTING,
                            FILE_FLAG_OVERLAPPED | SECURITY_SQOS_PRESENT |
                                SECURITY_IDENTIFICATION,
                            nullptr));
      if (pipe.get() != INVALID_HANDLE_VALUE) break;
      const auto code = GetLastError();
      pipe.release();  // INVALID_HANDLE_VALUE owns no resource.
      if (code != ERROR_FILE_NOT_FOUND && code != ERROR_PIPE_BUSY)
        return Fail(error, L"Could not connect to the running settings Broker.");
      Sleep((std::min)(Remaining(deadline), 25UL));
    }
    if (!pipe) return Fail(error, L"Settings Broker did not become ready.");
    DWORD server_id = 0;
    if (!VerifyServer(pipe.get(), security, &server_id, error)) return false;
    // A shortcut launched by the user may authorize the existing Broker to
    // foreground its settings window. Failure leaves normal OS focus policy.
    AllowSetForegroundWindow(server_id);
    core::ClientHello hello;
    hello.process_id = GetCurrentProcessId();
    hello.session_id = security.session_id();
    hello.client_name = "RIMES settings launcher";
    std::vector<std::byte> payload;
    core::Frame response;
    core::BrokerHello server;
    if (!core::EncodeClientHello(hello, &payload) ||
        !Exchange(pipe.get(), core::MessageType::kClientHello,
                  core::MessageType::kBrokerHello, 1, std::move(payload),
                  deadline, &response, error) ||
        !core::DecodeBrokerHello(response.payload, &server) ||
        server.process_id != server_id ||
        server.session_id != security.session_id())
      return Fail(error, L"Settings Broker handshake could not be verified.");
    for (std::uint32_t id = 2; Remaining(deadline); ++id) {
      if (!Exchange(pipe.get(), core::MessageType::kControl,
                    core::MessageType::kControlState, id,
                    core::EncodeControl({{"op", "open_settings"}}),
                    deadline, &response, error))
        return false;
      const auto result = core::DecodeControl(response.payload);
      if (!result) return Fail(error, L"Invalid settings command response.");
      const auto kind = result->value("kind", "");
      if (kind == "ok") return true;
      if (kind != "starting")
        return Fail(error, L"The running Broker could not open settings.");
      Sleep((std::min)(Remaining(deadline), 25UL));
    }
    return Fail(error, L"The settings window did not become ready.");
  } catch (...) {
    return Fail(error, L"Could not request the settings window.");
  }
}
}
