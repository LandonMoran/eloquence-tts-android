// Host tests for the Nullable<int> unwrap used by MinionModel.SellValue.
//
// A wrong unwrap is invisible in a build: the game keeps running and the port
// quietly reports the wrong number. These cases pin the arithmetic, including
// the ones where "absent" must not become 0.
#include <cstdint>
#include <cstdio>
#include <cstring>

#include "optional.h"

namespace {

int failures = 0;
int checks = 0;

void check(bool ok, const char* what) {
    ++checks;
    if (!ok) {
        ++failures;
        std::printf("FAIL: %s\n", what);
    }
}

// A Nullable<int> as il2cpp lays it out for this game: four value bytes, then
// the hasValue byte. Offsets are what il2cpp_field_get_offset reports.
void write_slot(unsigned char* slot, std::size_t value_offset, std::size_t has_offset,
                int32_t value, bool present) {
    std::memset(slot, 0xAA, 16);  // garbage, so a stray byte read is visible
    std::memcpy(slot + value_offset, &value, sizeof(value));
    slot[has_offset] = present ? 1 : 0;
}

void readsPresentValue() {
    unsigned char slot[16];
    write_slot(slot, 0, 4, 3, true);
    int32_t out = -1;
    check(sapa11y_optional::read_int(slot, sizeof(slot), 0, 4, out), "present value reads");
    check(out == 3, "present value is the stored number");
}

void readsNegativeValue() {
    // The game can hold a negative number here; the reader must not clamp it to
    // "unknown" and must not turn it into an unsigned huge value.
    unsigned char slot[16];
    write_slot(slot, 0, 4, -2, true);
    int32_t out = 0;
    check(sapa11y_optional::read_int(slot, sizeof(slot), 0, 4, out), "negative reads");
    check(out == -2, "negative value is preserved");
}

void absentIsUnknownNotZero() {
    unsigned char slot[16];
    write_slot(slot, 0, 4, 0, false);
    int32_t out = 7;
    check(!sapa11y_optional::read_int(slot, sizeof(slot), 0, 4, out),
          "absent optional is not readable");
    check(out == 7, "absent optional leaves the output untouched, so 0 is never invented");
}

void presentZeroIsZero() {
    unsigned char slot[16];
    write_slot(slot, 0, 4, 0, true);
    int32_t out = -1;
    check(sapa11y_optional::read_int(slot, sizeof(slot), 0, 4, out), "present zero reads");
    check(out == 0, "a real zero is reported as 0, not as unknown");
}

void honoursReportedOffsets() {
    // A build may pad the struct differently; the reader must use the offsets
    // it is handed rather than assuming value-at-zero.
    unsigned char slot[16];
    write_slot(slot, 4, 8, 42, true);
    int32_t out = 0;
    check(sapa11y_optional::read_int(slot, sizeof(slot), 4, 8, out), "offset layout reads");
    check(out == 42, "offset layout value");
}

void rejectsLayoutOutsideBuffer() {
    unsigned char slot[6];
    std::memset(slot, 1, sizeof(slot));
    int32_t out = 0;
    check(!sapa11y_optional::read_int(slot, sizeof(slot), 4, 8, out),
          "hasValue beyond the buffer is rejected instead of read");
    check(!sapa11y_optional::read_int(slot, sizeof(slot), 8, 0, out),
          "value beyond the buffer is rejected instead of read");
    check(!sapa11y_optional::read_int(nullptr, 0, 0, 0, out), "null slot is rejected");
}

}  // namespace

int main() {
    readsPresentValue();
    readsNegativeValue();
    absentIsUnknownNotZero();
    presentZeroIsZero();
    honoursReportedOffsets();
    rejectsLayoutOutsideBuffer();
    std::printf("%d passed, %d failed\n", checks - failures, failures);
    return failures == 0 ? 0 : 1;
}
