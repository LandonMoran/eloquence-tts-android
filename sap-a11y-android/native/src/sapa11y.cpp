// SuperAutoAccessibility for Android -- native bridge.
//
// On Windows the mod runs inside MelonLoader and talks to the game through
// Il2CppInterop. Android has no managed host, so this file replaces that layer:
// it resolves the il2cpp reflection API out of the already-loaded libil2cpp.so,
// walks the game's UGUI objects by class name, and hands a JSON snapshot to the
// Kotlin/Java accessibility service. Actions (tap, focus, text, value) come back
// the same way.
//
// Nothing here is version specific: class and method names are metadata names,
// which are identical on every platform of the same game build, and the il2cpp
// API is exported by libil2cpp.so for libunity.so to use.

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <unistd.h>

#include <csignal>
#include <csetjmp>
#include <cstdarg>

#include <fcntl.h>
#include <pthread.h>
#include <sys/syscall.h>
#include <ucontext.h>

#include <algorithm>
#include <cmath>
#include <cstdint>

#include "optional.h"
#include "order.h"
#include "shop_slots.h"
#include <cstdio>
#include <cstring>
#include <ctime>
#include <string>
#include <vector>

#define LOG_TAG "SapA11y"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// A file the Java side points at app storage, so a crash leaves a breadcrumb
// that can be read without adb.
std::string g_log_path;
// Bumped per shipped build; printed at attach time and by the controller.
static const char* kBridgeBuild = "2026-11-22-shop-handlers";
// Raw append fd for the signal handler. stdio in a signal handler can
// deadlock against a FILE* lock the interrupted thread was holding, which
// freezes the game instead of logging the fault, so the handler never calls
// fopen/fprintf: it writes straight to this descriptor.
static int g_log_fd = -1;

void native_log(const char* fmt, ...) {
    if (g_log_path.empty()) return;
    FILE* f = fopen(g_log_path.c_str(), "a");
    if (f == nullptr) return;
    va_list args;
    va_start(args, fmt);
    vfprintf(f, fmt, args);
    va_end(args);
    fputc('\n', f);
    fclose(f);
}

// --- diagnostics shared with the signal handler -------------------------
// Which scanner stage is executing. The fault line names it, so a trip says
// what the scanner was doing without needing a symbolised tombstone.
static volatile int g_scan_phase = 0;
static const char* scan_phase_name(int phase) {
    switch (phase) {
        case 1: return "consent-labels";
        case 2: return "shop-state";
        case 3: return "battle-teams";
        case 4: return "scoreboard";
        case 5: return "shop-zones";
        case 6: return "probe-shop-api";
        case 7: return "controls";
        case 8: return "spaces";
        case 9: return "minion-views";
        case 10: return "texts";
        case 11: return "sort-title";
        default: return "idle";
    }
}
static volatile int g_skip_snapshots = 0;
// Guards the unwinder below: a fault while handling a fault is not recoverable,
// so the handler notices the re-entry instead of looping on itself.
static volatile bool g_in_fault_handler = false;

static long current_tid() { return static_cast<long>(syscall(SYS_gettid)); }

// Async-signal-safe string building: no stdio, no allocation.
static char* app_str(char* p, char* end, const char* s) {
    while (s != nullptr && *s != '\0' && p < end) *p++ = *s++;
    return p;
}
static char* app_int(char* p, char* end, long v) {
    char buf[24];
    int i = 0;
    bool neg = v < 0;
    if (neg) v = -v;
    do { buf[i++] = static_cast<char>('0' + v % 10); v /= 10; } while (v > 0 && i < 22);
    if (neg) buf[i++] = '-';
    while (i > 0 && p < end) *p++ = buf[--i];
    return p;
}
static char* app_hex(char* p, char* end, unsigned long v) {
    char buf[20];
    int i = 0;
    do {
        unsigned d = static_cast<unsigned>(v & 0xfUL);
        buf[i++] = static_cast<char>(d < 10 ? '0' + d : 'a' + (d - 10));
        v >>= 4;
    } while (v > 0 && i < 18);
    if (p < end) *p++ = '0';
    if (p < end) *p++ = 'x';
    while (i > 0 && p < end) *p++ = buf[--i];
    return p;
}
static void raw_write_all(const char* p, size_t n) {
    if (g_log_fd < 0) return;
    size_t done = 0;
    while (done < n) {
        ssize_t w = write(g_log_fd, p + done, n - done);
        if (w <= 0) return;
        done += static_cast<size_t>(w);
    }
}

// Snapshot crash guard: the bridge reads live game objects while Unity loads
// and unloads scenes, so a dangling object pointer (consent window torn down
// mid-bootstrap, font/text half-built) can SIGSEGV/SIGBUS inside a snapshot.
// Without this the whole game dies while loading; with it the snapshot is
// dropped and the next poll retries. Only the synchronous read section is
// guarded, never user-triggered taps.
static sigjmp_buf g_snap_jmp;
static volatile bool g_in_snapshot = false;
static volatile long g_main_thread_tid = 0;
// Submit-fallback crash guard: invoking the game's Submit handlers with a
// best-guess argument can fault inside game code, and a crash on a tap would
// be worse than the tap failing. Same longjmp discipline as snapshots: only
// the synchronous invoke inside submit_fallback is guarded, never tap() itself.
static sigjmp_buf g_fallback_jmp;
static volatile bool g_in_fallback = false;
struct SnapGuard {
    SnapGuard() { g_in_snapshot = true; }
    ~SnapGuard() { g_in_snapshot = false; }
};

// ---------------------------------------------------------------------------
// il2cpp ABI types
// ---------------------------------------------------------------------------

struct Il2CppObjectBase {
    void* klass;
    void* monitor;
};

struct Il2CppArrayLayout {
    Il2CppObjectBase obj;
    void* bounds;
    uintptr_t max_length;
    void* vector[1];
};

struct Il2CppStringLayout {
    Il2CppObjectBase obj;
    int32_t length;
    uint16_t chars[1];
};

// These are opaque C++ objects: "Il2CppObject*" must mean "void*", so the
// aliases name void and the API declarations below take Typename*. Il2CppDomain
// is a value handle, so it keeps its pointer.
using Il2CppDomain = void*;
using Il2CppAssembly = void;
using Il2CppImage = void;
using Il2CppClass = void;
using Il2CppObject = void;
using Il2CppType = void;
using Il2CppThread = void;
using MethodInfo = void;
using FieldInfo = void;
using Il2CppException = void;
using Il2CppString = void;

struct Api {
    Il2CppDomain (*domain_get)();
    Il2CppThread* (*thread_attach)(Il2CppDomain);
    const Il2CppAssembly** (*domain_get_assemblies)(const Il2CppDomain, size_t*);
    Il2CppImage* (*assembly_get_image)(const Il2CppAssembly*);
    const char* (*image_get_name)(const Il2CppImage*);
    Il2CppClass* (*class_from_name)(const Il2CppImage*, const char*, const char*);
    const MethodInfo* (*class_get_method_from_name)(Il2CppClass*, const char*, int);
    Il2CppClass* (*class_get_parent)(Il2CppClass*);
    bool (*class_is_assignable_from)(Il2CppClass*, Il2CppClass*);
    const char* (*class_get_name)(Il2CppClass*);
    const char* (*class_get_namespace)(Il2CppClass*);
    Il2CppType* (*class_get_type)(Il2CppClass*);
    Il2CppObject* (*type_get_object)(Il2CppType*);
    // Optional: makes a Type object for a class that has not run yet.
    void (*class_init)(Il2CppClass*);
    Il2CppObject* (*object_new)(Il2CppClass*);
    void (*runtime_object_init)(Il2CppObject*);
    Il2CppObject* (*runtime_invoke)(const MethodInfo*, void*, void**, Il2CppException**);
    Il2CppString* (*string_new)(const char*);
    void* (*object_unbox)(Il2CppObject*);
    Il2CppClass* (*object_get_class)(Il2CppObject*);
    // Optional: only used to find explicitly implemented interface methods.
    const MethodInfo* (*class_get_methods)(Il2CppClass*, void**);
    const char* (*method_get_name)(const MethodInfo*);
    uint32_t (*method_get_param_count)(const MethodInfo*);
    // Optional: one-time class-shape dump that names the exact members.
    FieldInfo* (*class_get_fields)(Il2CppClass*, void**);
    const char* (*field_get_name)(FieldInfo*);
    // Optional: consent handling, from the dumped ConsentManager API.
    FieldInfo* (*class_get_field_from_name)(Il2CppClass*, const char*);
    void (*field_get_value)(Il2CppObject*, const FieldInfo*, void*);
    void (*field_set_value)(Il2CppObject*, const FieldInfo*, void*);
    void (*field_static_get_value)(const FieldInfo*, void*);
    // Optional: naming an enum-valued field (MinionModel.Tribe) needs the
    // field's type and, from it, the enum class with its constant table.
    Il2CppType* (*field_get_type)(FieldInfo*);
    Il2CppClass* (*class_from_type)(const Il2CppType*);
    // Optional: MinionModel.Tribe is a Nullable<TribeEnum> in this build
    // (dump.cs:551330), so naming the tribe means unboxing the optional and
    // reading its value/hasValue pair rather than treating the struct as the
    // enum. Both symbols are exported by libil2cpp.so.
    size_t (*field_get_offset)(FieldInfo*);
    Il2CppObject* (*field_get_value_object)(const Il2CppDomain, FieldInfo*, Il2CppObject*);
};

Api g_api{};
void* g_il2cpp = nullptr;
bool g_ready = false;
bool g_resolve_attempted = false;
int g_last_nodes = 0;

// Scan counters, so the app can report which enumeration path works without
// needing logcat or a debugger.
int g_stat_find_empty = 0;
int g_stat_find_objects = 0;
int g_stat_scene_count = 0;
int g_stat_scene_roots = 0;
int g_stat_scene_objects = 0;
// Which projection path placed each node's coordinates this snapshot. A screen
// whose controls all come from the main-camera fallback is the case where
// offscreen classification is least trustworthy, so the counts are reported.
int g_stat_proj_overlay = 0;
int g_stat_proj_canvas = 0;
int g_stat_proj_main = 0;
int g_stat_proj_none = 0;
// Controls dropped because they carry no readable label at all (see the drop
// in snapshot_json). Counted so a report shows how many were hidden.
int g_stat_unlabeled = 0;

// Class handles are cached: the snapshot runs a few times a second and every
// lookup walks the assembly list.
struct Classes {
    Il2CppClass* object = nullptr;
    Il2CppClass* component = nullptr;
    Il2CppClass* game_object = nullptr;
    Il2CppClass* transform = nullptr;
    Il2CppClass* rect_transform = nullptr;
    Il2CppClass* behaviour = nullptr;
    Il2CppClass* selectable = nullptr;
    // The game's own controls. Super Auto Pets builds its menus from
    // Spacewood.Unity.UI.SelectableBase/ButtonBase, which need not derive from
    // UnityEngine.UI.Selectable, so both families are scanned.
    Il2CppClass* selectable_base = nullptr;
    Il2CppClass* button_base = nullptr;
    Il2CppClass* button = nullptr;
    Il2CppClass* toggle = nullptr;
    Il2CppClass* slider = nullptr;
    Il2CppClass* scrollbar = nullptr;
    Il2CppClass* input_field = nullptr;
    Il2CppClass* tmp_input_field = nullptr;
    Il2CppClass* dropdown = nullptr;
    Il2CppClass* tmp_text = nullptr;
    Il2CppClass* ui_text = nullptr;
    Il2CppClass* graphic = nullptr;
    Il2CppClass* resources = nullptr;
    Il2CppClass* canvas = nullptr;
    Il2CppClass* camera = nullptr;
    Il2CppClass* event_system = nullptr;
    Il2CppClass* pointer_event_data = nullptr;
    Il2CppClass* screen = nullptr;
    Il2CppClass* page_manager = nullptr;
    Il2CppClass* page = nullptr;
    Il2CppClass* scene_manager = nullptr;
    Il2CppClass* scene = nullptr;
    // Shop and run state (PC-mod parity). Read through HangarOverlay and
    // MinionView, never driven, so nothing here touches live game logic.
    Il2CppClass* hangar_overlay = nullptr;
    Il2CppClass* hangar_main = nullptr;
    Il2CppClass* battle_controller = nullptr;
    Il2CppClass* board_model = nullptr;
    Il2CppClass* minion_view = nullptr;
    Il2CppClass* minion_model = nullptr;
    Il2CppClass* minion_extensions = nullptr;
    Il2CppClass* space = nullptr;
    Il2CppClass* uibattle = nullptr;
    Il2CppClass* scoreboard = nullptr;
    Il2CppClass* minion_canvas_view = nullptr;
} g_cls;

Classes g_cls_tmp;  // populated once, then copied into g_cls

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

std::string json_escape(const std::string& in) {
    std::string out;
    out.reserve(in.size() + 16);
    for (unsigned char c : in) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out += static_cast<char>(c);
                }
        }
    }
    return out;
}

void append_utf8(std::string& out, uint32_t cp) {
    if (cp <= 0x7F) {
        out += static_cast<char>(cp);
    } else if (cp <= 0x7FF) {
        out += static_cast<char>(0xC0 | (cp >> 6));
        out += static_cast<char>(0x80 | (cp & 0x3F));
    } else if (cp <= 0xFFFF) {
        out += static_cast<char>(0xE0 | (cp >> 12));
        out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
        out += static_cast<char>(0x80 | (cp & 0x3F));
    } else {
        // JNI's NewStringUTF takes modified UTF-8, which has no 4-byte forms:
        // supplementary characters must be written as a surrogate pair, each
        // encoded in three bytes (CESU-8), or the VM rejects the string.
        uint32_t v = cp - 0x10000;
        append_utf8(out, 0xD800 + (v >> 10));
        append_utf8(out, 0xDC00 + (v & 0x3FF));
    }
}

std::string to_utf8(Il2CppString* s) {
    if (s == nullptr) return {};
    const auto* str = reinterpret_cast<const Il2CppStringLayout*>(s);
    // During bootstrap a half-initialized string can report a wild length;
    // reading it walks off the object and segfaults the game while loading.
    if (str->length <= 0 || str->length > 2048) return {};
    std::string out;
    out.reserve(static_cast<size_t>(str->length));
    for (int32_t i = 0; i < str->length; ++i) {
        uint32_t cp = str->chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < str->length) {
            uint32_t low = str->chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
                ++i;
            }
        }
        append_utf8(out, cp);
    }
    return out;
}

Il2CppClass* find_class(const char* ns, const char* name) {
    // Resolved metadata is enough to look a class up; g_ready only becomes true
    // once these lookups have succeeded once, so gating on it here deadlocked
    // bootstrap (find_class always returned null and g_ready never flipped).
    if (g_il2cpp == nullptr) return nullptr;
    size_t count = 0;
    const Il2CppAssembly** assemblies = g_api.domain_get_assemblies(g_api.domain_get(), &count);
    if (assemblies == nullptr) return nullptr;
    for (size_t i = 0; i < count; ++i) {
        Il2CppImage* image = g_api.assembly_get_image(assemblies[i]);
        if (image == nullptr) continue;
        Il2CppClass* k = g_api.class_from_name(image, ns, name);
        if (k != nullptr) return k;
    }
    return nullptr;
}

const MethodInfo* method_on(Il2CppClass* k, const char* name, int argc) {
    if (k == nullptr) return nullptr;
    for (Il2CppClass* cur = k; cur != nullptr; cur = g_api.class_get_parent(cur)) {
        const MethodInfo* m = g_api.class_get_method_from_name(cur, name, argc);
        if (m != nullptr) return m;
    }
    return nullptr;
}

// Explicit interface implementations get mangled metadata names, for example
// UnityEngine_EventSystems_IPointerClickHandler_OnPointerClick, so a click has
// to be found by the tail of its name rather than its exact name.
const MethodInfo* method_suffix(Il2CppClass* k, const char* suffix, int argc) {
    if (k == nullptr || g_api.class_get_methods == nullptr || g_api.method_get_name == nullptr) {
        return nullptr;
    }
    const size_t suffix_len = strlen(suffix);
    for (Il2CppClass* cur = k; cur != nullptr; cur = g_api.class_get_parent(cur)) {
        void* iter = nullptr;
        while (const MethodInfo* m = g_api.class_get_methods(cur, &iter)) {
            const char* name = g_api.method_get_name(m);
            if (name == nullptr) continue;
            const size_t len = strlen(name);
            if (len < suffix_len || strcmp(name + len - suffix_len, suffix) != 0) continue;
            if (g_api.method_get_param_count != nullptr &&
                g_api.method_get_param_count(m) != static_cast<uint32_t>(argc)) {
                continue;
            }
            return m;
        }
    }
    return nullptr;
}

bool is_a(Il2CppClass* base, Il2CppObject* obj) {
    if (base == nullptr || obj == nullptr) return false;
    Il2CppClass* k = g_api.object_get_class(obj);
    if (k == nullptr) return false;
    if (k == base) return true;
    return g_api.class_is_assignable_from != nullptr && g_api.class_is_assignable_from(base, k);
}

Il2CppObject* invoke(const MethodInfo* m, void* self, void** params, int param_count) {
    if (m == nullptr) return nullptr;
    (void)param_count;
    Il2CppException* exc = nullptr;
    Il2CppObject* result = g_api.runtime_invoke(m, self, params, &exc);
    if (exc != nullptr) {
        LOGW("il2cpp exception invoking %p", m);
        return nullptr;
    }
    return result;
}

Il2CppObject* invoke_static(Il2CppClass* k, const char* name, int argc, void** params) {
    const MethodInfo* m = method_on(k, name, argc);
    if (m == nullptr) return nullptr;
    return invoke(m, nullptr, params, argc);
}

bool call_void(Il2CppObject* self, const char* name, int argc, void** params) {
    if (self == nullptr) return false;
    Il2CppClass* k = g_api.object_get_class(self);
    const MethodInfo* m = method_on(k, name, argc);
    if (m == nullptr) return false;
    invoke(m, self, params, argc);
    return true;
}

Il2CppObject* call_object(Il2CppObject* self, const char* name, int argc, void** params) {
    if (self == nullptr) return nullptr;
    Il2CppClass* k = g_api.object_get_class(self);
    const MethodInfo* m = method_on(k, name, argc);
    if (m == nullptr) return nullptr;
    return invoke(m, self, params, argc);
}

bool call_bool(Il2CppObject* self, const char* name) {
    Il2CppObject* r = call_object(self, name, 0, nullptr);
    if (r == nullptr) return false;
    auto* v = static_cast<uint8_t*>(g_api.object_unbox(r));
    return v != nullptr && *v != 0;
}

// Unity's Selectable.IsInteractable() accounts for parent CanvasGroups, so
// a button on a greyed-out tab reports unavailable instead of pretending to
// be tappable; the raw property alone does not. The game's SelectableBase
// exposes GetInteractable() instead. Controls with neither are usable.
bool interactable_of(Il2CppObject* component) {
    if (component == nullptr) return true;
    Il2CppClass* k = g_api.object_get_class(component);
    // ButtonBase has neither an IsInteractable method nor an Interactable
    // property in this build (dump.cs:628745): it exposes GetInteractable()
    // (dump.cs:628905). uGUI's Selectable does have get_interactable, which is
    // why the base-class probe below comes first for stock controls.
    if (method_on(k, "get_interactable", 0) != nullptr) return call_bool(component, "get_interactable");
    if (method_on(k, "GetInteractable", 0) != nullptr) return call_bool(component, "GetInteractable");
    return true;
}

int32_t call_int(Il2CppObject* self, const char* name) {
    Il2CppObject* r = call_object(self, name, 0, nullptr);
    if (r == nullptr) return 0;
    auto* v = static_cast<int32_t*>(g_api.object_unbox(r));
    return v != nullptr ? *v : 0;
}

int32_t call_int_static(Il2CppClass* k, const char* name) {
    const MethodInfo* m = method_on(k, name, 0);
    if (m == nullptr) return 0;
    Il2CppObject* r = invoke(m, nullptr, nullptr, 0);
    if (r == nullptr) return 0;
    auto* v = static_cast<int32_t*>(g_api.object_unbox(r));
    return v != nullptr ? *v : 0;
}

float call_float(Il2CppObject* self, const char* name) {
    Il2CppObject* r = call_object(self, name, 0, nullptr);
    if (r == nullptr) return 0.0f;
    auto* v = static_cast<float*>(g_api.object_unbox(r));
    return v != nullptr ? *v : 0.0f;
}

std::string call_string(Il2CppObject* self, const char* name) {
    return to_utf8(reinterpret_cast<Il2CppString*>(call_object(self, name, 0, nullptr)));
}

// Icon-only menu buttons (settings gear, close X, social links) carry no
// text, so without this TalkBack exposes them as a blank control nobody can
// identify. The GameObject name usually says what they are ("CloseButton",
// "Btn_Play"). Runs after the transparency check so invisible hotspots
// stay dropped; generic Unity names describe nothing and stay blank.
std::string pretty_object_name(Il2CppObject* go) {
    if (go == nullptr) return {};
    std::string name = call_string(go, "get_name");
    const std::string clone = "(Clone)";
    size_t at = name.find(clone);
    if (at != std::string::npos) name.erase(at, clone.size());
    for (char& c : name) {
        if (c == '_' || c == '-') c = ' ';
    }
    std::string spaced;
    spaced.reserve(name.size() + 4);
    for (size_t i = 0; i < name.size(); ++i) {
        char c = name[i];
        if (i > 0 && c >= 'A' && c <= 'Z') {
            char prev = name[i - 1];
            char next = i + 1 < name.size() ? name[i + 1] : '\0';
            bool lower_digit = (prev >= 'a' && prev <= 'z') || (prev >= '0' && prev <= '9');
            bool acronym_end = (prev >= 'A' && prev <= 'Z') && (next >= 'a' && next <= 'z');
            if ((lower_digit || acronym_end) && !spaced.empty() && spaced.back() != ' ') spaced += ' ';
        }
        spaced += c;
    }
    std::string out;
    out.reserve(spaced.size());
    bool gap = true;  // collapses runs of space and trims both ends
    for (char c : spaced) {
        if (c == ' ' || c == '\t') {
            gap = true;
            continue;
        }
        if (gap && !out.empty()) out += ' ';
        gap = false;
        out += c;
    }
    std::string lower = out;
    for (char& c : lower) {
        if (c >= 'A' && c <= 'Z') c = static_cast<char>(c + ('a' - 'A'));
    }
    static const char* kGeneric[] = {"button", "image", "icon", "background", "panel",
                                     "gameobject", "text", "container", "content",
                                     "viewport", "control", "transform"};
    for (const char* g : kGeneric) {
        if (lower == g) return {};
    }
    return out;
}

// System.Type for a class. A Type object for a class that has not been
// initialised can come back null, which silently made every FindObjectsOfType
// call return nothing, so initialise first.
Il2CppObject* type_object(Il2CppClass* type) {
    if (type == nullptr) return nullptr;
    // il2cpp_class_init runs the type's static constructor: game code. Every
    // component lookup used to call it, so the scanner was running arbitrary
    // initialisers several times a second, including during scene loads. That
    // is the only place the scanner executes game code of its own accord, and
    // the recurring fault (fixed libunity offset, phase=controls, right after
    // "Loading menu...") lands exactly there. A class only needs initialising
    // once; class pointers are stable, so remembering them is safe.
    if (g_api.class_init != nullptr) {
        static Il2CppClass* inited[64];
        static int inited_count = 0;
        bool seen = false;
        for (int i = 0; i < inited_count; ++i) {
            if (inited[i] == type) { seen = true; break; }
        }
        if (!seen) {
            g_api.class_init(type);
            if (inited_count < 64) inited[inited_count++] = type;
        }
    }
    Il2CppType* t = g_api.class_get_type(type);
    return t != nullptr ? g_api.type_get_object(t) : nullptr;
}

