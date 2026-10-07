#pragma once
#include <string>

namespace rimes::windows::broker {
class UserSecurityContext;
// Requests settings in the existing Broker through authenticated protocol v2.
// The entire connect/handshake/command has a 15-second deadline. No input
// session or document target is created.
bool RequestSettings(const UserSecurityContext& security,
                     std::wstring* error) noexcept;
}
