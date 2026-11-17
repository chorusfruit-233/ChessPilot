#pragma once

namespace chesspilot {
constexpr unsigned long kDotprodHwcap = 1UL << 20;
constexpr bool use_dotprod(unsigned long hwcap, bool force_baseline = false) {
    return !force_baseline && (hwcap & kDotprodHwcap) != 0;
}
static_assert(!use_dotprod(0));
static_assert(use_dotprod(kDotprodHwcap));
static_assert(!use_dotprod(kDotprodHwcap, true));
}  // namespace chesspilot