// Component.GetComponent(Type) works for any component type, so no generic
// method instantiation is needed.
Il2CppObject* get_component(Il2CppObject* component, Il2CppClass* type) {
    if (component == nullptr || type == nullptr) return nullptr;
    Il2CppObject* type_obj = type_object(type);
    if (type_obj == nullptr) return nullptr;
    void* params[1] = {type_obj};
    return call_object(component, "GetComponent", 1, params);
}

Il2CppObject* get_component_in_parent(Il2CppObject* component, Il2CppClass* type) {
    if (component == nullptr || type == nullptr) return nullptr;
    Il2CppObject* type_obj = type_object(type);
    if (type_obj == nullptr) return nullptr;
    // The 1-arg overload can be stripped while the (Type, bool) one remains.
    void* params1[1] = {type_obj};
    if (Il2CppObject* r = call_object(component, "GetComponentInParent", 1, params1)) {
        return r;
    }
    bool include_inactive = true;
    void* params2[2] = {type_obj, &include_inactive};
    return call_object(component, "GetComponentInParent", 2, params2);
}

Il2CppObject* get_game_object(Il2CppObject* component) {
    return call_object(component, "get_gameObject", 0, nullptr);
}

Il2CppObject* get_transform(Il2CppObject* component) {
    return call_object(component, "get_transform", 0, nullptr);
}

// Port of the PC mod's IsVisible: a fully transparent hotspot with no text is
// not a control (the sign-in page hides a developer console trigger that way).
bool control_visible(Il2CppObject* component, const std::string& label) {
    if (g_cls.graphic == nullptr || component == nullptr) return true;
    Il2CppObject* target = call_object(component, "get_targetGraphic", 0, nullptr);
    if (target == nullptr) return true;
    Il2CppObject* boxed = call_object(target, "get_color", 0, nullptr);
    if (boxed == nullptr) return true;
    auto* color = static_cast<float*>(g_api.object_unbox(boxed));
    if (color == nullptr) return true;
    if (color[3] > 0.01f) return true;
    return !label.empty();
}

bool object_active(Il2CppObject* go) {
    return go != nullptr && call_bool(go, "get_activeInHierarchy");
}

struct Vec3 {
    float x, y, z;
};

Vec3 world_position(Il2CppObject* transform) {
    Vec3 out{0, 0, 0};
    Il2CppObject* boxed = call_object(transform, "get_position", 0, nullptr);
    if (boxed == nullptr) return out;
    auto* v = static_cast<float*>(g_api.object_unbox(boxed));
    if (v != nullptr) {
        out.x = v[0];
        out.y = v[1];
        out.z = v[2];
    }
    return out;
}

int screen_height() {
    if (g_cls.screen == nullptr) return 1080;
    // Screen.height is a static property: invoke on the class, not an instance.
    const MethodInfo* m = method_on(g_cls.screen, "get_height", 0);
    if (m == nullptr) return 1080;
    Il2CppObject* r = invoke(m, nullptr, nullptr, 0);
    if (r == nullptr) return 1080;
    auto* v = static_cast<int32_t*>(g_api.object_unbox(r));
    return v != nullptr ? *v : 1080;
}

int screen_width() {
    if (g_cls.screen == nullptr) return 1920;
    const MethodInfo* m = method_on(g_cls.screen, "get_width", 0);
    if (m == nullptr) return 1920;
    Il2CppObject* r = invoke(m, nullptr, nullptr, 0);
    if (r == nullptr) return 1920;
    auto* v = static_cast<int32_t*>(g_api.object_unbox(r));
    return v != nullptr ? *v : 1920;
}



int32_t instance_id(Il2CppObject* obj) {
    if (obj == nullptr) return 0;
    Il2CppClass* k = g_api.object_get_class(obj);
    const MethodInfo* m = method_on(k, "GetInstanceID", 0);
    if (m == nullptr) return 0;
    Il2CppObject* r = invoke(m, obj, nullptr, 0);
    if (r == nullptr) return 0;
    auto* v = static_cast<int32_t*>(g_api.object_unbox(r));
    return v != nullptr ? *v : 0;
}

// Every component of one type at or below a transform. This build has no
// non-generic GetComponentsInChildren(Type, bool): the only overloads are
// GetComponentsInChildren<T>(bool[, List<T>]) (dump.cs:798602), which cannot
// be invoked by name with a runtime Type, so every such call used to resolve
// to nothing (or, worse, to the generic two-argument overload with a Type in
// the List slot). Component.GetComponent(Type) does exist (dump.cs:798037),
// so the hierarchy is walked instead. Depth- and count-capped
// because this runs on Unity's main thread during a poll.
void components_under(Il2CppObject* transform, Il2CppObject* type_obj, bool include_inactive,
                      std::vector<Il2CppObject*>& out, int depth = 0) {
    if (transform == nullptr || type_obj == nullptr || depth > 8 || out.size() >= 4096) return;
    Il2CppObject* go = call_object(transform, "get_gameObject", 0, nullptr);
    if (go != nullptr && (include_inactive || object_active(go))) {
        void* p[1] = {type_obj};
        Il2CppObject* comp = call_object(go, "GetComponent", 1, p);
        if (comp != nullptr) out.push_back(comp);
    }
    int32_t children = call_int(transform, "get_childCount");
    if (children <= 0) return;
    if (children > 64) children = 64;
    for (int32_t i = 0; i < children; ++i) {
        void* q[1] = {&i};
        components_under(call_object(transform, "GetChild", 1, q), type_obj, include_inactive, out,
                         depth + 1);
    }
}

Il2CppArrayLayout* find_objects_of_type(Il2CppClass* type) {
    if (g_cls.object == nullptr || type == nullptr) return nullptr;
    Il2CppObject* type_obj = type_object(type);
    if (type_obj == nullptr) return nullptr;
    void* params[1] = {type_obj};
    // Unity 6 keeps FindObjectsOfType(Type); the sort-mode overload is the
    // replacement and is tried when the old name is gone.
    Il2CppObject* result = invoke_static(g_cls.object, "FindObjectsOfType", 1, params);
    if (result == nullptr) {
        int32_t sort_mode = 0;  // FindObjectsSortMode.None
        void* p2[2] = {type_obj, &sort_mode};
        result = invoke_static(g_cls.object, "FindObjectsByType", 2, p2);
    }
    // Builds with engine-code stripping can lose both of the above; this one
    // also returns inactive objects, which the active checks filter out.
    if (result == nullptr && g_cls.resources != nullptr) {
        result = invoke_static(g_cls.resources, "FindObjectsOfTypeAll", 1, params);
    }
    return reinterpret_cast<Il2CppArrayLayout*>(result);
}

// Walk every loaded scene and ask each root for its components of one type.
// This is the fallback when FindObjectsOfType/FindObjectsByType are stripped
// from the build (which returns no objects at all, not an empty scene).
void scene_collect(Il2CppClass* type, std::vector<Il2CppObject*>& out) {
    if (g_cls.scene_manager == nullptr || type == nullptr) return;
    Il2CppObject* type_obj = type_object(type);
    if (type_obj == nullptr) return;

    auto append_from_scene = [&](Il2CppObject* scene_obj) {
        if (scene_obj == nullptr) return;
        Il2CppObject* roots = call_object(scene_obj, "GetRootGameObjects", 0, nullptr);
        if (roots == nullptr) return;
        auto* arr = reinterpret_cast<Il2CppArrayLayout*>(roots);
        // Mid scene-load the runtime can hand back a torn array header with
        // a wild length; iterating it SIGBUSes the game (0x700000004c).
        if (arr->max_length > 100000) return;
        g_stat_scene_roots += static_cast<int>(arr->max_length);
        bool include_inactive = false;
        for (size_t i = 0; i < arr->max_length; ++i) {
            Il2CppObject* root = arr->vector[i];
            if (root == nullptr) continue;
            (void)include_inactive;
            std::vector<Il2CppObject*> found;
            components_under(call_object(root, "get_transform", 0, nullptr), type_obj, false, found);
            g_stat_scene_objects += static_cast<int>(found.size());
            out.insert(out.end(), found.begin(), found.end());
        }
    };

    int32_t count = call_int_static(g_cls.scene_manager, "get_sceneCount");
    g_stat_scene_count = count;
    for (int32_t i = 0; i < count; ++i) {
        void* params[1] = {&i};
        append_from_scene(invoke_static(g_cls.scene_manager, "GetSceneAt", 1, params));
    }
    if (out.empty()) {
        append_from_scene(invoke_static(g_cls.scene_manager, "GetActiveScene", 0, nullptr));
    }
}

// Managers that expose a static Instance and own a UI hierarchy. Scanning
// from these roots does not depend on FindObjectsOfType at all.
const char* const kSingletonManagers[] = {
    "PopupManager", "ConsentManager", "SettingsMenu", "Picker", "UIBattle",
    "UIBattleSpeed", "UIBattleIntro", "PlaybackManager", "BoardController",
    "BattleController", "Service", "ProductManager", "PurchasingManager",
    "AchievementManager", "SyncManager", "LanguageLibrary",
};

Il2CppObject* singleton_instance(const char* type_name) {
    Il2CppClass* klass = find_class("Spacewood.Unity", type_name);
    if (klass == nullptr) return nullptr;
    const MethodInfo* get_instance = method_on(klass, "get_Instance", 0);
    if (get_instance == nullptr) return nullptr;
    return invoke(get_instance, nullptr, nullptr, 0);
}

void singleton_collect(Il2CppClass* type, std::vector<Il2CppObject*>& out) {
    Il2CppObject* type_obj = type_object(type);
    if (type_obj == nullptr) return;
    std::vector<int32_t> seen_roots;
    for (const char* name : kSingletonManagers) {
        Il2CppObject* instance = singleton_instance(name);
        if (instance == nullptr) continue;
        Il2CppObject* transform = get_transform(instance);
        if (transform == nullptr) continue;
        Il2CppObject* root_transform = call_object(transform, "get_root", 0, nullptr);
        if (root_transform == nullptr) continue;
        Il2CppObject* root = get_game_object(root_transform);
        if (root == nullptr) continue;
        int32_t root_id = instance_id(root);
        if (root_id != 0 &&
            std::find(seen_roots.begin(), seen_roots.end(), root_id) != seen_roots.end()) {
            continue;
        }
        if (root_id != 0) seen_roots.push_back(root_id);

        std::vector<Il2CppObject*> found;
        components_under(call_object(root, "get_transform", 0, nullptr), type_obj, true, found);
        g_stat_scene_objects += static_cast<int>(found.size());
        out.insert(out.end(), found.begin(), found.end());
    }
}

std::vector<Il2CppObject*> collect(Il2CppClass* type) {
    std::vector<Il2CppObject*> out;
    Il2CppArrayLayout* arr = find_objects_of_type(type);
    if (arr != nullptr && arr->max_length <= 100000) {
        for (size_t i = 0; i < arr->max_length; ++i) {
            if (arr->vector[i] != nullptr) out.push_back(arr->vector[i]);
        }
    }
    if (out.empty()) {
        g_stat_find_empty++;
        singleton_collect(type, out);
    } else {
        g_stat_find_objects += static_cast<int>(out.size());
    }
    if (out.empty()) scene_collect(type, out);
    return out;
}

// ---------------------------------------------------------------------------
// Text collection
// ---------------------------------------------------------------------------

std::string clean_native(std::string text) {
    // Strip rich-text tags; the service applies the full speech cleaning rules
    // ported from TextExtractor.CleanText.
    std::string out;
    out.reserve(text.size());
    bool in_tag = false;
    for (char c : text) {
        if (c == '<') {
            in_tag = true;
            continue;
        }
        if (c == '>' && in_tag) {
            in_tag = false;
            continue;
        }
        if (!in_tag) out += c;
    }
    // Collapse runs of whitespace.
    std::string collapsed;
    collapsed.reserve(out.size());
    bool space = false;
    for (char c : out) {
        if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
            space = true;
            continue;
        }
        if (space && !collapsed.empty()) collapsed += ' ';
        space = false;
        collapsed += c;
    }
    return collapsed;
}

bool inside_any(Il2CppObject* transform, const std::vector<int32_t>& roots);

void collect_text(Il2CppObject* transform, std::vector<std::string>& parts, int depth,
                  const std::vector<int32_t>& skip_roots) {
    if (transform == nullptr || depth > 8) return;
    Il2CppObject* go = get_game_object(transform);
    if (!object_active(go)) return;
    for (Il2CppClass* text_class : {g_cls.tmp_text, g_cls.ui_text}) {
        Il2CppObject* text = get_component(transform, text_class);
        if (text == nullptr) continue;
        std::string value = call_string(text, "get_text");
        std::string cleaned = clean_native(value);
        if (cleaned.empty()) continue;
        bool seen = false;
        for (const auto& p : parts) {
            if (p == cleaned) {
                seen = true;
                break;
            }
        }
        if (!seen) parts.push_back(cleaned);
    }
    int32_t children = call_int(transform, "get_childCount");
    for (int32_t i = 0; i < children; ++i) {
        void* params[1] = {&i};
        Il2CppObject* child = call_object(transform, "GetChild", 1, params);
        if (inside_any(child, skip_roots)) continue;
        collect_text(child, parts, depth + 1, skip_roots);
    }
}

FieldInfo* find_field(Il2CppClass* klass, const char* name) {
    if (g_api.class_get_field_from_name == nullptr) return nullptr;
    for (Il2CppClass* cur = klass; cur != nullptr; cur = g_api.class_get_parent(cur)) {
        if (FieldInfo* field = g_api.class_get_field_from_name(cur, name)) return field;
    }
    return nullptr;
}

// ButtonBase carries its caption on Label/LabelRight (TextMeshProUGUI), which
// the dump gives us; reading those is exact where walking children is a guess.
std::string label_from_fields(Il2CppObject* component) {
    if (g_api.field_get_value == nullptr) return {};
    Il2CppClass* klass = g_api.object_get_class(component);
    std::vector<std::string> parts;
    auto add_text = [&parts](Il2CppObject* text) {
        if (text == nullptr) return;
        std::string value = clean_native(call_string(text, "get_text"));
        if (value.empty()) return;
        for (const auto& part : parts) {
            if (part == value) return;
        }
        parts.push_back(value);
    };
    for (const char* name : {"Label", "LabelRight"}) {
        FieldInfo* field = find_field(klass, name);
        if (field == nullptr) continue;
        Il2CppObject* text = nullptr;
        g_api.field_get_value(component, field, &text);
        add_text(text);
    }
    // ButtonBase.LocalizedTextMesh is a BestLocalizeTextMesh, whose TextMesh is
    // the TMP object that actually carries the localized caption. On the consent
    // window the Label fields above are still empty while this one is filled in.
    if (FieldInfo* holder_field = find_field(klass, "LocalizedTextMesh")) {
        Il2CppObject* holder = nullptr;
        g_api.field_get_value(component, holder_field, &holder);
        if (holder != nullptr) {
            if (FieldInfo* text_field = find_field(g_api.object_get_class(holder), "TextMesh")) {
                Il2CppObject* text = nullptr;
                g_api.field_get_value(holder, text_field, &text);
                add_text(text);
            }
        }
    }
    std::string out;
    for (const auto& part : parts) {
        if (!out.empty()) out += ", ";
        out += part;
    }
    return out;
}

// The camera that actually draws a control is its own Canvas's world camera,
// not Camera.main: this game draws the 3D board with the main camera and the
// UI through another one, so projecting UI points through the board camera
// lands light-years off screen.
Il2CppObject* canvas_of(Il2CppObject* component) {
    if (g_cls.canvas == nullptr) {
        static bool logged = false;
        if (!logged) {
            logged = true;
            native_log("canvas: class lookup failed, no Canvas type");
        }
        return nullptr;
    }
    // Fast path: engine helper (may be stripped).
    Il2CppObject* canvas = get_component_in_parent(component, g_cls.canvas);
    if (canvas != nullptr) return canvas;
    // Fallback: walk parents manually via get_parent + GetComponent, which
    // does not depend on GetComponentInParent surviving code stripping.
    Il2CppObject* tf = get_transform(component);
    for (int guard = 0; tf != nullptr && guard < 64; ++guard) {
        Il2CppObject* go = call_object(tf, "get_gameObject", 0, nullptr);
        if (go != nullptr) {
            void* p[1] = {type_object(g_cls.canvas)};
            // GameObject.GetComponent(Type) avoids the stripped Component path.
            Il2CppObject* c = nullptr;
            if (g_cls.game_object != nullptr && p[0] != nullptr) {
                const MethodInfo* m = method_on(g_cls.game_object, "GetComponent", 1);
                if (m != nullptr) c = invoke(m, go, p, 1);
            }
            if (c == nullptr) c = get_component(tf, g_cls.canvas);
            if (c != nullptr) {
                static bool logged = false;
                if (!logged) {
                    logged = true;
                    native_log("canvas: found via parent walk");
                }
                return c;
            }
        }
        tf = call_object(tf, "get_parent", 0, nullptr);
    }
    return nullptr;
}

Il2CppObject* main_camera() {
    if (g_cls.camera == nullptr) return nullptr;
    return invoke_static(g_cls.camera, "get_main", 0, nullptr);
}

bool project_through(Il2CppObject* cam, const Vec3& pos, float* sx, float* sy) {
    if (cam == nullptr || g_cls.camera == nullptr) return false;
    const MethodInfo* w2s = method_on(g_cls.camera, "WorldToScreenPoint", 1);
    if (w2s == nullptr) return false;
    Vec3 in{pos.x, pos.y, pos.z};
    void* params[1] = {&in};
    Il2CppObject* r = invoke(w2s, cam, params, 1);
    if (r == nullptr) return false;
    auto* v = static_cast<float*>(g_api.object_unbox(r));
    if (v == nullptr) return false;
    *sx = v[0];
    *sy = v[1];
    return true;
}

void log_canvas_once(Il2CppObject* canvas, int mode, Il2CppObject* world_camera) {
    // Log once per distinct canvas setup (screens swap canvases), so the
    // report shows which camera each menu projects through.
    static int last_mode = -2;
    static std::string last_cam;
    std::string cam_name;
    if (world_camera != nullptr) {
        Il2CppObject* go = get_game_object(world_camera);
        if (go != nullptr) cam_name = call_string(go, "get_name");
    }
    if (canvas == nullptr) {
        if (last_mode != -1) {
            last_mode = -1;
            native_log("canvas: none found, holding tree");
        }
        return;
    }
    if (mode == last_mode && cam_name == last_cam) return;
    last_mode = mode;
    last_cam = cam_name;
    float scale = 0.0f;
    Il2CppObject* s = call_object(canvas, "get_scaleFactor", 0, nullptr);
    if (s != nullptr) {
        auto* v = static_cast<float*>(g_api.object_unbox(s));
        if (v != nullptr) scale = *v;
    }
    // planeDistance is not a Canvas member in this build (the whole class is
    // stripped to its used members: no get_planeDistance anywhere in the
    // reference), so the log no longer claims to report it.
    std::string cam = cam_name.empty() ? "(none)" : cam_name;
    native_log("canvas: renderMode=%d scaleFactor=%.3f worldCamera=%s screenPx=%dx%d",
               mode, scale, cam.c_str(), screen_width(), screen_height());
}

int render_mode(Il2CppObject* canvas) {
    Il2CppObject* r = call_object(canvas, "get_renderMode", 0, nullptr);
    if (r == nullptr) return 0;
    auto* v = static_cast<int32_t*>(g_api.object_unbox(r));
    return v != nullptr ? *v : 0;
}

// Screen rect for a control, in pixels. The canvas is resolved once and
// shared: the position is the pivot projected through the control's own
// canvas camera (never raw world units, which crash TalkBack), and the size
// projects the RectTransform edges through a camera too, because rect units
// are pixels only on overlay canvases (a full-width button measured 9x2
// world units, which TalkBack exposed as an untappable 8x3 px node).
// Unprojectable points get a sentinel the Java layer drops; sizes keep
// their world values then, so only genuinely 0x0 controls are filtered out.
void node_rect(Il2CppObject* component, Il2CppObject* transform,
               float* x, float* y, float* w, float* h) {
    *x = -10000;
    *y = -10000;
    *w = 0;
    *h = 0;
    if (transform == nullptr || g_cls.rect_transform == nullptr) return;
    // RectTransform.rect is a Rect struct: x, y, width, height.
    Il2CppObject* rect_transform = get_component(transform, g_cls.rect_transform);
    if (rect_transform == nullptr) return;
    Il2CppObject* boxed = call_object(rect_transform, "get_rect", 0, nullptr);
    if (boxed == nullptr) return;
    auto* v = static_cast<float*>(g_api.object_unbox(boxed));
    if (v == nullptr) return;
    float world_w = v[2];
    float world_h = v[3];
    Il2CppObject* scale = call_object(transform, "get_lossyScale", 0, nullptr);
    if (scale != nullptr) {
        auto* s = static_cast<float*>(g_api.object_unbox(scale));
        if (s != nullptr) {
            world_w = std::fabs(world_w * s[0]);
            world_h = std::fabs(world_h * s[1]);
        }
    }
    *w = world_w;
    *h = world_h;

    Vec3 pos = world_position(transform);
    Il2CppObject* canvas = canvas_of(component);
    int mode = canvas != nullptr ? render_mode(canvas) : 0;
    Il2CppObject* cam = nullptr;
    if (canvas != nullptr) cam = call_object(canvas, "get_worldCamera", 0, nullptr);
    log_canvas_once(canvas, mode, cam);

    float sx = pos.x;
    float sy = pos.y;
    bool projected = false;
    int path = 0;  // 1 overlay pixels, 2 canvas camera, 3 main camera
    if (canvas != nullptr && mode != 0 && cam != nullptr) {
        projected = project_through(cam, pos, &sx, &sy);
        if (projected) path = 2;
    } else if (canvas != nullptr && mode == 0) {
        // Overlay: positions are already screen pixels (y up) when sane.
        // Huge values mean the canvas resolved on the wrong root.
        if (std::fabs(pos.x) < 5000 && std::fabs(pos.y) < 5000) {
            projected = true;
            path = 1;
        }
    }
    if (!projected) {
        Il2CppObject* main = main_camera();
        if (main != nullptr && project_through(main, pos, &sx, &sy)) {
            projected = true;
            path = 3;
            static bool logged = false;
            if (!logged) {
                logged = true;
                native_log("canvas: fell back to main camera projection");
            }
        }
    }
    // Also try the canvas camera even in overlay mode when raw looks insane.
    if (!projected && canvas != nullptr && cam != nullptr) {
        if (project_through(cam, pos, &sx, &sy)) {
            projected = true;
            path = 2;
        }
    }
    if (!projected) {
        ++g_stat_proj_none;
        static bool logged = false;
        if (!logged) {
            logged = true;
            native_log("canvas: no projection available, raw world %.0f,%.0f", pos.x, pos.y);
        }
        return;
    }
    if (path == 1) ++g_stat_proj_overlay;
    else if (path == 2) ++g_stat_proj_canvas;
    else ++g_stat_proj_main;
    if (sx < -2000 || sx > 10000 || sy < -2000 || sy > 10000) {
        static int dropped = 0;
        if (dropped < 3) {
            ++dropped;
            native_log("canvas: projected off-screen %.0f,%.0f raw %.0f,%.0f mode=%d",
                       sx, sy, pos.x, pos.y, mode);
        }
        return;
    }
    *x = sx;
    *y = static_cast<float>(screen_height()) - sy;

    if (canvas != nullptr && mode == 0) return;  // overlay: already pixels
    Il2CppObject* size_cam = cam != nullptr ? cam : main_camera();
    if (size_cam == nullptr || world_w <= 0 || world_h <= 0) return;
    Vec3 right{pos.x + world_w, pos.y, pos.z};
    Vec3 up{pos.x, pos.y + world_h, pos.z};
    float sx2 = 0, sy2 = 0, sx3 = 0, sy3 = 0;
    if (project_through(size_cam, right, &sx2, &sy2) &&
        project_through(size_cam, up, &sx3, &sy3)) {
        // Differences are already pixels, so no y-flip is needed. Java
        // clamps oversized boxes to the display; only sub-pixel noise
        // falls back below.
        float pw = std::fabs(sx2 - sx);
        float ph = std::fabs(sy3 - sy);
        if (pw > 0.5f && ph > 0.5f) {
            *w = pw;
            *h = ph;
            return;
        }
    }
    float ortho = call_float(size_cam, "get_orthographicSize");
    if (ortho > 0.01f) {
        float ppu = static_cast<float>(screen_height()) / (2.0f * ortho);
        *w = world_w * ppu;
        *h = world_h * ppu;
    }
}

