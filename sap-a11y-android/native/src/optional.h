// Reading an optional int out of a Nullable<int>-shaped slot.
//
// The game stores several numbers as Nullable<int> (MinionModel.SellValue,
// dump.cs:551320) and Nullable<enum> (MinionModel.Tribe, dump.cs:551330). A
// Nullable is a struct of the value followed by a hasValue byte, whose offsets
// il2cpp reports per build through FieldInfo; reading the struct header instead
// of the value field yields garbage, and reporting "no value" as 0 invents a
// value the game never had.
//
// The arithmetic is pure so it can be tested on the host: a caller passes the
// struct bytes it copied and the two offsets il2cpp gave it.
#ifndef SAPA11Y_OPTIONAL_H
#define SAPA11Y_OPTIONAL_H

#include <cstddef>
#include <cstdint>
#include <cstring>

namespace sapa11y_optional {

// Reads an int32 from `slot` when the optional is present. Returns false when
// the optional has no value or the layout does not fit the buffer, so a caller
// can keep "unknown" distinct from 0.
inline bool read_int(const unsigned char* slot, std::size_t size, std::size_t value_offset,
                     std::size_t has_value_offset, int32_t& out) {
    if (slot == nullptr) return false;
    if (value_offset > size || sizeof(int32_t) > size - value_offset) return false;
    if (has_value_offset >= size) return false;
    if (slot[has_value_offset] == 0) return false;
    std::memcpy(&out, slot + value_offset, sizeof(out));
    return true;
}

}  // namespace sapa11y_optional

#endif  // SAPA11Y_OPTIONAL_H