std::string join_text(Il2CppObject* transform, const std::vector<int32_t>& skip_roots = {}) {
    std::vector<std::string> parts;
    collect_text(transform, parts, 0, skip_roots);
    std::string out;
    for (const auto& p : parts) {
        if (!out.empty()) out += ", ";
        out += p;
    }
    return out;
}

Il2CppObject* get_parent(Il2CppObject* transform) {
    return call_object(transform, "get_parent", 0, nullptr);
}

// Unity wrappers are distinct managed objects per call, so identity is the
// engine instance id, never the managed pointer.
bool is_descendant_of(Il2CppObject* transform, int32_t ancestor_id) {
    int guard = 0;
    for (Il2CppObject* t = transform; t != nullptr && guard++ < 64; t = get_parent(t)) {
        if (instance_id(t) == ancestor_id) return true;
    }
    return false;
}

bool get_checked(Il2CppObject* obj) { return call_bool(obj, "get_isOn"); }

// ---------------------------------------------------------------------------
// Nodes
// ---------------------------------------------------------------------------

struct Node {
    int32_t id = 0;
    std::string role = "button";
    std::string label;
    std::string value;
    bool enabled = true;
    bool checked = false;
    float x = 0;
    float y = 0;
    // Size of the control's RectTransform, for the TalkBack virtual tree.
    float w = 0;
    float h = 0;
    bool focusable = true;
    // Pet card line for shop, team and battle: stats and ability, spoken
    // after the label the way the PC mod reads them line by line.
    std::string detail;
    // Shop zone for TalkBack ordering and S/F/T/D/V jumps, mirroring the PC
    // mod's ShopZone: petshop, foodshop, team, actions, bullies, or empty.
    std::string zone;
    // The game's own item numbers, so the port stops guessing: ItemModel.Price
    // (dump.cs:555387), ItemModel.Frozen (dump.cs:555389) and
    // MinionModel.SellValue (dump.cs:551320). -1 means the member did not
    // resolve; that is the only case where the port's own defaults apply.
    int price = -1;
    int frozen = -1;
    int sell = -1;
};

std::string role_of(Il2CppObject* component) {
    if (is_a(g_cls.toggle, component)) return "toggle";
    if (is_a(g_cls.slider, component)) return "slider";
    if (is_a(g_cls.scrollbar, component)) return "scrollbar";
    if (is_a(g_cls.tmp_input_field, component) || is_a(g_cls.input_field, component)) return "input";
    if (is_a(g_cls.dropdown, component)) return "dropdown";
    if (is_a(g_cls.button, component)) return "button";
    return "button";
}

std::string value_of(Il2CppObject* component, const std::string& role) {
    if (role == "toggle") return get_checked(component) ? "checked" : "not checked";
    if (role == "scrollbar") {
        // Scrollbars are always 0..1, so speak a percentage, not a decimal.
        float v = call_float(component, "get_value");
        if (v < 0) v = 0;
        if (v > 1) v = 1;
        char buf[32];
        snprintf(buf, sizeof(buf), "%d percent", static_cast<int>(v * 100 + 0.5f));
        return buf;
    }
    if (role == "slider") {
        float v = call_float(component, "get_value");
        char buf[32];
        snprintf(buf, sizeof(buf), "%.2f", v);
        return buf;
    }
    if (role == "dropdown") {
        // The selected option's caption, so the box reads its choice.
        Il2CppObject* caption = call_object(component, "get_captionText", 0, nullptr);
        if (caption != nullptr) return clean_native(call_string(caption, "get_text"));
        return {};
    }
    if (role == "input") return call_string(component, "get_text");
    return {};
}

// The consent window's four captions are set by localization components that
// the generic field reader cannot see on first frame, but ConsentWindow names
// each button explicitly. Reading those fields gives those controls a stable
// spoken label even when every text component is still blank.
std::vector<std::pair<int32_t, std::string>> consent_button_labels() {
    std::vector<std::pair<int32_t, std::string>> out;
    if (g_api.field_get_value == nullptr) return out;
    Il2CppClass* manager_class = find_class("Spacewood.Unity", "ConsentManager");
    if (manager_class == nullptr) return out;
    // Read MonoSingleton._instance directly. Calling get_Instance() would run
    // FindObjectOfType when the field is null, and that is a live engine call
    // from the Android UI thread: it deadlocks against Unity's main thread.
    Il2CppObject* manager = nullptr;
    if (g_api.class_get_field_from_name != nullptr && g_api.field_static_get_value != nullptr) {
        for (Il2CppClass* cur = manager_class; cur != nullptr; cur = g_api.class_get_parent(cur)) {
            FieldInfo* field = g_api.class_get_field_from_name(cur, "_instance");
            if (field == nullptr) continue;
            g_api.field_static_get_value(field, &manager);
            break;
        }
    }
    if (manager == nullptr) return out;
    FieldInfo* window_field = find_field(manager_class, "Window");
    if (window_field == nullptr) return out;
    Il2CppObject* window = nullptr;
    g_api.field_get_value(manager, window_field, &window);
    if (window == nullptr) return out;
    Il2CppClass* window_class = g_api.object_get_class(window);
    struct Entry { const char* field; const char* label; };
    static const Entry kEntries[] = {
        {"PrivacyPolicyButton", "Privacy policy"},
        {"ServiceTermsButton", "Terms of service"},
        {"SubmitButton", "Accept"},
        {"AnalyticsButton", "Analytics"},
    };
    for (const Entry& entry : kEntries) {
        FieldInfo* field = find_field(window_class, entry.field);
        if (field == nullptr) continue;
        Il2CppObject* button = nullptr;
        g_api.field_get_value(window, field, &button);
        if (button == nullptr) continue;
        int32_t id = instance_id(button);
        if (id != 0) out.emplace_back(id, entry.label);
    }
    return out;
}

// ---------------------------------------------------------------------------
// Shop and run state (PC-mod parity, read-only)
// ---------------------------------------------------------------------------

int call_int_neg(Il2CppObject* self, const char* name) {
    Il2CppObject* r = call_object(self, name, 0, nullptr);
    if (r == nullptr) return -1;
    auto* v = static_cast<int32_t*>(g_api.object_unbox(r));
    return v != nullptr ? *v : -1;
}

// An IntegerStat behind a field or a getter; -1 when absent or out of range.
int stat_total(Il2CppObject* model, const char* name) {
    if (model == nullptr) return -1;
    Il2CppObject* stat = nullptr;
    if (FieldInfo* field = find_field(g_api.object_get_class(model), name)) {
        g_api.field_get_value(model, field, &stat);
    }
    if (stat == nullptr) {
        stat = call_object(model, (std::string("get_") + name).c_str(), 0, nullptr);
    }
    if (stat == nullptr) return -1;
    Il2CppObject* total = call_object(stat, "get_Total", 0, nullptr);
    if (total == nullptr) return -1;
    auto* v = static_cast<int32_t*>(g_api.object_unbox(total));
    if (v == nullptr || *v < 0 || *v > 999) return -1;
    return *v;
}

// A plain int behind a getter or a field; -1 when absent or out of range.
// The field path reads through a pointer-wide slot, so an object-typed field
// with the same name cannot smash the stack; the range check drops it.
int model_int(Il2CppObject* model, const char* name, int min_value, int max_value) {
    if (model == nullptr) return -1;
    if (Il2CppObject* r = call_object(model, (std::string("get_") + name).c_str(), 0, nullptr)) {
        if (auto* v = static_cast<int32_t*>(g_api.object_unbox(r))) {
            if (*v >= min_value && *v <= max_value) return *v;
        }
    }
    if (FieldInfo* field = find_field(g_api.object_get_class(model), name)) {
        uintptr_t raw = 0;
        g_api.field_get_value(model, field, &raw);
        int32_t v = static_cast<int32_t>(raw);
        if (v >= min_value && v <= max_value) return v;
    }
    return -1;
}

Il2CppObject* hangar_main_instance();
Il2CppObject* prop_or_field(Il2CppObject* self, const char* name);

// One-time dump of a class's exact method and field names, so the report
// file (or logcat) names the members instead of guessing them.
void diag_class(const char* ns, const char* name) {
    Il2CppClass* k = find_class(ns, name);
    if (k == nullptr) {
        native_log("class %s.%s: NOT FOUND", ns, name);
        return;
    }
    if (g_api.class_get_methods != nullptr && g_api.method_get_name != nullptr) {
        void* it = nullptr;
        int n = 0;
        std::string names;
        while (const MethodInfo* m = g_api.class_get_methods(k, &it)) {
            const char* mn = g_api.method_get_name(m);
            if (mn == nullptr) continue;
            if (!names.empty()) names += ",";
            names += mn;
            if (g_api.method_get_param_count != nullptr) {
                char buf[16];
                snprintf(buf, sizeof(buf), "/%u", g_api.method_get_param_count(m));
                names += buf;
            }
            // Preview only: these lines used to name every method and field, and
            // the resulting metadata dump filled the whole report, cutting the
            // actual play log off before it could be read.
            if (names.size() > 600) names = names.substr(0, 600) + ",...";
            if (++n > 300) break;
        }
        native_log("class %s.%s methods(%d): %s", ns, name, n, names.c_str());
    }
    if (g_api.class_get_fields != nullptr && g_api.field_get_name != nullptr) {
        void* it = nullptr;
        int n = 0;
        std::string names;
        while (FieldInfo* f = g_api.class_get_fields(k, &it)) {
            const char* fn = g_api.field_get_name(f);
            if (fn == nullptr) continue;
            if (!names.empty()) names += ",";
            names += fn;
            if (names.size() > 600) names = names.substr(0, 600) + ",...";
            if (++n > 300) break;
        }
        native_log("class %s.%s fields(%d): %s", ns, name, n, names.c_str());
    }
}

void diag_game_classes() {
    static bool done = false;
    if (done) return;
    done = true;
    diag_class("Spacewood.Unity.MonoBehaviours.Build", "HangarMain");
    diag_class("Spacewood.Unity.MonoBehaviours.Build", "HangarOverlay");
    diag_class("Spacewood.Unity.MonoBehaviours.Build", "MinionShop");
    diag_class("Spacewood.Unity.MonoBehaviours.Build", "SpellShop");
    diag_class("Spacewood.Unity.Views", "MinionArmy");
    diag_class("Spacewood.Unity.Views", "Space");
    diag_class("Spacewood.Unity.Views", "MinionView");
    diag_class("Spacewood.Unity.MonoBehaviours.Battle", "BattleController");
    diag_class("Spacewood.Unity.MonoBehaviours.Battle", "UIBattle");
    diag_class("Spacewood.Core.Models", "BoardModel");
    diag_class("Spacewood.Core.Models", "MinionModel");
    diag_class("Spacewood.Unity", "ConsentManager");
    diag_class("Spacewood.Unity", "ConsentData");
}

// Which HangarMain/HangarOverlay members actually resolved on this build.
void probe_shop_api() {
    static bool done = false;
    if (done) return;
    Il2CppObject* hangar = hangar_main_instance();
    if (hangar == nullptr) return;
    done = true;
    static const char* kHangar[] = {"Overlay", "MinionShop", "SpellShop",
                                    "MinionArmy", "StateMachine", nullptr};
    for (int i = 0; kHangar[i] != nullptr; ++i) {
        native_log("hangar.%s: %s", kHangar[i],
                   prop_or_field(hangar, kHangar[i]) ? "FOUND" : "missing");
    }
    Il2CppObject* overlay = prop_or_field(hangar, "Overlay");
    if (overlay != nullptr) {
        static const char* kOverlay[] = {"Roll",     "Freeze", "SellButton", "Sell",
                                         "DoneButton", "Done", "Timer",      "Menu",
                                         nullptr};
        for (int i = 0; kOverlay[i] != nullptr; ++i) {
            native_log("overlay.%s: %s", kOverlay[i],
                       prop_or_field(overlay, kOverlay[i]) ? "FOUND" : "missing");
        }
    }
}

// Localized text through a one- or two-argument static extension method
// (GetNameLocalized takes the model, with an optional trailing bool).
// MinionModelExtensions.GetNameLocalized(MinionModel) and
// GetAbilityLocalized(MinionModel) are both one-argument statics
// (dump.cs:644809, dump.cs:644783). A two-argument fallback used to try the
// same names with a bool glued on, but the only two-argument overloads are
// GetNameLocalized(string,string) (dump.cs:644560) and
// GetAbilityLocalized(Nullable<Perk>,Nullable<int>) (dump.cs:644862): that
// fallback could only ever call a different method with the wrong argument
// types, so it is gone rather than guarded.
std::string localize_static(Il2CppClass* klass, const char* name, Il2CppObject* model) {
    if (klass == nullptr || model == nullptr) return {};
    void* params[1] = {model};
    const MethodInfo* m = method_on(klass, name, 1);
    if (m == nullptr) return {};
    Il2CppObject* r = invoke(m, nullptr, params, 1);
    if (r == nullptr) return {};
    return clean_native(to_utf8(reinterpret_cast<Il2CppString*>(r)));
}

std::string pet_detail_for_minion(Il2CppObject* minion);  // defined below
Il2CppObject* shop_item_of(Il2CppObject* component);       // defined with the actions

// Pet card detail in the PC mod's reading order: name, level, attack, health,
// tier, then the ability text. Any control sitting under a MinionView (or a
// scoreboard MinionCanvasView) gets one, in the shop, on the team, in battle
// and on the scoreboard alike.
std::string pet_detail(Il2CppObject* component) {
    Il2CppObject* minion = nullptr;
    // A shop or team slot is a Space whose card lives in a child MinionView
    // (fields: <MinionView>, <MinionModel>), so a parent search misses it and
    // an occupied slot was published as an empty one. Read the slot's own
    // fields first, then fall back to the parent walk for plain controls.
    if (component != nullptr && g_cls.space != nullptr && is_a(g_cls.space, component)) {
        if (Il2CppObject* view = prop_or_field(component, "MinionView")) {
            minion = call_object(view, "get_Minion", 0, nullptr);
        }
        if (minion == nullptr) minion = prop_or_field(component, "MinionModel");
    }
    if (minion == nullptr && g_cls.minion_view != nullptr) {
        Il2CppObject* view = get_component_in_parent(component, g_cls.minion_view);
        if (view != nullptr) minion = call_object(view, "get_Minion", 0, nullptr);
    }
    if (minion == nullptr && g_cls.minion_canvas_view != nullptr) {
        Il2CppObject* view = get_component_in_parent(component, g_cls.minion_canvas_view);
        if (view != nullptr) minion = call_object(view, "get_Model", 0, nullptr);
    }
    // A shop card is a button wrapping the pet, so its MinionView is a *child*
    // and every parent search above misses it. That is why the shop slots read
    // as blank (and were then dropped): probe reported shopPets=3 while the
    // published tree held only the scoreboard and the V/S tabs.
    if (minion == nullptr && g_cls.minion_view != nullptr) {
        Il2CppObject* go = get_game_object(component);
        if (go != nullptr) {
            Il2CppObject* type_obj = type_object(g_cls.minion_view);
            if (type_obj != nullptr) {
                bool include_inactive = true;
                void* params[2] = {type_obj, &include_inactive};
                Il2CppObject* view = call_object(go, "GetComponentInChildren", 2, params);
                if (view == nullptr) {
                    void* params1[1] = {type_obj};
                    view = call_object(go, "GetComponentInChildren", 1, params1);
                }
                if (view != nullptr) minion = call_object(view, "get_Minion", 0, nullptr);
            }
        }
    }
    if (minion == nullptr) {
        // Food and toys are spells, not minions, so they never reached this
        // path and every food slot read as empty. The card's own text is the
        // one description that is certainly there.
        Il2CppObject* transform = get_transform(component);
        if (transform != nullptr) {
            std::string text = join_text(transform);
            if (!text.empty()) return text;
        }
        return {};
    }
    return pet_detail_for_minion(minion);
}

// A card's description is decoration: a fault while reading it (half-built
// model, type whose statics are not up yet) must cost that one description,
// never the snapshot. Runs under the same guard the submit fallback uses, and
// the handler prefers the innermost guard.
std::string pet_detail_guarded(Il2CppObject* component) {
    if (sigsetjmp(g_fallback_jmp, 1)) {
        g_in_fallback = false;
        native_log("pet detail: faulted, card left undescribed");
        return {};
    }
    g_in_fallback = true;
    std::string detail = pet_detail(component);
    g_in_fallback = false;
    return detail;
}

// The game's ability text is multi-line ("Friendly ate food\nGive them +1
// attack until next turn.\nWorks 3 times per turn."). clean_native turns a
// newline into a space, which glued the trigger onto its effect and made the
// line read as one run-on sentence. Rebuild it as separate sentences instead.
std::string ability_sentences(const std::string& raw) {
    std::string out;
    size_t start = 0;
    while (start <= raw.size()) {
        size_t nl = raw.find('\n', start);
        std::string line = clean_native(
            raw.substr(start, nl == std::string::npos ? std::string::npos : nl - start));
        if (!line.empty()) {
            if (!out.empty()) out += ' ';
            out += line;
            char last = line[line.size() - 1];
            if (last != '.' && last != '!' && last != '?' && last != ':') out += '.';
        }
        if (nl == std::string::npos) break;
        start = nl + 1;
    }
    return out;
}

// Name of an enum constant from the enum class's own constant table. Reading
// an enum field gives only its ordinal, so the declared names are the only
// way to speak it ("Spacewood.Core.Models.Tribe" -> "Feline").
std::string enum_constant_name(Il2CppClass* klass, int32_t value) {
    if (klass == nullptr || g_api.class_get_fields == nullptr ||
        g_api.field_get_name == nullptr || g_api.field_static_get_value == nullptr) {
        return {};
    }
    // Only enums have a constant table worth reading; anything else (a class
    // or struct field that happens to be called Tribe) is left alone.
    Il2CppClass* parent = g_api.class_get_parent(klass);
    const char* parent_name = parent != nullptr ? g_api.class_get_name(parent) : nullptr;
    if (parent_name == nullptr || strcmp(parent_name, "Enum") != 0) return {};
    // Initialise the enum class first. Reading a static field of a class whose
    // static storage does not exist yet dereferences a null table, which is
    // exactly the sig=11 addr=0x10 this lookup produced before; type_object is
    // memoised, so this costs one call per enum. An enum's initialiser is just
    // its constants, so no game code runs here.
    type_object(klass);
    void* it = nullptr;
    int guard = 0;
    while (FieldInfo* f = g_api.class_get_fields(klass, &it)) {
        if (++guard > 200) break;
        const char* fn = g_api.field_get_name(f);
        if (fn == nullptr || strcmp(fn, "value__") == 0) continue;
        int32_t constant = 0;
        g_api.field_static_get_value(f, &constant);
        if (constant == value) return fn;
    }
    return {};
}

// A Nullable<T> class is named "Nullable`1" (or "Nullable" once a dumper
// prints the closed form), and its layout is T followed by a hasValue byte.
bool is_nullable_class(Il2CppClass* klass) {
    if (klass == nullptr) return false;
    const char* name = g_api.class_get_name(klass);
    return name != nullptr && strncmp(name, "Nullable", 8) == 0;
}

// MinionModel.Tribe. The field is an enum, a Nullable<enum> or a plain string
// depending on the build, so every shape is read; anything unreadable stays
// silent rather than putting an ordinal in the description.
std::string tribe_of(Il2CppObject* minion) {
    if (minion == nullptr || g_api.field_get_type == nullptr ||
        g_api.class_from_type == nullptr) {
        return {};
    }
    FieldInfo* field = find_field(g_api.object_get_class(minion), "Tribe");
    if (field == nullptr) return {};
    Il2CppType* type = g_api.field_get_type(field);
    if (type == nullptr) return {};
    Il2CppClass* klass = g_api.class_from_type(type);
    if (klass == nullptr) return {};
    static Il2CppClass* string_class = find_class("System", "String");
    // Wide enough for an enum, a pointer or a small struct; field_get_value
    // copies the field's own size over the top of it.
    unsigned char slot[32] = {0};
    if (klass == string_class) {
        g_api.field_get_value(minion, field, slot);
        auto* text = *reinterpret_cast<Il2CppString**>(slot);
        if (text == nullptr) return {};
        std::string value = clean_native(to_utf8(text));
        return value.size() <= 32 ? value : std::string();
    }
    g_api.field_get_value(minion, field, slot);
    Il2CppClass* enum_class = klass;
    int32_t value = *reinterpret_cast<int32_t*>(slot);
    if (is_nullable_class(klass)) {
        // This build stores the species as Nullable<TribeEnum>. Naming the
        // Nullable struct as if it were the enum read its first four bytes,
        // which are not the constant, so every pet read as tribe-less.
        if (g_api.field_get_value_object == nullptr) return {};
        Il2CppObject* boxed = g_api.field_get_value_object(g_api.domain_get(), field, minion);
        if (boxed == nullptr) return {};  // a Nullable with no value
        auto* data = static_cast<uint8_t*>(g_api.object_unbox(boxed));
        Il2CppClass* boxed_class = g_api.object_get_class(boxed);
        if (data == nullptr || boxed_class == nullptr) return {};
        if (is_nullable_class(boxed_class)) {
            // Some il2cpp versions box the Nullable itself rather than its
            // value; its own value/hasValue fields are then the only source.
            if (g_api.class_get_field_from_name == nullptr || g_api.field_get_offset == nullptr ||
                g_api.field_get_type == nullptr || g_api.class_from_type == nullptr) {
                return {};
            }
            FieldInfo* value_field = g_api.class_get_field_from_name(boxed_class, "value");
            FieldInfo* has_field = g_api.class_get_field_from_name(boxed_class, "hasValue");
            if (value_field == nullptr || has_field == nullptr) return {};
            if (data[g_api.field_get_offset(has_field)] == 0) return {};
            value = *reinterpret_cast<int32_t*>(data + g_api.field_get_offset(value_field));
            Il2CppClass* inner = g_api.class_from_type(g_api.field_get_type(value_field));
            if (inner == nullptr) return {};
            enum_class = inner;
        } else {
            value = *reinterpret_cast<int32_t*>(data);
            enum_class = boxed_class;
        }
    }
    std::string name = enum_constant_name(enum_class, value);
    // "None" is the absence of a tribe; the game also spells it "0".
    if (name == "None" || name == "0") return {};
    return name;
}

// An integer behind an optional field: Nullable<int> (MinionModel.SellValue,
// dump.cs:551320) is a struct of the value and a hasValue byte, so the value
// field's own offset has to be used. A plain int field reads directly. -1 means
// the field or its value is absent, which is what makes the caller's fallback
// reachable instead of a silently wrong 0.
int optional_field_int(Il2CppObject* obj, const char* name) {
    if (obj == nullptr || g_api.field_get_type == nullptr || g_api.class_from_type == nullptr) {
        return -1;
    }
    FieldInfo* field = find_field(g_api.object_get_class(obj), name);
    if (field == nullptr) return -1;
    Il2CppClass* klass = g_api.class_from_type(g_api.field_get_type(field));
    if (klass == nullptr) return -1;
    // Wide enough for a Nullable<int> and then some; field_get_value copies the
    // field's own size over the top of it.
    unsigned char slot[64] = {0};
    g_api.field_get_value(obj, field, slot);
    if (!is_nullable_class(klass)) return *reinterpret_cast<int32_t*>(slot);
    if (g_api.class_get_field_from_name == nullptr || g_api.field_get_offset == nullptr) return -1;
    FieldInfo* value_field = g_api.class_get_field_from_name(klass, "value");
    FieldInfo* has_field = g_api.class_get_field_from_name(klass, "hasValue");
    if (value_field == nullptr || has_field == nullptr) return -1;
    int32_t out = 0;
    if (!sapa11y_optional::read_int(slot, sizeof(slot), g_api.field_get_offset(value_field),
                                    g_api.field_get_offset(has_field), out)) {
        return -1;
    }
    return out;
}

// ItemModel.Price: an int property (dump.cs:555387, get_Price 555409), with the
// backing field as the fallback for a build that kept only one of the two.
int item_price(Il2CppObject* item) {
    if (item == nullptr) return -1;
    int price = call_int_neg(item, "get_Price");
    if (price < 0 || price > 999) price = -1;
    if (price < 0) {
        if (FieldInfo* field = find_field(g_api.object_get_class(item), "Price")) {
            int32_t value = 0;
            g_api.field_get_value(item, field, &value);
            if (value >= 0 && value <= 999) price = value;
        }
    }
    return price;
}

// ItemModel.Frozen: a bool property (dump.cs:555389, get_Frozen 555417).
// Returns -1 when the member is absent, 0 for not frozen and 1 for frozen: a
// missing member must not read as "not frozen".
int item_frozen(Il2CppObject* item) {
    if (item == nullptr) return -1;
    Il2CppClass* klass = g_api.object_get_class(item);
    if (klass == nullptr) return -1;
    if (method_on(klass, "get_Frozen", 0) != nullptr) {
        Il2CppObject* boxed = call_object(item, "get_Frozen", 0, nullptr);
        if (boxed == nullptr) return -1;
        auto* value = static_cast<uint8_t*>(g_api.object_unbox(boxed));
        if (value == nullptr) return -1;
        return *value != 0 ? 1 : 0;
    }
    if (FieldInfo* field = find_field(klass, "Frozen")) {
        uint8_t value = 0;
        g_api.field_get_value(item, field, &value);
        return value != 0 ? 1 : 0;
    }
    return -1;
}

// The item numbers behind one card, read from the card's own model. A fault
// while reading them costs those three numbers, never the snapshot.
void item_facts(Il2CppObject* component, Node& node) {
    Il2CppObject* item = shop_item_of(component);
    if (item == nullptr) return;
    node.price = item_price(item);
    node.frozen = item_frozen(item);
    node.sell = optional_field_int(item, "SellValue");
}

void item_facts_guarded(Il2CppObject* component, Node& node) {
    if (sigsetjmp(g_fallback_jmp, 1)) {
        g_in_fallback = false;
        native_log("item facts: faulted, card left without price/frozen/sell");
        return;
    }
    g_in_fallback = true;
    item_facts(component, node);
    g_in_fallback = false;
}

// An enum-valued *field*'s constant name. MinionModel exposes the species as
// both get_Type (a property) and Enum (a field), and a build can lose one of
// them; the field path is what keeps the card named.
std::string enum_field_text(Il2CppObject* obj, const char* field_name) {
    if (obj == nullptr || g_api.field_get_type == nullptr || g_api.class_from_type == nullptr) {
        return {};
    }
    FieldInfo* field = find_field(g_api.object_get_class(obj), field_name);
    if (field == nullptr) return {};
    Il2CppType* type = g_api.field_get_type(field);
    if (type == nullptr) return {};
    Il2CppClass* klass = g_api.class_from_type(type);
    if (klass == nullptr) return {};
    unsigned char slot[32] = {0};
    g_api.field_get_value(obj, field, slot);
    std::string name = enum_constant_name(klass, *reinterpret_cast<int32_t*>(slot));
    if (name == "None" || name == "0") return {};
    return name;
}

// An enum-valued getter's own name, used to name a pet whose localized name
// has not loaded: MinionModel.get_Type is the species ("Weevil").
std::string enum_text(Il2CppObject* self, const char* getter) {
    Il2CppObject* boxed = call_object(self, getter, 0, nullptr);
    if (boxed == nullptr) return {};
    std::string text = clean_native(call_string(boxed, "ToString"));
    if (text.empty() || text.size() > 32 || text == "0" || text == "None") return {};
    return text;
}

// The untagged ability text: localize_static cleans it, which loses the line
// breaks the ability sentences are rebuilt from.
std::string localize_static_raw(Il2CppClass* klass, const char* name, Il2CppObject* model) {
    if (klass == nullptr || model == nullptr) return {};
    {
        void* params[1] = {model};
        if (const MethodInfo* m = method_on(klass, name, 1)) {
            if (Il2CppObject* r = invoke(m, nullptr, params, 1)) {
                std::string s = to_utf8(reinterpret_cast<Il2CppString*>(r));
                if (!s.empty()) return s;
            }
        }
    }
    {
        uint8_t no = 0;
        void* params[2] = {model, &no};
        if (const MethodInfo* m = method_on(klass, name, 2)) {
            if (Il2CppObject* r = invoke(m, nullptr, params, 2)) {
                std::string s = to_utf8(reinterpret_cast<Il2CppString*>(r));
                if (!s.empty()) return s;
            }
        }
    }
    return {};
}

std::string pet_detail_for_minion(Il2CppObject* minion) {
    if (g_cls.minion_model == nullptr) return {};
    std::string name = localize_static(g_cls.minion_extensions, "GetNameLocalized", minion);
    // Localization can still be loading when a card is first shown; the species
    // enum says the same name in the build's own language, so the card is
    // readable instead of missing from the tree entirely. Four sources are
    // tried because no single one survives every build: the localized name,
    // the species property, the species field, and the model's template.
    std::string by_type, by_enum, by_template;
    if (name.empty()) name = by_type = enum_text(minion, "get_Type");
    if (name.empty()) name = by_enum = enum_field_text(minion, "Enum");
    if (name.empty()) {
        if (Il2CppObject* templ = call_object(minion, "get_Template", 0, nullptr)) {
            by_template = enum_text(templ, "get_Type");
            if (by_template.empty()) {
                by_template = localize_static(g_cls.minion_extensions, "GetNameLocalized", templ);
            }
            name = by_template;
        }
    }
    static int name_logs = 0;
    if (name_logs < 3 && (!name.empty() || name_logs == 0)) {
        ++name_logs;
        native_log("pet name: localized='%s' type='%s' enum='%s' template='%s' -> '%s'",
                   localize_static(g_cls.minion_extensions, "GetNameLocalized", minion).c_str(),
                   by_type.c_str(), by_enum.c_str(), by_template.c_str(), name.c_str());
    }
    if (name.empty()) return {};
    std::string out = name;
    int level = model_int(minion, "Level", 1, 9);
    if (level >= 1) out += ", level " + std::to_string(level);
    int attack = stat_total(minion, "Attack");
    int health = stat_total(minion, "Health");
    if (attack >= 0 && health >= 0) {
        char buf[64];
        snprintf(buf, sizeof(buf), ", %d attack, %d health", attack, health);
        out += buf;
    }
    int tier = call_int_neg(minion, "get_Tier");
    if (tier >= 1 && tier <= 9) out += ", tier " + std::to_string(tier);
    std::string tribe = tribe_of(minion);
    if (!tribe.empty()) out += ", " + tribe;
    std::string ability = ability_sentences(
        localize_static_raw(g_cls.minion_extensions, "GetAbilityLocalized", minion));
    if (!ability.empty()) {
        out += ". ";
        out += ability;
    }
    static bool logged = false;
    if (!logged) {
        logged = true;
        native_log("pet detail: %s", out.c_str());
    }
    return out;
}

// A child object behind a property getter or a field, whichever the game
// build kept: HangarMain.MinionShop, HangarOverlay.Roll and the like.
Il2CppObject* prop_or_field(Il2CppObject* self, const char* name) {
    if (self == nullptr) return nullptr;
    if (Il2CppObject* r = call_object(self, (std::string("get_") + name).c_str(), 0, nullptr)) {
        return r;
    }
    if (FieldInfo* field = find_field(g_api.object_get_class(self), name)) {
        Il2CppObject* value = nullptr;
        g_api.field_get_value(self, field, &value);
        return value;
    }
    return nullptr;
}

// The live shop controller, the PC mod's GameplayPhaseDetector.GetHangarMain.
Il2CppObject* hangar_main_instance() {
    if (g_cls.hangar_main == nullptr) return nullptr;
    for (Il2CppObject* hangar : collect(g_cls.hangar_main)) {
        if (hangar != nullptr && object_active(get_game_object(hangar))) return hangar;
    }
    return nullptr;
}

// Transform ids of every Space in a shop/army holder (MinionShop, SpellShop,
// MinionArmy): holder -> Spaces -> each Space's transform. Spaces may be an
// array or a list-like with Items/Count/get_Item; every shape is tried.
void collect_space_transforms(Il2CppObject* holder, std::vector<int32_t>& out,
                              std::vector<Il2CppObject*>* objects = nullptr) {
    if (holder == nullptr) return;
    Il2CppObject* spaces = prop_or_field(holder, "Spaces");
    if (spaces == nullptr) return;
    Il2CppClass* klass = g_api.object_get_class(spaces);
    // Direct array of Space.
    if (g_api.class_get_name != nullptr && g_api.class_get_namespace != nullptr) {
        // Heuristic: arrays expose get_Length; try it before list shapes.
        Il2CppObject* len_obj = call_object(spaces, "get_Length", 0, nullptr);
        if (len_obj == nullptr) len_obj = call_object(spaces, "get_Count", 0, nullptr);
        int count = -1;
        if (len_obj != nullptr) {
            if (auto* v = static_cast<int32_t*>(g_api.object_unbox(len_obj))) count = *v;
        }
        if (count >= 0 && count <= 64) {
            for (int i = 0; i < count; ++i) {
                void* params[1] = {&i};
                Il2CppObject* space = call_object(spaces, "get_Item", 1, params);
                if (space == nullptr) space = call_object(spaces, "Get", 1, params);
                if (space == nullptr) continue;
                Il2CppObject* t = get_transform(space);
                if (t == nullptr) continue;
                int32_t id = instance_id(t);
                if (id != 0) out.push_back(id);
                if (objects != nullptr) objects->push_back(space);
            }
            return;
        }
    }
    // Grid<Space> shape: Spaces.Items is the list.
    if (Il2CppObject* items = prop_or_field(spaces, "Items")) {
        Il2CppObject* len_obj = call_object(items, "get_Count", 0, nullptr);
        if (len_obj == nullptr) len_obj = call_object(items, "get_Length", 0, nullptr);
        int count = -1;
        if (len_obj != nullptr) {
            if (auto* v = static_cast<int32_t*>(g_api.object_unbox(len_obj))) count = *v;
        }
        if (count >= 0 && count <= 64) {
            for (int i = 0; i < count; ++i) {
                void* params[1] = {&i};
                Il2CppObject* space = call_object(items, "get_Item", 1, params);
                if (space == nullptr) continue;
                Il2CppObject* t = get_transform(space);
                if (t == nullptr) continue;
                int32_t id = instance_id(t);
                if (id != 0) out.push_back(id);
                if (objects != nullptr) objects->push_back(space);
            }
        }
    }
    (void)klass;
}

struct ShopZones {
    std::vector<int32_t> pet_shop;
    std::vector<int32_t> food_shop;
    std::vector<int32_t> team;
    // The Space components behind those transforms. A scene walk cannot be
    // trusted to reach them (the shop slots were invisible to it: probe read
    // shopPets=3 while the tree held no card at all), so the shop's own Spaces
    // list is the source of truth for what is on the screen.
    std::vector<Il2CppObject*> pet_slots;
    std::vector<Il2CppObject*> food_slots;
    std::vector<Il2CppObject*> team_slots;
    // Component instance ids of the action buttons (Roll/Freeze/Sell/Done).
    std::vector<int32_t> action_components;
    bool ready = false;
};

// Unwrap a button holder (Roll/Freeze wrappers expose Button) to the clickable
// component, recording both holder and button ids so taps still match.
void record_action_button(Il2CppObject* holder, ShopZones& zones) {
    if (holder == nullptr) return;
    int32_t holder_id = instance_id(holder);
    if (holder_id != 0) zones.action_components.push_back(holder_id);
    Il2CppObject* button = prop_or_field(holder, "Button");
    if (button != nullptr) {
        int32_t button_id = instance_id(button);
        if (button_id != 0) zones.action_components.push_back(button_id);
    }
}

ShopZones shop_zones(Il2CppObject* hangar) {
    ShopZones zones;
    if (hangar == nullptr) return zones;
    collect_space_transforms(prop_or_field(hangar, "MinionShop"), zones.pet_shop, &zones.pet_slots);
    collect_space_transforms(prop_or_field(hangar, "SpellShop"), zones.food_shop, &zones.food_slots);
    collect_space_transforms(prop_or_field(hangar, "MinionArmy"), zones.team, &zones.team_slots);
    if (Il2CppObject* overlay = prop_or_field(hangar, "Overlay")) {
        record_action_button(prop_or_field(overlay, "Roll"), zones);
        record_action_button(prop_or_field(overlay, "Freeze"), zones);
        Il2CppObject* sell = prop_or_field(overlay, "SellButton");
        if (sell == nullptr) sell = prop_or_field(overlay, "Sell");
        if (sell != nullptr) {
            int32_t id = instance_id(sell);
            if (id != 0) zones.action_components.push_back(id);
        }
        Il2CppObject* done = prop_or_field(overlay, "DoneButton");
        if (done == nullptr) done = prop_or_field(overlay, "Done");
        if (done != nullptr) {
            int32_t id = instance_id(done);
            if (id != 0) zones.action_components.push_back(id);
        }
    }
    zones.ready = true;
    return zones;
}

bool in_zone(Il2CppObject* transform, const std::vector<int32_t>& roots) {
    for (int32_t root : roots) {
        if (is_descendant_of(transform, root)) return true;
    }
    return false;
}

void tag_zone(Node& node, Il2CppObject* transform, int32_t component_id, const ShopZones& zones) {
    if (!zones.ready) return;
    bool is_action = std::find(zones.action_components.begin(),
                               zones.action_components.end(),
                               component_id) != zones.action_components.end();
    if (is_action) {
        node.zone = "actions";
    } else if (in_zone(transform, zones.pet_shop)) {
        node.zone = "petshop";
    } else if (in_zone(transform, zones.food_shop)) {
        node.zone = "foodshop";
    } else if (in_zone(transform, zones.team)) {
        node.zone = "team";
    }
}

bool inside_any(Il2CppObject* transform, const std::vector<int32_t>& roots) {
    if (transform == nullptr) return false;
    int32_t self = instance_id(transform);
    for (int32_t root : roots) {
        if (root != 0 && (self == root || is_descendant_of(transform, root))) return true;
    }
    return false;
}

struct ShopState {
    std::string phase = "menu";
    int gold = -1;
    int lives = -1;
    int lives_max = -1;
    int turn = -1;
    int tier = -1;
    int wins = -1;
    int losses = -1;
    int free_rolls = 0;
    int moustaches = -1;
};

// Read-only mirror of the PC mod's GameplayPhaseDetector: an active
// HangarOverlay holding a board means the shop, an active BattleController a
// battle, otherwise the menus.
ShopState shop_state() {
    ShopState state;
    if (g_cls.hangar_overlay != nullptr) {
        for (Il2CppObject* overlay : collect(g_cls.hangar_overlay)) {
            if (overlay == nullptr || !object_active(get_game_object(overlay))) continue;
            Il2CppObject* board = call_object(overlay, "get_BoardModel", 0, nullptr);
            if (board == nullptr) continue;
            state.phase = "shop";
            state.gold = call_int_neg(board, "get_Gold");
            state.lives = call_int_neg(board, "get_Lives");
            state.lives_max = call_int_neg(board, "get_LivesMax");
            state.turn = call_int_neg(board, "get_Turn");
            state.tier = call_int_neg(board, "get_Tier");
            state.wins = model_int(board, "Victories", 0, 99);
            state.losses = model_int(board, "Losses", 0, 99);
            state.free_rolls = model_int(board, "FreeRolls", 0, 99);
            if (state.free_rolls < 0) state.free_rolls = 0;
            state.moustaches = model_int(board, "MoustachesCollected", 0, 99999);
            return state;
        }
    }
    if (g_cls.battle_controller != nullptr) {
        for (Il2CppObject* controller : collect(g_cls.battle_controller)) {
            if (controller != nullptr && object_active(get_game_object(controller))) {
                state.phase = "battle";
                break;
            }
        }
    }
    return state;
}

void battle_teams(std::string& player, std::string& enemy);
void diag_class(const char* ns, const char* name);
void probe_shop_api();
void scoreboard_json_str(std::string& out);

// ---------------------------------------------------------------------------
// Scene-swap quiet window
// ---------------------------------------------------------------------------
// Every recovered fault in the reports lands in the control sweep on the frame
// right after "Loading menu...", i.e. while Unity is tearing the old page down
// and building the next one. The scan is a cross-thread read of exactly the
// objects being replaced, so no single call is to blame: there is no safe way
// to walk a half-swapped hierarchy. The text sweep never faulted in that
// state, so it is used as the detector: when the screen is nothing but a
// loading/connecting caption, the scan is held until the game has finished
// swapping instead of being torn down mid-swap five times a second. Holding a
// scan also cannot leak an engine lock, which longjmp-ing out of a half-run
// control sweep can.
static long now_ms() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<long>(ts.tv_sec) * 1000L + ts.tv_nsec / 1000000L;
}
static long g_quiet_until_ms = 0;
static int g_quiet_skips = 0;
// Start of the current uninterrupted hold, and when the loading screen was
// last looked for. The hold is capped: a caption that never goes away must not
// leave TalkBack frozen on the previous screen, which is what made a page
// transition look like the mod "not hooking on" to the new state.
static long g_quiet_since_ms = 0;
static long g_last_load_check_ms = 0;
static const long kQuietHoldMaxMs = 6000;
// Scene-swap gate. A scene being added is the one state where the scan itself
// hangs the game: the loading thread holds engine locks, the Android UI thread
// blocks inside the engine call it is making, input stops being delivered and
// Android kills the app for not responding. SceneManager.get_sceneCount is a
// static read with no object walk, so it can be asked during the load.
static int g_stable_scene_count = -1;
static long g_swap_since_ms = 0;
static int g_swap_skips = 0;
// A mode that keeps an extra scene loaded forever must not freeze the reader:
// after this long the scan goes ahead and adopts the new count as normal.
static const long kSwapHoldMaxMs = 15000;

static bool loading_caption(const std::string& value) {
    static const char* kCaptions[] = {"loading", "connecting", "pinging",
                                      "logging in", "waiting for consent",
                                      "please wait"};
    for (const char* caption : kCaptions) {
        if (value.find(caption) != std::string::npos) return true;
    }
    return false;
}

bool loading_screen_visible() {
    for (Il2CppClass* text_class : {g_cls.tmp_text, g_cls.ui_text}) {
        for (Il2CppObject* text : collect(text_class)) {
            if (text == nullptr) continue;
            if (!object_active(get_game_object(text))) continue;
            std::string value = clean_native(call_string(text, "get_text"));
            for (char& c : value) {
                if (c >= 'A' && c <= 'Z') c = static_cast<char>(c + ('a' - 'A'));
            }
            if (loading_caption(value)) return true;
        }
    }
    return false;
}

std::string snapshot_json() {
    if (!g_ready) return "{\"ready\":false}";
    // A fault was just recovered from: let the game finish whatever it was
    // doing before walking the object graph again (one poll, not a disable).
    if (g_skip_snapshots > 0) {
        --g_skip_snapshots;
        return "{\"ready\":false}";
    }
    // Any wild pointer Unity hands us mid scene-load lands here instead of
    // killing the game; the Java poll just retries next tick.
    if (sigsetjmp(g_snap_jmp, 1)) {
        g_in_snapshot = false;
        return "{\"ready\":false}";
    }
    SnapGuard guard;
    // The bridge is only safe when driven from the thread that bootstrapped it
    // (Unity's main thread). Record a mismatch once rather than guess.
    if (g_main_thread_tid == 0) {
        g_main_thread_tid = current_tid();
    } else {
        static bool warned = false;
        long tid = current_tid();
        if (!warned && tid != g_main_thread_tid) {
            warned = true;
            native_log("snapshot: off the bootstrapping thread (tid %ld, bootstrap %ld)",
                       tid, g_main_thread_tid);
        }
    }

    // Hold the scan while the game swaps a page (see the quiet window note).
    const long now = now_ms();
    if (g_quiet_until_ms > now) {
        if (++g_quiet_skips % 10 == 1) {
            native_log("quiet: scan held %ld ms more (page still loading)",
                       g_quiet_until_ms - now);
        }
        return "{\"ready\":false}";
    }
    g_quiet_skips = 0;
    if (g_cls.scene_manager != nullptr) {
        int32_t scenes = call_int_static(g_cls.scene_manager, "get_sceneCount");
        if (scenes > 0 && g_stable_scene_count < 0) g_stable_scene_count = scenes;
        if (scenes > g_stable_scene_count) {
            if (g_swap_since_ms == 0) g_swap_since_ms = now;
            if (now - g_swap_since_ms < kSwapHoldMaxMs) {
                if (++g_swap_skips % 10 == 1) {
                    native_log("swap: %d scenes (stable %d), scan held %ld ms", scenes,
                               g_stable_scene_count, now - g_swap_since_ms);
                }
                return "{\"ready\":false}";
            }
            native_log("swap: %d scenes for %ld ms, scanning and accepting it", scenes,
                       now - g_swap_since_ms);
            g_stable_scene_count = scenes;
            g_swap_since_ms = 0;
        } else if (scenes > 0) {
            g_stable_scene_count = scenes;
            g_swap_since_ms = 0;
        }
    }
    // The extra text sweep is cheap but not free, so it runs every poll while
    // the previous tree was sparse (boot) and once a second otherwise: a page
    // swap after a busy screen must still be caught, and waiting for the tree
    // to shrink first would walk the hierarchy mid-swap, which is the fault.
    if (g_last_nodes <= 3 || now - g_last_load_check_ms >= 1000) {
        g_last_load_check_ms = now;
        if (loading_screen_visible()) {
            if (g_quiet_since_ms == 0) g_quiet_since_ms = now;
            if (now - g_quiet_since_ms < kQuietHoldMaxMs) {
                g_quiet_until_ms = now + 1500;
                native_log("quiet: loading screen, holding the scan for 1.5 s");
                return "{\"ready\":false}";
            }
            // Held long enough already: publish the tree instead, so a caption
            // that stays on screen cannot freeze the reader.
            native_log("quiet: loading caption still up after %ld ms, scanning anyway",
                       now - g_quiet_since_ms);
        } else {
            g_quiet_since_ms = 0;
        }
    }

    g_stat_find_empty = 0;
    g_stat_find_objects = 0;
    g_stat_scene_count = 0;
    g_stat_scene_roots = 0;
    g_stat_scene_objects = 0;
    g_stat_proj_overlay = 0;
    g_stat_proj_canvas = 0;
    g_stat_proj_main = 0;
    g_stat_proj_none = 0;
    g_stat_unlabeled = 0;

    static int snapshot_count = 0;
    ++snapshot_count;
    bool log_this = snapshot_count < 10 || snapshot_count % 50 == 0;
    if (log_this) native_log("snapshot %d: scanning", snapshot_count);

    // Each stage names itself, so a fault line says what the scanner was
    // doing ("phase=shop-zones") without needing a symbolised tombstone.
    g_scan_phase = 1;
    const auto consent_labels = consent_button_labels();
    g_scan_phase = 2;
    const ShopState shop = shop_state();
    std::string battle_player, battle_enemy;
    g_scan_phase = 3;
    if (shop.phase == "battle") battle_teams(battle_player, battle_enemy);
    std::string sb_json = "{\"open\":false,\"opponents\":[]}";
    g_scan_phase = 4;
    if (shop.phase == "shop" || shop.phase == "battle") scoreboard_json_str(sb_json);
    ShopZones zones;
    g_scan_phase = 5;
    if (shop.phase == "shop") {
        zones = shop_zones(hangar_main_instance());
        g_scan_phase = 6;
        probe_shop_api();
    }
    g_scan_phase = 7;
    std::vector<Node> nodes;
    std::vector<int32_t> control_transforms;
    std::vector<int32_t> seen_objects;
    int found_controls = 0;
    int found_texts = 0;
    // Per-sweep publication counts. A shop screen whose cards never appear can
    // then be pinned to the sweep that missed them (controls / spaces / views)
    // instead of guessing.
    int published_controls = 0;
    int published_spaces = 0;
    int published_views = 0;

    for (Il2CppClass* control_class : {g_cls.selectable, g_cls.selectable_base, g_cls.button_base}) {
        for (Il2CppObject* component : collect(control_class)) {
            if (component == nullptr) continue;
            ++found_controls;
            Il2CppObject* go = get_game_object(component);
            if (!object_active(go)) continue;
            // The same GameObject can carry more than one of these, and a
            // SelectableBase may itself be a Selectable, so read it once.
            int32_t object_id = instance_id(go);
            if (object_id != 0 && std::find(seen_objects.begin(), seen_objects.end(), object_id) != seen_objects.end()) {
                continue;
            }
            if (object_id != 0) seen_objects.push_back(object_id);
            if (g_cls.behaviour != nullptr && is_a(g_cls.behaviour, component) && !call_bool(component, "get_enabled")) continue;

            Il2CppObject* transform = get_transform(component);
            if (transform == nullptr) continue;
            // The class sweeps overlap (a Button is both a Selectable and a
            // ButtonBase), so the same GameObject comes back twice with the
            // same text and rect. TalkBack then reads everything doubled.
            // Transforms are one per object, so an already-emitted transform
            // is always this same object, never a nested control.
            int32_t self_tid = instance_id(transform);
            if (self_tid != 0 &&
                std::find(control_transforms.begin(), control_transforms.end(),
                          self_tid) != control_transforms.end()) {
                continue;
            }

            Node node;
            node.id = instance_id(component);
            node.role = role_of(component);
            node.label = label_from_fields(component);
            if (node.label.empty()) {
                for (const auto& entry : consent_labels) {
                    if (entry.first == node.id) {
                        node.label = entry.second;
                        break;
                    }
                }
            }
            if (node.label.empty()) node.label = join_text(transform);
            if (!control_visible(component, node.label)) continue;
            if (node.label.empty()) node.label = pretty_object_name(go);
            if (node.label.empty() && node.value.empty() && node.detail.empty()
                && node.role != "input") {
                // Nothing to read means nothing to offer. An unlabelled icon
                // button is also a live trap for a screen reader: the first
                // focusable control on the main menu was the blank 85x85 store
                // icon, so TalkBack focused it and the first double-tap opened
                // the purchase page unasked. Dropping it leaves the labelled
                // controls to be reached in order.
                ++g_stat_unlabeled;
                continue;
            }
            control_transforms.push_back(instance_id(transform));
            node.value = value_of(component, node.role);
            node.detail = pet_detail_guarded(component);
            item_facts_guarded(component, node);
            tag_zone(node, transform, node.id, zones);
            node.enabled = interactable_of(component);
            node.checked = node.role == "toggle" && get_checked(component);
            node_rect(component, transform, &node.x, &node.y, &node.w, &node.h);
            // Keep a control even without an instance id; it is still worth
            // reading, and the synthetic id cannot be tapped.
            if (node.id == 0) node.id = -(static_cast<int>(nodes.size()) + 1);
            nodes.push_back(node);
            ++published_controls;
        }
    }

    // Board slots with no Selectable of their own still get a node: an empty
    // shop slot is a place to put a pet, and a battle pet is worth reading.
    // Taps on these go through the game's own shop/team click entry points.
    g_scan_phase = 8;
    if (g_cls.space != nullptr) {
        for (Il2CppObject* space : collect(g_cls.space)) {
            if (space == nullptr) continue;
            Il2CppObject* go = get_game_object(space);
            if (!object_active(go)) continue;
            int32_t object_id = instance_id(go);
            if (object_id != 0 && std::find(seen_objects.begin(), seen_objects.end(),
                                            object_id) != seen_objects.end()) {
                continue;  // a real control on this slot already speaks for it
            }
            Il2CppObject* transform = get_transform(space);
            if (transform == nullptr || inside_any(transform, control_transforms)) continue;
            Node node;
            node.id = instance_id(space);
            node.role = "button";
            node.detail = pet_detail_guarded(space);
            item_facts_guarded(space, node);
            tag_zone(node, transform, node.id, zones);
            node_rect(space, transform, &node.x, &node.y, &node.w, &node.h);
            // A slot this pass cannot measure is left to the zone pass below,
            // which knows how to measure it from the pet's card. Publishing it
            // here first put nine unprojectable nodes in the tree and won the
            // de-duplication against the good copy. The rect has to be read
            // *before* this test: it used to be read after, so the check saw the
            // zero-initialised fields and skipped every space unconditionally.
            if (node.x <= -1000 || node.w <= 1 || node.h <= 1) continue;
            if (!node.detail.empty()) {
                node.label = "";  // the detail line already starts with the name
            } else if (node.zone == "petshop") {
                node.label = "Empty pet shop slot";
            } else if (node.zone == "foodshop") {
                node.label = "Empty food shop slot";
            } else if (node.zone == "team") {
                node.label = "Empty team slot";
            } else {
                continue;
            }
            node.enabled = true;
            if (node.id == 0) node.id = -(static_cast<int>(nodes.size()) + 1);
            control_transforms.push_back(instance_id(transform));
            if (object_id != 0) seen_objects.push_back(object_id);
            nodes.push_back(node);
            ++published_spaces;
        }
    }
    // The shop's own Space lists, published directly. Every pet in the shop and
    // every pet on the team IS a slot in one of these three lists, so this pass
    // is what puts the shop on screen: the scene walk above kept missing the
    // cards while the probe read shopPets=3 (three slot objects) and the tree
    // held only the scoreboard and the tabs. Slots are deduplicated by
    // component id, so a slot the walk already published is not read twice.
    static int shop_slot_logs = 0;
    int slot_logs_left = shop.phase == "shop" && shop_slot_logs < 3 ? 12 : 0;
    if (shop.phase == "shop" && shop_slot_logs < 3) ++shop_slot_logs;
    int bad_slot_rects = 0;
    auto slot_rect_valid = [](const Node& n) {
        return n.w > 1 && n.h > 1 && n.x > -1000 && n.y > -1000;
    };
    auto publish_slot_list = [&](const std::vector<Il2CppObject*>& slots, const char* zone,
                                 const char* empty_label) {
        for (Il2CppObject* space : slots) {
            if (space == nullptr) continue;
            Il2CppObject* go = get_game_object(space);
            if (!object_active(go)) continue;
            Il2CppObject* transform = get_transform(space);
            if (transform == nullptr) continue;
            int32_t id = instance_id(space);
            // Look for an earlier copy of this slot, but do not trust it: the
            // generic space walk publishes the same Space components, and when
            // it cannot measure one it still publishes a node at -10000. A copy
            // like that must be replaced by this measured one, never kept: a
            // second node with the same id makes TalkBack read the slot twice
            // and sends taps to the dead copy.
            int existing_index = -1;
            if (id != 0) {
                for (size_t i = 0; i < nodes.size(); ++i) {
                    if (nodes[i].id == id) {
                        existing_index = static_cast<int>(i);
                        break;
                    }
                }
            }
            Node node;
            node.id = id;
            node.role = "button";
            node.detail = pet_detail_guarded(space);
            item_facts_guarded(space, node);
            if (node.detail.empty()) node.label = empty_label;
            node.zone = zone;
            node.enabled = true;
            node_rect(space, transform, &node.x, &node.y, &node.w, &node.h);
            const float raw_x = node.x, raw_y = node.y, raw_w = node.w, raw_h = node.h;
            if (!slot_rect_valid(node)) {
                // The slot component itself often sits on a plain Transform, so
                // node_rect gives it no rect at all (x,y = -10000). Every shop
                // card and board pet was published like that and then binned by
                // the controller's on-screen filter: ten controls in the tree on
                // a screen full of pets. The rect has to come from a child that
                // is real UI — the pet's MinionView when the slot is occupied,
                // the empty slot's ItemContainer when it is not — and the child
                // must be the component passed in, because the canvas is looked
                // up from it.
                for (const char* field : {"MinionView", "ItemContainer", "PreviewContainer"}) {
                    Il2CppObject* child = prop_or_field(space, field);
                    if (child == nullptr) continue;
                    Il2CppObject* child_transform = get_transform(child);
                    if (child_transform == nullptr || child_transform == transform) continue;
                    node_rect(child, child_transform, &node.x, &node.y, &node.w, &node.h);
                    if (slot_rect_valid(node)) break;
                }
            }
            if (!slot_rect_valid(node)) ++bad_slot_rects;
            if (slot_logs_left > 0) {
                native_log("slot %s: id=%d raw=%.0f,%.0f %.0fx%.0f final=%.0f,%.0f %.0fx%.0f detail='%s'",
                           zone, id, raw_x, raw_y, raw_w, raw_h, node.x, node.y, node.w,
                           node.h, node.detail.c_str());
            }
            if (existing_index >= 0) {
                Node& existing = nodes[existing_index];
                if (slot_rect_valid(existing) && !existing.detail.empty()) {
                    if (slot_logs_left > 0) {
                        native_log("slot %s: id=%d kept the measured copy at %.0f,%.0f", zone,
                                   id, existing.x, existing.y);
                    }
                    continue;
                }
                existing = node;
                ++published_spaces;
                if (slot_logs_left > 0) {
                    native_log("slot %s: id=%d replaced the unmeasured copy (was %.0f,%.0f)",
                               zone, id, raw_x, raw_y);
                }
                continue;
            }
            if (node.id == 0) node.id = -(static_cast<int>(nodes.size()) + 1);
            int32_t object_id = instance_id(go);
            if (object_id != 0) seen_objects.push_back(object_id);
            nodes.push_back(node);
            ++published_spaces;
        }
    };
    g_scan_phase = 81;
    publish_slot_list(zones.pet_slots, "petshop", "Pet shop slot");
    g_scan_phase = 82;
    publish_slot_list(zones.food_slots, "foodshop", "Food shop slot");
    g_scan_phase = 83;
    publish_slot_list(zones.team_slots, "team", "Team slot");
    {
        // One line when the shop's slot count changes: the next report then
        // says whether the game exposed three shop pets and five team slots.
        static size_t last_slot_sig = static_cast<size_t>(-1);
        size_t sig = zones.pet_slots.size() * 100 + zones.food_slots.size() * 10
                     + zones.team_slots.size();
        if (sig != last_slot_sig && shop.phase == "shop") {
            last_slot_sig = sig;
            native_log("slots: pets=%zu food=%zu team=%zu badRects=%d", zones.pet_slots.size(),
                       zones.food_slots.size(), zones.team_slots.size(), bad_slot_rects);
        } else if (bad_slot_rects > 0) {
            static int bad_logged = 0;
            if (bad_logged++ % 50 == 0) {
                native_log("slots: still %d slots without a usable rect", bad_slot_rects);
            }
        }
    }

    g_scan_phase = 9;
    if (g_cls.minion_view != nullptr) {
        for (Il2CppObject* view : collect(g_cls.minion_view)) {
            if (view == nullptr) continue;
            Il2CppObject* go = get_game_object(view);
            if (!object_active(go)) continue;
            Il2CppObject* transform = get_transform(view);
            if (transform == nullptr || inside_any(transform, control_transforms)) continue;
            std::string detail = pet_detail_guarded(view);
            std::string label = join_text(transform);
            if (detail.empty() && label.empty()) continue;
            // Same rule as the space sweep: a pet this view cannot measure is
            // left to the zone pass, which has the card-sized fallback.
            float probe_x = 0, probe_y = 0, probe_w = 0, probe_h = 0;
            node_rect(view, transform, &probe_x, &probe_y, &probe_w, &probe_h);
            if (probe_x <= -1000 || probe_w <= 1 || probe_h <= 1) continue;
            Node node;
            node.id = instance_id(view);
            node.role = "button";
            node.detail = detail;
            item_facts_guarded(view, node);
            node.label = detail.empty() ? label : "";
            tag_zone(node, transform, node.id, zones);
            node.enabled = true;
            node_rect(view, transform, &node.x, &node.y, &node.w, &node.h);
            if (node.id == 0) node.id = -(static_cast<int>(nodes.size()) + 1);
            control_transforms.push_back(instance_id(transform));
            nodes.push_back(node);
            ++published_views;
        }
    }

    // Static text outside controls, so page and dialog bodies are readable.
    g_scan_phase = 10;
    for (Il2CppClass* text_class : {g_cls.tmp_text, g_cls.ui_text}) {
        for (Il2CppObject* text : collect(text_class)) {
            if (text == nullptr) continue;
            ++found_texts;
            Il2CppObject* go = get_game_object(text);
            if (!object_active(go)) continue;
            Il2CppObject* transform = get_transform(text);
            if (transform == nullptr) continue;
            int32_t transform_id = instance_id(transform);
            bool inside_control = false;
            for (int32_t ct : control_transforms) {
                if (ct == transform_id || is_descendant_of(transform, ct)) {
                    inside_control = true;
                    break;
                }
            }
            if (inside_control) continue;
            std::string value = clean_native(call_string(text, "get_text"));
            if (value.empty()) continue;
            Node node;
            node.id = instance_id(text);
            node.role = "text";
            node.label = value;
            node.enabled = true;
            node.focusable = false;
            node_rect(text, transform, &node.x, &node.y, &node.w, &node.h);
            if (node.id == 0) node.id = -(static_cast<int>(nodes.size()) + 1);
            nodes.push_back(node);
        }
    }

    // Layout: UGUI positions/sizes are only valid after the layout pass, and a
    // control with no size cannot be tapped or focused, so drop the sizeless
    // ones instead of vetoing the whole screen. Vetoing blanked the tree on
    // live screens that merely contain a few hidden 0x0 controls (the build
    // screen did this), which froze TalkBack on a stale tree and made every
    // double-tap miss its dead id. Only a screen where *every* control is
    // sizeless is real boot (all stacked at one point), and that is held.
    // Never publish a node that cannot be placed on screen. TalkBack would
    // focus it, the user would double-tap it, and the tap would go wherever the
    // last usable coordinate happened to be — which is how a shop turn ended up
    // opening the purchase screen.
    nodes.erase(std::remove_if(nodes.begin(), nodes.end(),
                               [](const Node& n) {
                                   return n.x <= -5000 || n.y <= -5000;
                               }),
                nodes.end());
    nodes.erase(std::remove_if(nodes.begin(), nodes.end(),
                               [](const Node& n) {
                                   // Shop, team and food slots keep their node
                                   // even without a measurable rect: the card
                                   // is there, and dropping it is what left the
                                   // shop empty.
                                   return n.role != "text" && n.w <= 1 && n.h <= 1
                                          && n.zone.empty();
                               }),
                nodes.end());
    // Nothing laid out at all means Unity has not run its first layout pass:
    // stay not-ready so the Java side keeps waiting instead of announcing an
    // empty screen. Any screen with at least one real node is published, even
    // when most of its controls are hidden 0x0 leftovers.
    if (nodes.empty()) {
        static int pending = 0;
        if (++pending <= 3 || pending % 50 == 0) native_log("boot: nothing laid out yet");
        return "{\"ready\":false}";
    }

    // Reading order: shop zones in PC-mod order (pets, food, team, actions),
    // then anything else top-to-bottom, left-to-right, so a TalkBack swipe
    // walks the shop the way the PC mod's S/F/T/D keys do.
    //
    // The key lives in order.h and is tested on the host (tests/native). The
    // comparator this replaced compared y only when it differed by more than
    // 8 px and x otherwise, which is not transitive, and std::sort with an
    // invalid comparator is undefined behaviour: the same screen could come
    // back in a different order on the next poll.
    std::sort(nodes.begin(), nodes.end(), [](const Node& a, const Node& b) {
        return sapa11y_order::before(a.zone.c_str(), a.y, a.x, a.id,
                                     b.zone.c_str(), b.y, b.x, b.id);
    });

    std::string title;
    if (g_cls.page_manager != nullptr) {
        std::vector<Il2CppObject*> managers = collect(g_cls.page_manager);
        if (!managers.empty()) {
            Il2CppObject* manager = managers[0];
            // PageManager.CurrentPage is a field (dump.cs:620849), not a
            // property, so the get_CurrentPage / get_currentPage lookups this
            // used to try never resolved and every screen title came out
            // empty. prop_or_field reads the field first.
            Il2CppObject* page = prop_or_field(manager, "CurrentPage");
            if (page != nullptr) {
                Il2CppObject* page_go = get_game_object(page);
                if (page_go != nullptr) title = call_string(page_go, "get_name");
            }
        }
    }

    g_last_nodes = static_cast<int>(nodes.size());
    if (log_this) {
        native_log("snapshot %d: %zu nodes (found %d controls, %d texts; published %d controls, %d spaces, %d views; %d unlabeled dropped), title=%s, sceneManager=%p",
                   snapshot_count, nodes.size(), found_controls, found_texts, published_controls,
                   published_spaces, published_views, g_stat_unlabeled, title.c_str(),
                   static_cast<void*>(g_cls.scene_manager));
        native_log("shop: phase=%s gold=%d lives=%d/%d turn=%d tier=%d wins=%d losses=%d zones=%d/%d/%d actions=%d",
                   shop.phase.c_str(), shop.gold, shop.lives, shop.lives_max,
                   shop.turn, shop.tier, shop.wins, shop.losses,
                   (int)zones.pet_shop.size(), (int)zones.food_shop.size(),
                   (int)zones.team.size(), (int)zones.action_components.size());
        int shown = 0;
        for (const Node& n : nodes) {
            if (shown++ >= 12) break;
            native_log("  node id=%d role=%s label='%s' enabled=%d at %.0f,%.0f size %.0fx%.0f",
                       n.id, n.role.c_str(), n.label.c_str(), n.enabled ? 1 : 0,
                       n.x, n.y, n.w, n.h);
        }
    }

    // NOTE: fixed char buffers here silently truncated every node (~150+ bytes
    // into a 128-byte buf), producing malformed JSON that Java could never
    // parse: bridge "ready" with 0 controls forever. Build with std::string.
    std::string out = "{\"ready\":true,\"title\":\"" + json_escape(title) + "\",\"nodes\":[";
    bool first = true;
    // Proof of what Java actually receives, for the first few shop screens: the
    // exact integers written into the JSON for every zoned node.
    static int slot_json_logs = 0;
    static int unplaced_logs = 0;
    for (const Node& n : nodes) {
        if (!first) out += ',';
        first = false;
        if (!n.zone.empty() && slot_json_logs < 12) {
            ++slot_json_logs;
            native_log("json slot: id=%d zone=%s x=%d y=%d w=%d h=%d label='%s'", n.id,
                       n.zone.c_str(), static_cast<int>(std::round(n.x)),
                       static_cast<int>(std::round(n.y)), static_cast<int>(std::round(n.w)),
                       static_cast<int>(std::round(n.h)), n.label.c_str());
        }
        // Anything still going out unprojectable is named here, with the sweep
        // that made it (role + zone), so the next report says which pass needs
        // fixing instead of the count alone.
        if ((n.x <= -5000 || n.y <= -5000) && unplaced_logs < 12) {
            ++unplaced_logs;
            native_log("unplaced: id=%d role=%s zone=%s w=%.0f h=%.0f label='%s'", n.id,
                       n.role.c_str(), n.zone.c_str(), n.w, n.h, n.label.c_str());
        }
        out += "{\"id\":" + std::to_string(n.id)
            + ",\"role\":\"" + json_escape(n.role)
            + "\",\"label\":\"" + json_escape(n.label)
            + "\",\"value\":\"" + json_escape(n.value)
            + "\",\"detail\":\"" + json_escape(n.detail)
            + "\",\"zone\":\"" + json_escape(n.zone);
        // Only members that actually resolved are published, so Java can tell
        // "the game says 2 gold" from "the bridge could not read a price".
        if (n.price >= 0) out += ",\"price\":" + std::to_string(n.price);
        if (n.frozen >= 0) out += std::string(",\"frozen\":") + (n.frozen != 0 ? "true" : "false");
        if (n.sell >= 0) out += ",\"sell\":" + std::to_string(n.sell);
        out
            + "\",\"enabled\":" + (n.enabled ? "true" : "false")
            + ",\"checked\":" + (n.checked ? "true" : "false")
            + ",\"focusable\":" + (n.focusable ? "true" : "false")
            + ",\"x\":" + std::to_string(static_cast<int>(std::round(n.x)))
            + ",\"y\":" + std::to_string(static_cast<int>(std::round(n.y)))
            + ",\"w\":" + std::to_string(static_cast<int>(std::round(n.w)))
            + ",\"h\":" + std::to_string(static_cast<int>(std::round(n.h))) + "}";
    }
    {
        out += "],\"shop\":{\"phase\":\"" + json_escape(shop.phase)
            + "\",\"gold\":" + std::to_string(shop.gold)
            + ",\"lives\":" + std::to_string(shop.lives)
            + ",\"livesMax\":" + std::to_string(shop.lives_max)
            + ",\"turn\":" + std::to_string(shop.turn)
            + ",\"tier\":" + std::to_string(shop.tier)
            + ",\"wins\":" + std::to_string(shop.wins)
            + ",\"losses\":" + std::to_string(shop.losses)
            + ",\"freeRolls\":" + std::to_string(shop.free_rolls)
            + ",\"moustaches\":" + std::to_string(shop.moustaches) + "}";
        // Battle teams for the narrator diff; empty outside battle.
        out += ",\"battle\":{\"player\":\"" + json_escape(battle_player) +
               "\",\"enemy\":\"" + json_escape(battle_enemy) + "\"},\"scoreboard\":" +
               sb_json + "}";
    }
    g_scan_phase = 0;
    g_skip_snapshots = 0;  // a full pass means the faulting state is gone
    g_quiet_since_ms = 0;  // a published tree resets the hold's budget
    return out;
}

// ---------------------------------------------------------------------------
// Actions
// ---------------------------------------------------------------------------

Il2CppObject* find_component_by_id(int32_t id, Il2CppClass* type) {
    for (Il2CppObject* obj : collect(type)) {
        if (obj != nullptr && instance_id(obj) == id) return obj;
    }
    return nullptr;
}

Il2CppObject* find_control_by_id(int32_t id) {
    for (Il2CppClass* type :
         {g_cls.selectable, g_cls.selectable_base, g_cls.button_base, g_cls.space,
          g_cls.minion_view}) {
        Il2CppObject* found = find_component_by_id(id, type);
        if (found != nullptr) return found;
    }
    return nullptr;
}

// A slot with no clickable control of its own is still playable: drive the
// game's own shop/team click entry point for the slot's zone, the methods the
// PC mod's PerformAction calls.
bool slot_click_component(Il2CppObject* component) {
    if (component == nullptr || g_cls.space == nullptr) return false;
    Il2CppObject* hangar = hangar_main_instance();
    if (hangar == nullptr) return false;
    Il2CppObject* space = get_component_in_parent(component, g_cls.space);
    if (space == nullptr) {
        if (g_api.object_get_class(component) == g_cls.space) space = component;
    }
    if (space == nullptr) return false;
    Il2CppObject* transform = get_transform(space);
    if (transform == nullptr) return false;
    // The slot's own click handler first: it is the game's direct path.
    // Space.HandleClick takes the slot's PointerCapture (dump.cs:632069), so
    // the old zero-argument lookup resolved nothing and this path was dead on
    // every slot; the click only ever worked through the HangarMain entry
    // points below. Space carries the capture in its own PointerCapture field
    // (dump.cs:632043), so pass that and nothing is invented.
    const MethodInfo* click = method_on(g_api.object_get_class(space), "HandleClick", 1);
    if (click != nullptr) {
        Il2CppObject* capture = prop_or_field(space, "PointerCapture");
        if (capture != nullptr) {
            void* args[1] = {capture};
            invoke(click, space, args, 1);
            return true;
        }
    }
    ShopZones zones = shop_zones(hangar);
    const char* holder_name = nullptr;
    const char* method_name = nullptr;
    if (in_zone(transform, zones.pet_shop)) {
        holder_name = "MinionShop";
        method_name = "HandleMinionShopClick";
    } else if (in_zone(transform, zones.food_shop)) {
        holder_name = "SpellShop";
        method_name = "HandleSpellShopClick";
    } else if (in_zone(transform, zones.team)) {
        holder_name = "MinionArmy";
        method_name = "HandleMinionArmyClick";
    } else {
        return false;
    }
    Il2CppObject* holder = prop_or_field(hangar, holder_name);
    if (holder == nullptr) return false;
    const MethodInfo* m = method_on(g_api.object_get_class(hangar), method_name, 2);
    if (m == nullptr) return false;
    void* args[2] = {holder, space};
    invoke(m, hangar, args, 2);
    return true;
}

Il2CppObject* event_system() {
    if (g_cls.event_system == nullptr) return nullptr;
    const MethodInfo* m = method_on(g_cls.event_system, "get_current", 0);
    if (m == nullptr) return nullptr;
    return invoke(m, nullptr, nullptr, 0);
}

Il2CppObject* new_event_data(Il2CppClass* klass) {
    if (klass == nullptr) return nullptr;
    Il2CppObject* es = event_system();
    if (es == nullptr) return nullptr;
    Il2CppObject* data = g_api.object_new(klass);
    if (data == nullptr) return nullptr;
    const MethodInfo* ctor = method_on(klass, ".ctor", 1);
    if (ctor != nullptr) {
        void* params[1] = {es};
        invoke(ctor, data, params, 1);
    }
    return data;
}

bool slot_click_component(Il2CppObject* component);

bool tap_impl(int32_t id) {
    if (!g_ready) return false;
    Il2CppObject* component = find_control_by_id(id);
    if (component == nullptr) {
        // A stale id, a static text, or a control class the sweeps do not
        // cover: nothing to activate either way.
        native_log("tap %d: control gone (stale id?)", id);
        return false;
    }

    if (is_a(g_cls.toggle, component)) {
        bool on = get_checked(component);
        bool next = !on;
        void* params[1] = {&next};
        if (call_void(component, "set_isOn", 1, params)) return true;
    }

    // The difficulty picker and pack options are dropdowns; opening the
    // list is the tap.
    if (is_a(g_cls.dropdown, component)) {
        if (call_void(component, "Show", 0, nullptr)) return true;
    }

    // Every synthetic path below runs only while the game still calls the
    // control interactable. Their own guards return silently otherwise, and
    // reporting that as a successful tap is what stopped the real-touch
    // fallback from ever running (the tap that "worked" and did nothing).
    // A not-interactable control now returns false, so the Java side injects a
    // genuine touch at its rect and the game decides.
    Il2CppObject* button = get_component(component, g_cls.button);
    const bool interactable = interactable_of(component);
    native_log("tap %d: interactable=%d button=%d", id, interactable ? 1 : 0,
               button != nullptr ? 1 : 0);
    Il2CppClass* klass = g_api.object_get_class(component);

    if (interactable && g_cls.pointer_event_data != nullptr) {
        Il2CppObject* data = new_event_data(g_cls.pointer_event_data);
        if (data != nullptr) {
            void* params[1] = {data};
            if (call_void(component, "OnPointerClick", 1, params)) return true;
            if (call_void(component, "OnSubmit", 1, params)) return true;
        }
    }

    // Game controls implement the pointer interfaces explicitly, so look for the
    // mangled handler name, then for the public Click() the PC mod calls.
    if (interactable) {
        for (const char* handler : {"OnPointerClick", "OnSubmit"}) {
            const MethodInfo* m = method_suffix(klass, handler, 1);
            if (m == nullptr || g_cls.pointer_event_data == nullptr) continue;
            Il2CppObject* data = new_event_data(g_cls.pointer_event_data);
            if (data == nullptr) continue;
            void* params[1] = {data};
            invoke(m, component, params, 1);
            return true;
        }
        if (const MethodInfo* m = method_suffix(klass, "Click", 0)) {
            invoke(m, component, nullptr, 0);
            native_log("tap %d: called component Click()", id);
            return true;
        }
    }
    // Button.onClick is deliberately not invoked: a uGUI Button's listener is
    // reached through OnSubmit above, and invoking the listener directly runs
    // game code outside Unity's event flow, which aborted the process on the
    // first button this was tried on. Anything the synthetic paths cannot
    // drive falls through to the real-touch fallback on the Java side.
    // Last resort: a board slot whose clicks the game routes through the
    // shop controller instead of a control on the slot itself.
    if (slot_click_component(component)) return true;
    const char* tname = "?";
    if (g_api.class_get_name != nullptr) {
        const char* n = g_api.class_get_name(g_api.object_get_class(component));
        if (n != nullptr) tname = n;
    }
    native_log("tap %d: no click path on %s", id, tname);
    return false;
}

// A click runs game code, and the page can swap out from under it: a control
// whose page is being torn down used to fault here and take the process down
// (the game froze on the tap, with nothing written to the report). Run every
// click under the same guard the submit fallback uses, so a bad pointer aborts
// this one click instead of the game.
bool tap(int32_t id) {
    if (!g_ready) return false;
    if (sigsetjmp(g_fallback_jmp, 1)) {
        g_in_fallback = false;
        native_log("tap %d: faulted, aborting click", id);
        return false;
    }
    g_in_fallback = true;
    bool ok = tap_impl(id);
    g_in_fallback = false;
    return ok;
}

// Click any component the way tap() does: Click() first (the PC mod calls
// Button.Click()), then pointer handlers, then the Unity onClick event.
bool click_component(Il2CppObject* component) {
    if (component == nullptr) return false;
    Il2CppClass* klass = g_api.object_get_class(component);
    if (const MethodInfo* m = method_suffix(klass, "Click", 0)) {
        invoke(m, component, nullptr, 0);
        return true;
    }
    if (g_cls.pointer_event_data != nullptr) {
        Il2CppObject* data = new_event_data(g_cls.pointer_event_data);
        if (data != nullptr) {
            void* params[1] = {data};
            if (call_void(component, "OnPointerClick", 1, params)) return true;
            if (call_void(component, "OnSubmit", 1, params)) return true;
        }
    }
    Il2CppObject* button = get_component(component, g_cls.button);
    if (button != nullptr) {
        Il2CppObject* click = call_object(button, "get_onClick", 0, nullptr);
        if (click != nullptr && call_void(click, "Invoke", 0, nullptr)) return true;
    }
    return false;
}

// Direct shop actions for the Q/E controller keys: click the game's own
// Roll / Done buttons, the same objects the PC mod's PerformRoll/PerformEndTurn
// click. Read-only otherwise; no game logic is reimplemented here.
bool shop_button_click(const char* holder_name) {
    if (!g_ready) return false;
    Il2CppObject* hangar = hangar_main_instance();
    if (hangar == nullptr) return false;
    Il2CppObject* overlay = prop_or_field(hangar, "Overlay");
    if (overlay == nullptr) return false;
    Il2CppObject* holder = prop_or_field(overlay, holder_name);
    if (holder == nullptr) {
        if (strcmp(holder_name, "DoneButton") == 0) holder = prop_or_field(overlay, "Done");
        if (holder == nullptr) return false;
    }
    Il2CppObject* button = prop_or_field(holder, "Button");
    if (button == nullptr) button = holder;
    // The holder's own Click() first: that is what the PC mod's PerformRoll and
    // PerformEndTurn call, and it runs the same submit handler (and the same
    // gold/turn guards, which announce themselves) as a real press. A synthetic
    // touch on these custom controls can be consumed without invoking anything,
    // so it is only the fallback.
    if (click_component(button)) {
        native_log("shop button %s: Click() ok", holder_name);
        return true;
    }
    int32_t id = instance_id(button);
    if (id != 0) {
        Il2CppObject* control = find_control_by_id(id);
        if (control != nullptr) return tap(id);
    }
    return click_component(button);
}

// Any uGUI Button under the tapped control (the game's custom buttons often
// wrap one): invoke its click event. The same known-safe path tap() already
// uses, extended to children, so no guessed signatures are involved.
bool child_button_click(Il2CppObject* component) {
    if (component == nullptr || g_cls.button == nullptr) return false;
    Il2CppObject* go = get_game_object(component);
    if (go == nullptr) return false;
    Il2CppObject* type_obj = type_object(g_cls.button);
    if (type_obj == nullptr) return false;
    std::vector<Il2CppObject*> found;
    components_under(call_object(go, "get_transform", 0, nullptr), type_obj, true, found);
    if (found.empty()) return false;
    for (size_t k = 0; k < found.size() && k < 16; ++k) {
        Il2CppObject* button = found[k];
        if (button == nullptr) continue;
        if (!object_active(get_game_object(button))) continue;
        Il2CppObject* click = call_object(button, "get_onClick", 0, nullptr);
        if (click != nullptr && call_void(click, "Invoke", 0, nullptr)) return true;
    }
    return false;
}

// Pre-tap freshness gate: the injected touch and the submit fallback both act
// on coordinates from the last snapshot, but the screen may have moved on
// (dialog opened, scene swapped). Re-resolve the control by id and refuse the
// tap unless the exact pixel the touch would land on is still inside the
// control's CURRENT rect. Testing the tap point against the live rect (rather
// than the stale rect against the live one) sidesteps the clamped box centre,
// which drifts from the pivot on wide edge buttons; the clamped centre of an
// unchanged box is inside that box by construction, so small layout breathing
// passes while a swapped screen misses. A dangling control can fault the
// resolve itself, so this runs under the fallback guard: a fault refuses the
// tap, never the game.
bool tap_check(int32_t id, float cx, float cy) {
    if (!g_ready) return false;
    if (sigsetjmp(g_fallback_jmp, 1)) {
        g_in_fallback = false;
        native_log("tap stale id=%d: check faulted", id);
        return false;
    }
    g_in_fallback = true;
    Il2CppObject* component = find_control_by_id(id);
    float x = 0, y = 0, w = 0, h = 0;
    if (component != nullptr) {
        Il2CppObject* transform = get_transform(component);
        if (transform != nullptr) node_rect(component, transform, &x, &y, &w, &h);
    }
    g_in_fallback = false;
    if (component == nullptr) {
        native_log("tap stale id=%d: control gone", id);
        return false;
    }
    if (w <= 0 || h <= 0 || x <= -5000 || y <= -5000) {
        // The control exists but cannot be put on screen. "Tapping somewhere"
        // for it is exactly what opened the store during a shop turn; a tap
        // that cannot be aimed is refused.
        native_log("tap refused id=%d: control has no usable rect (%.0f,%.0f %.0fx%.0f)", id, x,
                   y, w, h);
        return false;
    }
    // node_rect emits the pivot; four pixels cover rounding between it and
    // Java's centre.
    bool inside = cx >= x - w / 2.0f - 4.0f && cx <= x + w / 2.0f + 4.0f &&
                  cy >= y - h / 2.0f - 4.0f && cy <= y + h / 2.0f + 4.0f;
    if (!inside) {
        native_log("tap stale id=%d: tap at %.0f,%.0f outside %.0f,%.0f %.0fx%.0f",
                   id, cx, cy, x, y, w, h);
    }
    return inside;
}

// Last resort for shop/setup buttons the synthetic tap and the injected
// touch cannot drive: the game's own HangarMain submit handlers from the
// method dump (HandleRollSubmit/1, HandleDoneSubmit/1, HandleBattleSubmit/1,
// HandleChoiceZoneSubmit/1). The single argument is most plausibly the
// submitting control, so the tapped component goes first, then the overlay
// holder, then the overlay itself. Every attempt runs under the fallback
// crash guard, because a wrong handler context can fault inside game code;
// a fault aborts that attempt, never the game.
bool submit_fallback(int32_t id, const std::string& label) {
    if (!g_ready) return false;
    Il2CppObject* hangar = hangar_main_instance();
    if (hangar == nullptr) return false;
    std::string low = label;
    for (char& c : low) {
        if (c >= 'A' && c <= 'Z') c = static_cast<char>(c + ('a' - 'A'));
    }
    auto has = [&](const char* s) { return low.find(s) != std::string::npos; };
    struct Attempt {
        const char* method;
        const char* holder;
    };
    std::vector<Attempt> attempts;
    if (has("roll")) attempts.push_back({"HandleRollSubmit", "Roll"});
    if (has("done") || has("end turn")) attempts.push_back({"HandleDoneSubmit", "DoneButton"});
    if (has("sell")) attempts.push_back({"HandleSellSubmit", nullptr});
    if (has("freez") || has("snow") || has("frost")) attempts.push_back({"HandleFreezeSubmit", nullptr});
    if (has("opponent") || has("rival") || has("versus")) attempts.push_back({"HandleOpponentSubmit", nullptr});
    if (has("skip")) attempts.push_back({"HandleChooserSkip", nullptr});
    if (has("battle") || has("start") || has("fight")) {
        attempts.push_back({"HandleBattleSubmit", nullptr});
        attempts.push_back({"HandleChoiceZoneSubmit", nullptr});
    }
    if (has("choos") || has("confirm") || has("select") || has("pick")) {
        attempts.push_back({"HandleChoiceZoneSubmit", nullptr});
    }
    if (attempts.empty()) return false;
    Il2CppObject* component = find_control_by_id(id);
    // The known-safe child-button path first: no guessed signatures.
    if (component != nullptr && child_button_click(component)) {
        native_log("submit: child Button.onClick ok for '%s'", label.c_str());
        return true;
    }
    Il2CppClass* hk = g_api.object_get_class(hangar);
    Il2CppObject* overlay = prop_or_field(hangar, "Overlay");
    for (const Attempt& a : attempts) {
        const MethodInfo* m = method_on(hk, a.method, 1);
        if (m == nullptr) {
            native_log("submit: %s missing", a.method);
            continue;
        }
        std::vector<Il2CppObject*> args_try;
        if (component != nullptr) args_try.push_back(component);
        if (a.holder != nullptr && overlay != nullptr) {
            Il2CppObject* holder = prop_or_field(overlay, a.holder);
            if (holder == nullptr && strcmp(a.holder, "DoneButton") == 0) {
                holder = prop_or_field(overlay, "Done");
            }
            if (holder != nullptr && holder != component) args_try.push_back(holder);
        }
        if (overlay != nullptr) args_try.push_back(overlay);
        for (Il2CppObject* arg : args_try) {
            void* params[1] = {arg};
            if (sigsetjmp(g_fallback_jmp, 1)) {
                g_in_fallback = false;
                native_log("submit: %s faulted, trying next", a.method);
                continue;
            }
            g_in_fallback = true;
            invoke(m, hangar, params, 1);
            g_in_fallback = false;
            native_log("submit: %s ok for '%s'", a.method, label.c_str());
            return true;
        }
    }
    g_in_fallback = false;
    return false;
}

// Z key: freeze the focused shop item, like the PC mod's PerformFreeze but
// addressed by the tapped control instead of a tracked slot index. The
// focused control sits under a MinionView whose Minion is passed to the
// game's own HangarMain.FreezeItem.
// The shop item behind a control: a pet's minion, or the food/toy spell.
// A shop slot is published as its Space component, whose MinionView is a child,
// so the parent walk alone found nothing and every freeze failed.
Il2CppObject* shop_item_of(Il2CppObject* component) {
    if (component == nullptr) return nullptr;
    Il2CppObject* space = nullptr;
    if (g_cls.space != nullptr) {
        space = get_component_in_parent(component, g_cls.space);
        if (space == nullptr && g_api.object_get_class(component) == g_cls.space) {
            space = component;
        }
    }
    // The slot's own model first: Space.MinionModel / Space.SpellModel are the
    // getters the PC mod's PerformFreeze and PerformSell read, and they exist
    // even on a slot whose card view has not been built yet. The view walk is
    // the fallback for a control outside a Space.
    if (space != nullptr) {
        if (Il2CppObject* minion = call_object(space, "get_MinionModel", 0, nullptr)) return minion;
        if (Il2CppObject* spell = call_object(space, "get_SpellModel", 0, nullptr)) return spell;
    }
    Il2CppObject* view = nullptr;
    if (g_cls.minion_view != nullptr) view = get_component_in_parent(component, g_cls.minion_view);
    if (view == nullptr) view = prop_or_field(component, "MinionView");
    if (view == nullptr && space != nullptr) view = prop_or_field(space, "MinionView");
    if (view != nullptr) {
        Il2CppObject* minion = call_object(view, "get_Minion", 0, nullptr);
        if (minion != nullptr) return minion;
    }
    Il2CppObject* spell_view = prop_or_field(component, "SpellView");
    if (spell_view == nullptr && space != nullptr) spell_view = prop_or_field(space, "SpellView");
    if (spell_view != nullptr) {
        Il2CppObject* spell = call_object(spell_view, "get_Spell", 0, nullptr);
        if (spell != nullptr) return spell;
    }
    return nullptr;
}

bool freeze_focused_impl(int32_t id) {
    if (!g_ready) return false;
    Il2CppObject* hangar = hangar_main_instance();
    if (hangar == nullptr) return false;
    Il2CppObject* component = find_control_by_id(id);
    if (component == nullptr) return false;
    Il2CppObject* item = shop_item_of(component);
    if (item == nullptr) {
        // An empty slot has nothing to freeze; the controller says so instead of
        // claiming it froze something.
        native_log("freeze %d: no shop item behind the control", id);
        return false;
    }
    void* params[1] = {item};
    Il2CppObject* r = call_object(hangar, "FreezeItem", 1, params);
    (void)r;
    return true;
}

// Freezing runs game code, so it gets the same fault guard as tap().
bool freeze_focused(int32_t id) {
    if (!g_ready) return false;
    if (sigsetjmp(g_fallback_jmp, 1)) {
        g_in_fallback = false;
        native_log("freeze %d: faulted, aborting", id);
        return false;
    }
    g_in_fallback = true;
    bool ok = freeze_focused_impl(id);
    g_in_fallback = false;
    return ok;
}

// A shop or team slot click, run through the game's own handler for that
// container. This is the PC mod's PerformAction exactly: a pet is bought with
// HandleMinionShopClick(hangar.MinionShop, space), food with
// HandleSpellShopClick(hangar.SpellShop, space), and a team slot is chosen
// with HandleMinionArmyClick(hangar.MinionArmy, space). The three handlers are
// private, take (container, space) and are asserted with those parameter types
// in tests/reference/check.py, so no guessed signature is involved. Injection
// is left as the fallback because the game's custom controls accept a synthetic
// touch without running it.
struct ShopSlotTarget {
    Il2CppObject* space = nullptr;
    const sapa11y::ShopSlotHandler* handler = nullptr;
};

// Which container a tapped control belongs to, found by walking the live
// Spaces lists rather than guessing from the model type: a pet on the board is
// a MinionModel too, so the model cannot tell the shop from the army.
bool find_shop_slot(Il2CppObject* hangar, Il2CppObject* component, ShopSlotTarget& out) {
    Il2CppObject* transform = get_transform(component);
    if (transform == nullptr) return false;
    const ShopZones zones = shop_zones(hangar);
    const int slot_zones[3] = {sapa11y::kSlotPetShop, sapa11y::kSlotSpellShop, sapa11y::kSlotTeam};
    const std::vector<Il2CppObject*>* lists[3] = {&zones.pet_slots, &zones.food_slots,
                                                 &zones.team_slots};
    const int32_t self = instance_id(transform);
    for (int z = 0; z < 3; ++z) {
        const sapa11y::ShopSlotHandler* handler = sapa11y::shop_slot_handler(slot_zones[z]);
        if (handler == nullptr) continue;
        for (Il2CppObject* slot : *lists[z]) {
            if (slot == nullptr) continue;
            Il2CppObject* slot_transform = get_transform(slot);
            if (slot_transform == nullptr) continue;
            const int32_t root = instance_id(slot_transform);
            if (root == 0) continue;
            if (self == root || is_descendant_of(transform, root)) {
                out.space = slot;
                out.handler = handler;
                return true;
            }
        }
    }
    return false;
}

bool shop_click_impl(int32_t id) {
    Il2CppObject* hangar = hangar_main_instance();
    if (hangar == nullptr) return false;
    Il2CppObject* component = find_control_by_id(id);
    if (component == nullptr) return false;
    ShopSlotTarget slot;
    if (!find_shop_slot(hangar, component, slot)) return false;
    Il2CppObject* holder = prop_or_field(hangar, slot.handler->holder_field);
    if (holder == nullptr) return false;
    // A hidden container (a shop left behind on another page) must not take a
    // click: the handler would run against a model the player cannot see.
    Il2CppObject* holder_object = get_game_object(holder);
    if (holder_object != nullptr && !object_active(holder_object)) return false;
    const MethodInfo* m = method_on(g_api.object_get_class(hangar), slot.handler->method, 2);
    if (m == nullptr) return false;
    void* args[2] = {holder, slot.space};
    invoke(m, hangar, args, 2);
    native_log("shop click %d: %s(%s) ok", id, slot.handler->method, slot.handler->holder_field);
    return true;
}

// The handler runs game code, so it gets the same fault guard as tap().
bool shop_click(int32_t id) {
    if (!g_ready) return false;
    if (sigsetjmp(g_fallback_jmp, 1)) {
        g_in_fallback = false;
        native_log("shop click %d: faulted, aborting", id);
        return false;
    }
    g_in_fallback = true;
    bool ok = shop_click_impl(id);
    g_in_fallback = false;
    return ok;
}

// X key sell, first half: select the team pet behind the focused control, the
// way the PC mod's PerformSell calls HandleMinionArmyClick before deferring
// the SellButton click by a few frames.
bool select_team(int32_t id) {
    if (!g_ready || g_cls.space == nullptr) return false;
    Il2CppObject* hangar = hangar_main_instance();
    if (hangar == nullptr) return false;
    Il2CppObject* component = find_control_by_id(id);
    if (component == nullptr) return false;
    Il2CppObject* space = get_component_in_parent(component, g_cls.space);
    if (space == nullptr) return false;
    Il2CppObject* army = prop_or_field(hangar, "MinionArmy");
    if (army == nullptr) return false;
    const MethodInfo* m = method_on(g_api.object_get_class(hangar), "HandleMinionArmyClick", 2);
    if (m == nullptr) return false;
    void* args[2] = {army, space};
    invoke(m, hangar, args, 2);
    return true;
}

// X key sell, second half: click the game's own Sell button.
bool click_sell() {
    if (!g_ready) return false;
    Il2CppObject* hangar = hangar_main_instance();
    if (hangar == nullptr) return false;
    Il2CppObject* overlay = prop_or_field(hangar, "Overlay");
    if (overlay == nullptr) return false;
    Il2CppObject* sell = prop_or_field(overlay, "SellButton");
    if (sell == nullptr) sell = prop_or_field(overlay, "Sell");
    if (sell == nullptr) return false;
    // Same order as the Roll/Done buttons: the PC mod clicks the holder, so
    // that runs first and the touch is the fallback.
    if (click_component(sell)) return true;
    int32_t id = instance_id(sell);
    if (id != 0) {
        Il2CppObject* control = find_control_by_id(id);
        if (control != nullptr) return tap(id);
    }
    return false;
}

// A food spell with no Aim target covers the whole team: the game waits for a
// click on its board-sized SpellZone and ignores a pet. The PC mod reads the
// same thing (template.Aim == null) and clicks spellZone.PointerCapture, so a
// pet click on one of these spells is a real dead end the port has to route
// around. Returns 1 for a whole-team spell, 0 for a single-target one, -1 when
// no spell is being aimed.
int spell_focus_aimless_impl() {
    Il2CppObject* hangar = hangar_main_instance();
    if (hangar == nullptr) return -1;
    Il2CppObject* machine = prop_or_field(hangar, "StateMachine");
    if (machine == nullptr) return -1;
    Il2CppObject* state = prop_or_field(machine, "State");
    if (state == nullptr) return -1;
    // get_Spell exists only on HangarStateShopSpellFocus, so this also says
    // whether the shop is aiming a spell at all.
    Il2CppObject* spell = call_object(state, "get_Spell", 0, nullptr);
    if (spell == nullptr) return -1;
    Il2CppObject* spell_template = call_object(spell, "get_Template", 0, nullptr);
    if (spell_template == nullptr || g_api.field_get_value == nullptr) return -1;
    FieldInfo* aim = find_field(g_api.object_get_class(spell_template), "Aim");
    if (aim == nullptr) return -1;
    Il2CppObject* aim_target = nullptr;
    g_api.field_get_value(spell_template, aim, &aim_target);
    return aim_target == nullptr ? 1 : 0;
}

int spell_focus_aimless() {
    if (!g_ready) return -1;
    if (sigsetjmp(g_fallback_jmp, 1)) {
        g_in_fallback = false;
        native_log("spell focus: faulted, aim state unknown");
        return -1;
    }
    g_in_fallback = true;
    int r = spell_focus_aimless_impl();
    g_in_fallback = false;
    return r;
}

// Confirm a whole-team spell through the game's own SpellZone click handler
// (HangarMain.HandleSpellZoneClick(SpellZone), dump.cs:637381), the handler the
// PC mod reaches through spellZone.PointerCapture.Click().
bool use_spell_zone_impl() {
    Il2CppObject* hangar = hangar_main_instance();
    if (hangar == nullptr) return false;
    Il2CppObject* zone = prop_or_field(hangar, "SpellZone");
    if (zone == nullptr) return false;
    const MethodInfo* m = method_on(g_api.object_get_class(hangar), "HandleSpellZoneClick", 1);
    if (m != nullptr) {
        void* args[1] = {zone};
        invoke(m, hangar, args, 1);
        return true;
    }
    Il2CppObject* capture = prop_or_field(zone, "PointerCapture");
    return capture != nullptr && click_component(capture);
}

bool use_spell_zone() {
    if (!g_ready) return false;
    if (sigsetjmp(g_fallback_jmp, 1)) {
        g_in_fallback = false;
        native_log("spell zone: faulted, aborting");
        return false;
    }
    g_in_fallback = true;
    bool ok = use_spell_zone_impl();
    g_in_fallback = false;
    native_log("spell zone click: %s", ok ? "ok" : "failed");
    return ok;
}

bool set_text(int32_t id, const std::string& text) {
    if (!g_ready) return false;
    Il2CppObject* component = find_control_by_id(id);
    if (component == nullptr) return false;
    Il2CppString* s = g_api.string_new(text.c_str());
    void* params[1] = {s};
    return call_void(component, "set_text", 1, params);
}

bool focus(int32_t id) {
    if (!g_ready) return false;
    // Same reasoning as tap(): moving the game's selection runs game code on a
    // control the page may already be tearing down.
    if (sigsetjmp(g_fallback_jmp, 1)) {
        g_in_fallback = false;
        native_log("focus %d: faulted, aborting focus", id);
        return false;
    }
    g_in_fallback = true;
    Il2CppObject* component = find_control_by_id(id);
    Il2CppObject* go = component != nullptr ? get_game_object(component) : nullptr;
    Il2CppObject* es = go != nullptr ? event_system() : nullptr;
    bool ok = false;
    if (es != nullptr) {
        void* params[2] = {go, nullptr};
        ok = call_void(es, "SetSelectedGameObject", 2, params);
    }
    g_in_fallback = false;
    return ok;
}

// C key turn timer, mirroring the PC mod's ReadTurnTimer: the overlay Timer's
// SecondsDelta while it IsCounting. -100 means no timer here, -101 that it is
// not counting right now.
int shop_timer_seconds() {
    if (!g_ready) return -100;
    Il2CppObject* hangar = hangar_main_instance();
    if (hangar == nullptr) return -100;
    Il2CppObject* overlay = prop_or_field(hangar, "Overlay");
    if (overlay == nullptr) return -100;
    Il2CppObject* timer = prop_or_field(overlay, "Timer");
    if (timer == nullptr) return -100;
    if (method_on(g_api.object_get_class(timer), "get_IsCounting", 0) == nullptr) return -100;
    if (!call_bool(timer, "get_IsCounting")) return -101;
    return call_int_neg(timer, "get_SecondsDelta");
}

// One battle team as a compact line: "Flea 3/2,Beaver 2/2". The Java side
// diffs lines across polls to narrate faints, damage and summons, the way the
// PC mod's BattleNarrator announces board events.
std::string team_line(Il2CppObject* board) {
    if (board == nullptr) return {};
    Il2CppObject* minions = prop_or_field(board, "Minions");
    if (minions == nullptr) return {};
    Il2CppObject* items = prop_or_field(minions, "Items");
    if (items == nullptr) return {};
    Il2CppObject* len_obj = call_object(items, "get_Count", 0, nullptr);
    if (len_obj == nullptr) return {};
    auto* count = static_cast<int32_t*>(g_api.object_unbox(len_obj));
    if (count == nullptr || *count < 0 || *count > 32) return {};
    std::string out;
    for (int32_t i = 0; i < *count; ++i) {
        void* p[1] = {&i};
        Il2CppObject* m = call_object(items, "get_Item", 1, p);
        if (m == nullptr) continue;
        std::string name = localize_static(g_cls.minion_extensions, "GetNameLocalized", m);
        if (name.empty()) name = "pet";
        if (!out.empty()) out += ",";
        out += name;
        int atk = stat_total(m, "Attack");
        int hp = stat_total(m, "Health");
        if (atk >= 0 && hp >= 0) {
            char buf[32];
            snprintf(buf, sizeof(buf), " %d/%d", atk, hp);
            out += buf;
        }
    }
    return out;
}

void battle_teams(std::string& player, std::string& enemy) {
    player.clear();
    enemy.clear();
    if (g_cls.battle_controller == nullptr) return;
    for (Il2CppObject* controller : collect(g_cls.battle_controller)) {
        if (controller == nullptr || !object_active(get_game_object(controller))) continue;
        player = team_line(prop_or_field(controller, "PlayerBoardModel"));
        enemy = team_line(prop_or_field(controller, "OpponentBoardModel"));
        return;
    }
}

// The live battle controller; the decompile confirms Pause/Play/Skip and the
// Toggle* methods on this class, with no UI-button getters to go through.
Il2CppObject* battle_controller_active() {
    if (g_cls.battle_controller == nullptr) return nullptr;
    for (Il2CppObject* controller : collect(g_cls.battle_controller)) {
        if (controller != nullptr && object_active(get_game_object(controller))) return controller;
    }
    return nullptr;
}

// Unity's time scale, logged in battle to show whether pause uses it.
float time_scale() {
    Il2CppClass* time = find_class("UnityEngine", "Time");
    if (time == nullptr) return -1.0f;
    const MethodInfo* m = method_on(time, "get_timeScale", 0);
    if (m == nullptr) return -1.0f;
    Il2CppObject* r = invoke(m, nullptr, nullptr, 0);
    if (r == nullptr) return -1.0f;
    auto* v = static_cast<float*>(g_api.object_unbox(r));
    return v != nullptr ? *v : -1.0f;
}

bool battle_call(const char* name) {
    Il2CppObject* controller = battle_controller_active();
    if (controller == nullptr) return false;
    const MethodInfo* m = method_on(g_api.object_get_class(controller), name, 0);
    if (m == nullptr) return false;
    invoke(m, controller, nullptr, 0);
    return true;
}

// BattleController.Pause has two bool parameters (dump.cs:643895); the
// zero-argument Pause belongs to UIBattle (dump.cs:644194), a different class,
// so the old zero-argument lookup on the controller never resolved and the
// P key did nothing. Play is the zero-argument method (dump.cs:643898).
bool battle_pause(bool paused) {
    Il2CppObject* controller = battle_controller_active();
    if (controller == nullptr) return false;
    Il2CppClass* klass = g_api.object_get_class(controller);
    if (!paused) {
        const MethodInfo* m = method_on(klass, "Play", 0);
        if (m == nullptr) return false;
        invoke(m, controller, nullptr, 0);
        return true;
    }
    const MethodInfo* m = method_on(klass, "Pause", 2);
    if (m == nullptr) return false;
    // Both flags default to false in the reference signature, so pass false
    // for each rather than guessing at a meaning they might have.
    bool disable_autoplay = false;
    bool pause_time = false;
    void* args[2] = {&disable_autoplay, &pause_time};
    invoke(m, controller, args, 2);
    return true;
}

// P key: Pause when the clock is running, Play when it is stopped. The
// time scale is logged so the report shows whether the game pauses this way.
// Returns 1 when Pause was called, 2 for Play, 0 on failure.
int battle_pause_toggle() {
    if (!g_ready) return 0;
    float ts = time_scale();
    native_log("battle pause: timeScale=%.2f", ts);
    bool paused = ts >= 0.0f && ts < 0.5f;
    if (battle_pause(paused)) return paused ? 2 : 1;
    return 0;
}

// IsFastfowarding is a *static* getter on BattleController (dump.cs:643831,
// private static bool IsFastfowarding { get; }), so it is invoked with no
// receiver; passing the instance would have been an object as `this` for a
// static method.
bool battle_fastforward_state() {
    Il2CppObject* controller = battle_controller_active();
    if (controller == nullptr) return false;
    Il2CppClass* klass = g_api.object_get_class(controller);
    const MethodInfo* m = method_on(klass, "get_IsFastfowarding", 0);
    if (m == nullptr) return false;
    Il2CppObject* r = invoke(m, nullptr, nullptr, 0);
    if (r == nullptr) return false;
    auto* v = static_cast<uint8_t*>(g_api.object_unbox(r));
    return v != nullptr && *v != 0;
}

// G key: the game's own fast-forward toggle, then its own state.
// Returns 1 on, 0 off, -1 on failure.
int battle_fastforward() {
    if (!g_ready) return -1;
    if (!battle_call("ToggleFastforward")) return -1;
    return battle_fastforward_state() ? 1 : 0;
}

// U key: the game's own autoplay toggle. Its state is not readable in this
// build (no AutoPlay member), so success only reports the toggle itself.
// Returns 1 on success, -1 on failure.
int battle_autoplay() {
    if (!g_ready) return -1;
    return battle_call("ToggleAutoplay") ? 1 : -1;
}

// K key: the battle's own Skip.
bool battle_skip() {
    if (!g_ready) return false;
    return battle_call("Skip");
}

// Scoreboard (O key), the PC mod's ScoreboardNavigator without member-name
// guessing: entries are the scoreboard's child objects, each holding pet
// views; the header is the entry's own text outside the pet views.
void scoreboard_json_str(std::string& out) {
    out = "{\"open\":false,\"opponents\":[]}";
    if (g_cls.scoreboard == nullptr || g_cls.minion_canvas_view == nullptr) return;
    Il2CppObject* board = nullptr;
    for (Il2CppObject* b : collect(g_cls.scoreboard)) {
        if (b != nullptr && object_active(get_game_object(b))) {
            board = b;
            break;
        }
    }
    if (board == nullptr) return;
    Il2CppObject* root = get_transform(board);
    if (root == nullptr) return;
    Il2CppObject* view_type = type_object(g_cls.minion_canvas_view);
    if (view_type == nullptr) return;
    int32_t children = call_int(root, "get_childCount");
    if (children < 0 || children > 64) return;
    std::string opps;
    for (int32_t i = 0; i < children; ++i) {
        void* p[1] = {&i};
        Il2CppObject* child = call_object(root, "GetChild", 1, p);
        if (child == nullptr) continue;
        Il2CppObject* child_go = get_game_object(child);
        if (!object_active(child_go)) continue;
        (void)child_go;
        std::vector<Il2CppObject*> views;
        components_under(child, view_type, false, views);
        std::vector<int32_t> view_roots;
        std::vector<std::string> pets;
        for (size_t k = 0; k < views.size() && k < 16; ++k) {
            Il2CppObject* view = views[k];
            if (view == nullptr) continue;
            Il2CppObject* minion = call_object(view, "get_Model", 0, nullptr);
            if (minion == nullptr) continue;
            std::string detail = pet_detail_for_minion(minion);
            if (detail.empty()) continue;
            pets.push_back(detail);
            Il2CppObject* vt = get_transform(view);
            if (vt != nullptr) view_roots.push_back(instance_id(vt));
        }
        std::string header = join_text(child, view_roots);
        if (header.empty() && pets.empty()) continue;
        if (!opps.empty()) opps += ",";
        opps += "{\"header\":\"" + json_escape(header) + "\",\"pets\":[";
        for (size_t k = 0; k < pets.size(); ++k) {
            if (k != 0) opps += ",";
            opps += "\"" + json_escape(pets[k]) + "\"";
        }
        opps += "]}";
    }
    out = "{\"open\":true,\"opponents\":[" + opps + "]}";
    native_log("scoreboard: open with %s", opps.empty() ? "no opponents" : "opponents");
}

bool scoreboard_toggle() {
    if (!g_ready) return false;
    Il2CppObject* hangar = hangar_main_instance();
    if (hangar == nullptr) return false;
    const MethodInfo* get_sb = method_on(g_api.object_get_class(hangar), "GetScoreboard", 0);
    if (get_sb == nullptr) {
        native_log("scoreboard: GetScoreboard missing");
        return false;
    }
    Il2CppObject* go = invoke(get_sb, hangar, nullptr, 0);
    if (go == nullptr) return false;
    if (object_active(go)) {
        Il2CppObject* board = nullptr;
        if (Il2CppObject* type_obj = type_object(g_cls.scoreboard)) {
            void* p[1] = {type_obj};
            board = call_object(go, "GetComponentInChildren", 1, p);
        }
        if (board == nullptr) return false;
        return call_void(board, "HandleClose", 0, nullptr);
    }
    const MethodInfo* open = method_on(g_api.object_get_class(hangar), "OpenScoreboard", 0);
    if (open == nullptr) {
        native_log("scoreboard: OpenScoreboard missing");
        return false;
    }
    invoke(open, hangar, nullptr, 0);
    return true;
}

bool set_object_field(Il2CppObject* obj, Il2CppClass* klass, const char* name, void* value) {
    if (obj == nullptr || klass == nullptr || g_api.class_get_field_from_name == nullptr ||
        g_api.field_set_value == nullptr) {
        return false;
    }
    FieldInfo* field = g_api.class_get_field_from_name(klass, name);
    if (field == nullptr) return false;
    g_api.field_set_value(obj, field, value);
    return true;
}

// Grant the privacy/terms consent through the game's own ConsentManager, so the
// agreement window (which has no readable controls) closes and stays closed.
// The fields and methods come from the dumped ConsentManager/ConsentData
// classes; nothing here is guessed.
bool grant_consent() {
    if (!g_ready || g_api.field_set_value == nullptr) return false;
    Il2CppClass* manager_class = find_class("Spacewood.Unity", "ConsentManager");
    if (manager_class == nullptr) return false;

    // Field read only: invoking get_Instance() can run FindObjectOfType, and
    // any live engine call from this thread deadlocks against Unity's thread.
    Il2CppObject* manager = nullptr;
    if (g_api.class_get_field_from_name != nullptr && g_api.field_static_get_value != nullptr) {
        for (Il2CppClass* cur = manager_class; cur != nullptr; cur = g_api.class_get_parent(cur)) {
            FieldInfo* field = g_api.class_get_field_from_name(cur, "_instance");
            if (field == nullptr) continue;
            g_api.field_static_get_value(field, &manager);
            break;
        }
    }
    if (manager == nullptr) {
        native_log("consent: ConsentManager._instance is null");
        return false;
    }

    Il2CppObject* data = nullptr;
    FieldInfo* data_field = g_api.class_get_field_from_name != nullptr
                                    ? g_api.class_get_field_from_name(manager_class, "Data")
                                    : nullptr;
    if (data_field != nullptr && g_api.field_get_value != nullptr) {
        g_api.field_get_value(manager, data_field, &data);
    }

    Il2CppClass* data_class = find_class("Spacewood.Unity", "ConsentData");
    if (data == nullptr && data_class != nullptr) {
        data = g_api.object_new(data_class);
        if (const MethodInfo* ctor = method_on(data_class, ".ctor", 0)) {
            invoke(ctor, data, nullptr, 0);
        }
    }
    if (data == nullptr || data_class == nullptr) return false;

    int32_t version = 4;  // ConsentManager.CurrentVersion
    bool yes = true;
    set_object_field(data, data_class, "Version", &version);
    set_object_field(data, data_class, "SeenPrivacyPolicy", &yes);
    set_object_field(data, data_class, "SeenTerms", &yes);
    set_object_field(data, data_class, "Confirmed", &yes);
    if (data_field != nullptr) set_object_field(manager, manager_class, "Data", &data);

    const MethodInfo* save = method_on(manager_class, "SaveConsent", 1);
    if (save == nullptr) {
        native_log("consent: SaveConsent not found");
        return false;
    }
    native_log("consent: writing ConsentData version=4 flags=1,1,1");
    void* params[1] = {data};
    invoke(save, manager, params, 1);
    // CloseWindow is deliberately not called: it walks live UI on the Unity
    // thread and blocks this one. The saved data is enough for the next launch
    // to skip the window, and the user is told to reopen the game.
    native_log("consent: SaveConsent returned");
    return true;
}

int32_t focused() {
    if (!g_ready) return 0;
    Il2CppObject* es = event_system();
    if (es == nullptr) return 0;
    Il2CppObject* go = call_object(es, "get_currentSelectedGameObject", 0, nullptr);
    return instance_id(go);
}

// ---------------------------------------------------------------------------
// Bootstrap
// ---------------------------------------------------------------------------

bool resolve_api() {
    void* h = dlopen("libil2cpp.so", RTLD_LAZY | RTLD_NOLOAD);
    if (h == nullptr) h = dlopen("libil2cpp.so", RTLD_LAZY);
    if (h == nullptr) return false;

#define BIND(field, name)                                                        \
    do {                                                                         \
        g_api.field = reinterpret_cast<decltype(g_api.field)>(dlsym(h, name));   \
        if (g_api.field == nullptr) {                                            \
            LOGE("missing il2cpp symbol %s", name);                              \
            return false;                                                        \
        }                                                                        \
    } while (0)

    BIND(domain_get, "il2cpp_domain_get");
    BIND(thread_attach, "il2cpp_thread_attach");
    BIND(domain_get_assemblies, "il2cpp_domain_get_assemblies");
    BIND(assembly_get_image, "il2cpp_assembly_get_image");
    BIND(class_from_name, "il2cpp_class_from_name");
    BIND(class_get_method_from_name, "il2cpp_class_get_method_from_name");
    BIND(class_get_parent, "il2cpp_class_get_parent");
    BIND(class_is_assignable_from, "il2cpp_class_is_assignable_from");
    BIND(class_get_name, "il2cpp_class_get_name");
    BIND(class_get_namespace, "il2cpp_class_get_namespace");
    BIND(class_get_type, "il2cpp_class_get_type");
    BIND(type_get_object, "il2cpp_type_get_object");
    BIND(object_new, "il2cpp_object_new");
    BIND(runtime_object_init, "il2cpp_runtime_object_init");
    BIND(runtime_invoke, "il2cpp_runtime_invoke");
    BIND(string_new, "il2cpp_string_new");
    BIND(object_unbox, "il2cpp_object_unbox");
    BIND(object_get_class, "il2cpp_object_get_class");
#undef BIND

    g_api.image_get_name = reinterpret_cast<decltype(g_api.image_get_name)>(dlsym(h, "il2cpp_image_get_name"));
    g_api.class_init = reinterpret_cast<decltype(g_api.class_init)>(dlsym(h, "il2cpp_class_init"));
    if (g_api.class_init == nullptr) {
        // Some builds export only the runtime-prefixed name.
        g_api.class_init = reinterpret_cast<decltype(g_api.class_init)>(dlsym(h, "il2cpp_runtime_class_init"));
    }
    g_api.class_get_field_from_name =
        reinterpret_cast<decltype(g_api.class_get_field_from_name)>(dlsym(h, "il2cpp_class_get_field_from_name"));
    g_api.field_get_value =
        reinterpret_cast<decltype(g_api.field_get_value)>(dlsym(h, "il2cpp_field_get_value"));
    g_api.field_set_value =
        reinterpret_cast<decltype(g_api.field_set_value)>(dlsym(h, "il2cpp_field_set_value"));
    g_api.field_static_get_value =
        reinterpret_cast<decltype(g_api.field_static_get_value)>(dlsym(h, "il2cpp_field_static_get_value"));
    g_api.class_get_methods = reinterpret_cast<decltype(g_api.class_get_methods)>(dlsym(h, "il2cpp_class_get_methods"));
    g_api.class_get_fields =
        reinterpret_cast<decltype(g_api.class_get_fields)>(dlsym(h, "il2cpp_class_get_fields"));
    g_api.field_get_name =
        reinterpret_cast<decltype(g_api.field_get_name)>(dlsym(h, "il2cpp_field_get_name"));
    g_api.method_get_name = reinterpret_cast<decltype(g_api.method_get_name)>(dlsym(h, "il2cpp_method_get_name"));
    g_api.method_get_param_count =
        reinterpret_cast<decltype(g_api.method_get_param_count)>(dlsym(h, "il2cpp_method_get_param_count"));
    g_api.field_get_type =
        reinterpret_cast<decltype(g_api.field_get_type)>(dlsym(h, "il2cpp_field_get_type"));
    g_api.class_from_type =
        reinterpret_cast<decltype(g_api.class_from_type)>(dlsym(h, "il2cpp_class_from_type"));
    g_api.field_get_offset =
        reinterpret_cast<decltype(g_api.field_get_offset)>(dlsym(h, "il2cpp_field_get_offset"));
    g_api.field_get_value_object = reinterpret_cast<decltype(g_api.field_get_value_object)>(
        dlsym(h, "il2cpp_field_get_value_object"));

    g_il2cpp = h;
    Il2CppDomain domain = g_api.domain_get();
    if (domain != nullptr) g_api.thread_attach(domain);
    return true;
}

void cache_classes() {
    g_cls_tmp.object = find_class("UnityEngine", "Object");
    g_cls_tmp.component = find_class("UnityEngine", "Component");
    g_cls_tmp.game_object = find_class("UnityEngine", "GameObject");
    g_cls_tmp.transform = find_class("UnityEngine", "Transform");
    g_cls_tmp.rect_transform = find_class("UnityEngine", "RectTransform");
    g_cls_tmp.behaviour = find_class("UnityEngine", "Behaviour");
    g_cls_tmp.selectable = find_class("UnityEngine.UI", "Selectable");
    g_cls_tmp.selectable_base = find_class("Spacewood.Unity.UI", "SelectableBase");
    g_cls_tmp.button_base = find_class("Spacewood.Unity.UI", "ButtonBase");
    g_cls_tmp.button = find_class("UnityEngine.UI", "Button");
    g_cls_tmp.toggle = find_class("UnityEngine.UI", "Toggle");
    g_cls_tmp.slider = find_class("UnityEngine.UI", "Slider");
    g_cls_tmp.scrollbar = find_class("UnityEngine.UI", "Scrollbar");
    g_cls_tmp.input_field = find_class("UnityEngine.UI", "InputField");
    g_cls_tmp.tmp_input_field = find_class("TMPro", "TMP_InputField");
    g_cls_tmp.dropdown = find_class("UnityEngine.UI", "Dropdown");
    g_cls_tmp.tmp_text = find_class("TMPro", "TMP_Text");
    g_cls_tmp.ui_text = find_class("UnityEngine.UI", "Text");
    g_cls_tmp.graphic = find_class("UnityEngine.UI", "Graphic");
    g_cls_tmp.resources = find_class("UnityEngine", "Resources");
    g_cls_tmp.canvas = find_class("UnityEngine", "Canvas");
    g_cls_tmp.camera = find_class("UnityEngine", "Camera");
    g_cls_tmp.event_system = find_class("UnityEngine.EventSystems", "EventSystem");
    g_cls_tmp.pointer_event_data = find_class("UnityEngine.EventSystems", "PointerEventData");
    g_cls_tmp.screen = find_class("UnityEngine", "Screen");
    g_cls_tmp.page_manager = find_class("Spacewood.Unity", "PageManager");
    g_cls_tmp.page = find_class("Spacewood.Unity", "Page");
    g_cls_tmp.scene_manager = find_class("UnityEngine.SceneManagement", "SceneManager");
    g_cls_tmp.scene = find_class("UnityEngine.SceneManagement", "Scene");
    g_cls_tmp.hangar_overlay = find_class("Spacewood.Unity.MonoBehaviours.Build", "HangarOverlay");
    g_cls_tmp.hangar_main = find_class("Spacewood.Unity.MonoBehaviours.Build", "HangarMain");
    g_cls_tmp.battle_controller = find_class("Spacewood.Unity.MonoBehaviours.Battle", "BattleController");
    g_cls_tmp.board_model = find_class("Spacewood.Core.Models", "BoardModel");
    g_cls_tmp.minion_view = find_class("Spacewood.Unity.Views", "MinionView");
    g_cls_tmp.minion_model = find_class("Spacewood.Core.Models", "MinionModel");
    g_cls_tmp.minion_extensions = find_class("Spacewood.Unity.Extensions", "MinionModelExtensions");
    g_cls_tmp.space = find_class("Spacewood.Unity.Views", "Space");
    g_cls_tmp.uibattle = find_class("Spacewood.Unity.MonoBehaviours.Battle", "UIBattle");
    g_cls_tmp.scoreboard =
        find_class("Spacewood.Unity.MonoBehaviours.Build", "HangarScoreboard");
    g_cls_tmp.minion_canvas_view = find_class("Spacewood.Unity.Views", "MinionCanvasView");

    LOGI("classes: object=%p selectable=%p selectableBase=%p buttonBase=%p tmpText=%p eventSystem=%p pageManager=%p",
         g_cls_tmp.object, g_cls_tmp.selectable, g_cls_tmp.selectable_base, g_cls_tmp.button_base,
         g_cls_tmp.tmp_text, g_cls_tmp.event_system, g_cls_tmp.page_manager);
    native_log("classes: canvas=%d camera=%d screen=%d rect=%d behaviour=%d graphic=%d resources=%d scene=%d",
               g_cls_tmp.canvas != nullptr ? 1 : 0, g_cls_tmp.camera != nullptr ? 1 : 0,
               g_cls_tmp.screen != nullptr ? 1 : 0, g_cls_tmp.rect_transform != nullptr ? 1 : 0,
               g_cls_tmp.behaviour != nullptr ? 1 : 0, g_cls_tmp.graphic != nullptr ? 1 : 0,
               g_cls_tmp.resources != nullptr ? 1 : 0, g_cls_tmp.scene != nullptr ? 1 : 0);
}

// Runs on the thread that calls into the bridge, which is the Java main thread
// and therefore Unity's main thread. A detached bootstrap thread was tried
// first and crashed: il2cpp metadata calls from a thread Unity does not own are
// not safe while the game is still initialising. Retrying on the caller is.
int g_bootstrap_attempts = 0;

void ensure_bootstrap() {
    if (g_ready) return;
    ++g_bootstrap_attempts;
    if (!g_resolve_attempted) {
        if (resolve_api()) {
            g_resolve_attempted = true;
            LOGI("libil2cpp resolved");
            native_log("libil2cpp resolved");
        }
    }
    if (!g_resolve_attempted) {
        if (g_bootstrap_attempts % 20 == 0) native_log("waiting for libil2cpp");
        return;
    }
    // The process starts before Unity initialises, so the domain can be null on
    // the first attempts; attaching once it exists is what makes class lookups
    // work. thread_attach is idempotent.
    Il2CppDomain domain = g_api.domain_get();
    if (domain != nullptr) g_api.thread_attach(domain);
    else {
        if (g_bootstrap_attempts % 20 == 0) native_log("boot: waiting for Unity domain");
        return;
    }
    cache_classes();
    // Metadata resolves as soon as the il2cpp domain exists, which can be
    // before Unity has created any scene. Snapshotting then reads
    // half-initialized hierarchies (transient 26->2->7 node garble), so the
    // bridge stays down until the game has booted: a loaded scene AND a
    // live top-level object (first page, hangar, or battle). The consent /
    // loading bootstrap (ConsentManager.PrepareBootstrapAsync) runs before
    // any page exists; publishing then is what injects a broken tree.
    int scene_count = -1;
    if (g_cls_tmp.scene_manager != nullptr) {
        scene_count = call_int_static(g_cls_tmp.scene_manager, "get_sceneCount");
    }
    // collect()/find_objects_of_type() read g_cls, so publish the freshly
    // cached classes before probing the live scene; g_ready still gates
    // snapshots until boot is confirmed below.
    g_cls = g_cls_tmp;
    // Scene count alone gates readiness: the consent/loading UI exists
    // before any PageManager page, Hangar, or battle object does, and
    // gating on those kept the bridge down forever on the first screens.
    // Publication of half-laid-out trees is held separately by the layout
    // gate in snapshot_json and the Java stable-tree gating.
    if (g_cls_tmp.selectable != nullptr && g_cls_tmp.event_system != nullptr && scene_count >= 1) {
        g_ready = true;
        diag_game_classes();
        LOGI("bridge ready");
        native_log("bridge ready, selectable=%p text=%p",
                   static_cast<void*>(g_cls.selectable), static_cast<void*>(g_cls.tmp_text));
    } else if (g_bootstrap_attempts % 20 == 0) {
        native_log("bootstrap attempt %d: selectable=%p eventSystem=%p scenes=%d", g_bootstrap_attempts,
                   static_cast<void*>(g_cls_tmp.selectable),
                   static_cast<void*>(g_cls_tmp.event_system), scene_count);
    }
}

}  // namespace

// ---------------------------------------------------------------------------
// JNI
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jboolean JNICALL
Java_app_sapa11y_extension_SapNative_nativeReady(JNIEnv*, jclass) {
    ensure_bootstrap();
    return g_ready ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_sapa11y_extension_SapNative_nativeSnapshot(JNIEnv* env, jclass) {
    ensure_bootstrap();
    return env->NewStringUTF(snapshot_json().c_str());
}

// The controller knows what a user action starts: Continue, Play, End turn and
// friends all load a scene. Walking the object graph while that load holds
// engine locks is what wedges the game (the UI thread blocks inside the call
// and never returns to input), so the controller asks for a pause before it
// taps and the scan stays off the graph for that window.
extern "C" JNIEXPORT void JNICALL
Java_app_sapa11y_extension_SapNative_nativeQuiet(JNIEnv*, jclass, jint ms) {
    if (ms <= 0) return;
    const long until = now_ms() + ms;
    if (until > g_quiet_until_ms) g_quiet_until_ms = until;
    native_log("quiet: scan paused %d ms by the controller", static_cast<int>(ms));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_sapa11y_extension_SapNative_nativeTap(JNIEnv*, jclass, jint id) {
    return tap(id) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_sapa11y_extension_SapNative_nativeRoll(JNIEnv*, jclass) {
    return shop_button_click("Roll") ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_sapa11y_extension_SapNative_nativeEndTurn(JNIEnv*, jclass) {
    return shop_button_click("DoneButton") ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_sapa11y_extension_SapNative_nativeTapCheck(JNIEnv*, jclass, jint id, jfloat cx, jfloat cy) {
    return tap_check(id, cx, cy) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_sapa11y_extension_SapNative_nativeSubmitFallback(JNIEnv* env, jclass, jint id, jstring label) {
    if (label == nullptr) return JNI_FALSE;
    const char* utf = env->GetStringUTFChars(label, nullptr);
    std::string value = utf != nullptr ? utf : "";
    if (utf != nullptr) env->ReleaseStringUTFChars(label, utf);
    return submit_fallback(id, value) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_sapa11y_extension_SapNative_nativeShopClick(JNIEnv*, jclass, jint id) {
    return shop_click(id) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_sapa11y_extension_SapNative_nativeSpellFocusAimless(JNIEnv*, jclass) {
    return spell_focus_aimless();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_sapa11y_extension_SapNative_nativeUseSpellZone(JNIEnv*, jclass) {
    return use_spell_zone() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_sapa11y_extension_SapNative_nativeFreeze(JNIEnv*, jclass, jint id) {
    return freeze_focused(id) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_sapa11y_extension_SapNative_nativeSelectTeam(JNIEnv*, jclass, jint id) {
    return select_team(id) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_sapa11y_extension_SapNative_nativeClickSell(JNIEnv*, jclass) {
    return click_sell() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_sapa11y_extension_SapNative_nativeShopTimer(JNIEnv*, jclass) {
    return shop_timer_seconds();
}

extern "C" JNIEXPORT jint JNICALL
Java_app_sapa11y_extension_SapNative_nativeBattlePause(JNIEnv*, jclass) {
    return battle_pause_toggle();
}

extern "C" JNIEXPORT jint JNICALL
Java_app_sapa11y_extension_SapNative_nativeBattleFastForward(JNIEnv*, jclass) {
    return battle_fastforward();
}

extern "C" JNIEXPORT jint JNICALL
Java_app_sapa11y_extension_SapNative_nativeBattleAutoplay(JNIEnv*, jclass) {
    return battle_autoplay();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_sapa11y_extension_SapNative_nativeBattleSkip(JNIEnv*, jclass) {
    return battle_skip() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_sapa11y_extension_SapNative_nativeScoreboardToggle(JNIEnv*, jclass) {
    return scoreboard_toggle() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_sapa11y_extension_SapNative_nativeSetText(JNIEnv* env, jclass, jint id, jstring text) {
    if (text == nullptr) return JNI_FALSE;
    const char* utf = env->GetStringUTFChars(text, nullptr);
    std::string value = utf != nullptr ? utf : "";
    if (utf != nullptr) env->ReleaseStringUTFChars(text, utf);
    return set_text(id, value) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_sapa11y_extension_SapNative_nativeFocus(JNIEnv*, jclass, jint id) {
    return focus(id) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_sapa11y_extension_SapNative_nativeFocused(JNIEnv*, jclass) {
    return focused();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_sapa11y_extension_SapNative_nativeGrantConsent(JNIEnv*, jclass) {
    ensure_bootstrap();
    return grant_consent() ? JNI_TRUE : JNI_FALSE;
}

// What the game state looks like right now, independent of the accessibility
// tree: the evidence needed to tell "no match started" from "match started but
// the scanner found nothing" from "found but not published".
extern "C" JNIEXPORT jstring JNICALL
Java_app_sapa11y_extension_SapNative_nativeProbe(JNIEnv* env, jclass) {
    ensure_bootstrap();
    if (!g_ready) return env->NewStringUTF("probe: bridge not ready");
    // The probe walks the same objects the scan does (HangarOverlay, BoardModel,
    // the Spaces lists), and the controller calls it every couple of seconds
    // across the transition watch. It is the one walk that was still running
    // inside a load: the log for a session that died after "Continue" ends with
    // the pause markers and then nothing. Honour the same pause here, and stay
    // off the graph while a scene is being added.
    if (now_ms() < g_quiet_until_ms) {
        return env->NewStringUTF("probe: paused, the page is loading");
    }
    if (g_cls.scene_manager != nullptr) {
        int32_t scenes = call_int_static(g_cls.scene_manager, "get_sceneCount");
        if (g_stable_scene_count > 0 && scenes > g_stable_scene_count) {
            char buf[96];
            snprintf(buf, sizeof(buf), "probe: paused, %d scenes loading", scenes);
            return env->NewStringUTF(buf);
        }
    }
    if (sigsetjmp(g_fallback_jmp, 1)) {
        g_in_fallback = false;
        return env->NewStringUTF("probe: faulted while probing");
    }
    g_in_fallback = true;
    const ShopState shop = shop_state();
    Il2CppObject* hangar = hangar_main_instance();
    int team = -1, shop_pets = -1, shop_food = -1, actions = -1;
    if (hangar != nullptr) {
        ShopZones zones = shop_zones(hangar);
        team = static_cast<int>(zones.team.size());
        shop_pets = static_cast<int>(zones.pet_shop.size());
        shop_food = static_cast<int>(zones.food_shop.size());
        actions = static_cast<int>(zones.action_components.size());
    }
    g_in_fallback = false;
    char buf[384];
    snprintf(buf, sizeof(buf),
             "match: scenes=%d hangar=%d battleCls=%d phase=%s gold=%d lives=%d/%d turn=%d tier=%d wins=%d losses=%d team=%d shopPets=%d shopFood=%d actions=%d nodes=%d",
             g_stat_scene_count, hangar != nullptr ? 1 : 0,
             g_cls.battle_controller != nullptr ? 1 : 0, shop.phase.c_str(),
             shop.gold, shop.lives, shop.lives_max, shop.turn, shop.tier,
             shop.wins, shop.losses, team, shop_pets, shop_food, actions, g_last_nodes);
    return env->NewStringUTF(buf);
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_sapa11y_extension_SapNative_nativeStats(JNIEnv* env, jclass) {
    ensure_bootstrap();
    char buf[256];
    snprintf(buf, sizeof(buf), "findEmpty=%d findObjs=%d scenes=%d roots=%d sceneObjs=%d nodes=%d canvas=%d cam=%d screen=%d rect=%d screenPx=%dx%d proj=overlay%d/canvas%d/main%d/none%d tid=%ld",
             g_stat_find_empty, g_stat_find_objects, g_stat_scene_count,
             g_stat_scene_roots, g_stat_scene_objects, g_last_nodes,
             g_cls.canvas != nullptr ? 1 : 0, g_cls.camera != nullptr ? 1 : 0,
             g_cls.screen != nullptr ? 1 : 0, g_cls.rect_transform != nullptr ? 1 : 0,
             screen_width(), screen_height(),
             g_stat_proj_overlay, g_stat_proj_canvas, g_stat_proj_main, g_stat_proj_none,
             current_tid());
    return env->NewStringUTF(buf);
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_sapa11y_extension_SapNative_nativeBackend(JNIEnv* env, jclass) {
    ensure_bootstrap();
    const char* state = g_ready ? "ready" : (g_il2cpp != nullptr ? "loading" : "waiting for il2cpp");
    char buf[256];
    snprintf(buf, sizeof(buf), "%s sel=%d es=%d txt=%d sm=%d canvas=%d cam=%d screen=%d rect=%d nodes=%d", state,
             g_cls_tmp.selectable != nullptr ? 1 : 0,
             g_cls_tmp.event_system != nullptr ? 1 : 0,
             g_cls_tmp.tmp_text != nullptr ? 1 : 0,
             g_cls_tmp.scene_manager != nullptr ? 1 : 0,
             g_cls_tmp.canvas != nullptr ? 1 : 0,
             g_cls_tmp.camera != nullptr ? 1 : 0,
             g_cls_tmp.screen != nullptr ? 1 : 0,
             g_cls_tmp.rect_transform != nullptr ? 1 : 0,
             g_last_nodes);
    return env->NewStringUTF(buf);
}

extern "C" JNIEXPORT void JNICALL
Java_app_sapa11y_extension_SapNative_nativeSetLogPath(JNIEnv* env, jclass, jstring path) {
    if (path == nullptr) return;
    const char* utf = env->GetStringUTFChars(path, nullptr);
    if (utf == nullptr) return;
    g_log_path = utf;
    env->ReleaseStringUTFChars(path, utf);
    // Raw append fd for the fault handler: opening/formatting with stdio in a
    // signal context can deadlock on a FILE lock the interrupted thread holds.
    if (g_log_fd >= 0) close(g_log_fd);
    g_log_fd = open(g_log_path.c_str(), O_WRONLY | O_APPEND | O_CREAT, 0644);
    // Stamped into every log so a report copied by hand says which bridge made
    // it; two builds otherwise look identical in a truncated file.
    native_log("bridge build %s (fault fd=%d)", kBridgeBuild, g_log_fd);
}

namespace {

// One entry per distinct fault site, so a storm of the same instruction is
// logged a few times instead of thousands. Filled from the signal handler,
// so it holds only PODs and is never allocated.
struct FaultSite {
    int sig;
    unsigned long pc;
    long hits;
};
static FaultSite g_fault_sites[16];
static int g_fault_site_count = 0;

// Note on backtraces: running _Unwind_Backtrace inside the signal handler is
// not async-signal-safe and cannot be tested off-device; if it faulted, the
// handler would re-enter and kill a fault the bridge can otherwise absorb. The
// fault line therefore records the faulting PC/LR/SP, the library and offset,
// and the scanner phase, which is what a symbolised tombstone would give.

void crash_handler(int sig, siginfo_t* info, void* uctx) {
    if (g_in_fault_handler) {
        // Faulted while handling a fault (unwinder or dladdr): nothing safe is
        // left to try, so die rather than loop.
        raw_write_all("fault: re-entered handler, giving up\n", 36);
        signal(sig, SIG_DFL);
        raise(sig);
        return;
    }
    g_in_fault_handler = true;
    const bool guarded = (sig == SIGSEGV || sig == SIGBUS || sig == SIGILL || sig == SIGFPE);
    // Innermost guard wins. A description read runs inside a snapshot but has
    // its own handler: one bad card must skip the description, not unwind the
    // whole scan and leave TalkBack on the previous screen.
    const bool in_fallback = guarded && g_in_fallback;
    const bool in_snapshot = guarded && !in_fallback && g_in_snapshot;

    // Faulting instruction: ucontext carries the interrupted registers, which
    // is what makes a trip actionable (address alone says nothing about which
    // scanner call faulted).
    unsigned long pc = 0, lr = 0, sp = 0;
    if (uctx != nullptr) {
        auto* uc = static_cast<ucontext_t*>(uctx);
#if defined(__aarch64__)
        pc = static_cast<unsigned long>(uc->uc_mcontext.pc);
        lr = static_cast<unsigned long>(uc->uc_mcontext.regs[30]);
        sp = static_cast<unsigned long>(uc->uc_mcontext.sp);
#elif defined(__arm__)
        pc = static_cast<unsigned long>(uc->uc_mcontext.arm_pc);
        lr = static_cast<unsigned long>(uc->uc_mcontext.arm_lr);
        sp = static_cast<unsigned long>(uc->uc_mcontext.arm_sp);
#else
        (void)uc;
#endif
    }

    // Resolve the faulting instruction to library + offset where possible.
    const char* lib = nullptr;
    unsigned long lib_off = 0;
    if (pc != 0) {
        Dl_info dli{};
        if (dladdr(reinterpret_cast<void*>(pc), &dli) != 0) {
            lib = dli.dli_fname;
            lib_off = pc - reinterpret_cast<unsigned long>(dli.dli_fbase);
        }
    }

    // Deduplicate by (signal, pc): first three hits and then each 1/10000th.
    long hits = 0;
    FaultSite* site = nullptr;
    for (int i = 0; i < g_fault_site_count; ++i) {
        if (g_fault_sites[i].sig == sig && g_fault_sites[i].pc == pc) {
            site = &g_fault_sites[i];
            break;
        }
    }
    if (site == nullptr && g_fault_site_count < 16) {
        site = &g_fault_sites[g_fault_site_count++];
        site->sig = sig;
        site->pc = pc;
        site->hits = 0;
    }
    bool log_this;
    if (site != nullptr) {
        hits = ++site->hits;
        log_this = hits <= 3 || hits % 10000 == 0;
    } else {
        // More distinct sites than the table holds: keep a coarse counter so
        // a flood cannot turn into one line per fault.
        static long overflow_hits = 0;
        log_this = (++overflow_hits <= 5) || (overflow_hits % 10000 == 0);
    }

    if (log_this) {
        char buf[400];
        char* p = buf;
        char* end = buf + sizeof(buf) - 2;
        p = app_str(p, end, "\nfault: sig=");
        p = app_int(p, end, sig);
        p = app_str(p, end, " pc=");
        p = app_hex(p, end, pc);
        p = app_str(p, end, " lr=");
        p = app_hex(p, end, lr);
        p = app_str(p, end, " sp=");
        p = app_hex(p, end, sp);
        p = app_str(p, end, " addr=");
        p = app_hex(p, end, info != nullptr ? reinterpret_cast<unsigned long>(info->si_addr) : 0UL);
        p = app_str(p, end, " lib=");
        p = app_str(p, end, lib != nullptr ? lib : "(unresolved)");
        p = app_str(p, end, " off=");
        p = app_hex(p, end, lib_off);
        p = app_str(p, end, " phase=");
        p = app_str(p, end, scan_phase_name(g_scan_phase));
        p = app_str(p, end, " thread=");
        p = app_int(p, end, current_tid());
        p = app_str(p, end, " hits=");
        p = app_int(p, end, hits);
        p = app_str(p, end, in_snapshot ? " scope=snapshot\n"
                        : (in_fallback ? (g_in_snapshot ? " scope=detail\n" : " scope=action\n")
                                       : " scope=fatal\n"));
        raw_write_all(buf, static_cast<size_t>(p - buf));
    }

    g_in_fault_handler = false;
    if (in_snapshot) {
        // Let the game settle instead of re-entering the same half-torn object
        // graph at 5 Hz: the pause doubles per consecutive fault up to 16
        // polls (~3 s) and resets as soon as a snapshot completes.
            // A fault means the graph was mid-swap: give the game about a second
        // before walking it again, not one 200 ms poll. Re-entering immediately
        // is what turned single faults into storms of tens of thousands.
        g_skip_snapshots = g_skip_snapshots < 1 ? 5
                          : (g_skip_snapshots < 16 ? g_skip_snapshots * 2 : 16);
        siglongjmp(g_snap_jmp, 1);
        return;
    }
    if (in_fallback) {
        siglongjmp(g_fallback_jmp, 1);
        return;
    }
    // Not our memory to recover from: leave the process exactly as it would
    // have died without the handler.
    raw_write_all("fault: fatal outside the scanner, restoring default\n", 52);
    signal(sig, SIG_DFL);
    raise(sig);
}

void install_crash_handler() {
    struct sigaction action {};
    action.sa_sigaction = crash_handler;
    action.sa_flags = SA_SIGINFO;
    sigemptyset(&action.sa_mask);
    const int signals[] = {SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE};
    for (int sig : signals) {
        sigaction(sig, &action, nullptr);
    }
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    install_crash_handler();
    return JNI_VERSION_1_6;
}
