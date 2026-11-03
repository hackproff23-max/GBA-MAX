#include <jni.h>
#include <dlfcn.h>
#include <android/bitmap.h>
#include <mutex>
#include <vector>
#include <string>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <algorithm>
#include "libretro.h"

static void* lib;
static void (*f_env)(retro_environment_t);
static void (*f_vid)(retro_video_refresh_t);
static void (*f_as)(retro_audio_sample_t);
static void (*f_ab)(retro_audio_sample_batch_t);
static void (*f_poll)(retro_input_poll_t);
static void (*f_st)(retro_input_state_t);
static void (*f_init)(void);
static bool (*f_load)(const struct retro_game_info*);
static void (*f_unload)(void);
static void (*f_run)(void);
static void (*f_av)(struct retro_system_av_info*);
static void* (*f_mem)(unsigned);
static size_t (*f_msz)(unsigned);
static size_t (*f_ssz)(void);
static bool (*f_ser)(void*, size_t);
static bool (*f_unser)(const void*, size_t);
static void (*f_reset)(void);
static void (*f_chr)(void);
static void (*f_chs)(unsigned, bool, const char*);

static std::mutex vm, am;
static std::vector<uint32_t> frame;
static std::vector<int16_t> aud;
static unsigned fw = 240, fh = 160;
static int fmt = 0;
static volatile int pad = 0;
static volatile bool skipv = false;
static double sr = 32768;
static std::string sysdir;

static uint8_t L5[32], L6[64]; static struct LutInit { LutInit() { for (int i = 0; i < 32; i++) L5[i] = (uint8_t)(i * 255 / 31); for (int i = 0; i < 64; i++) L6[i] = (uint8_t)(i * 255 / 63); } } lutInit;
static bool env(unsigned cmd, void* d) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: fmt = *(int*)d; return true;
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: *(const char**)d = sysdir.c_str(); return true;
        case RETRO_ENVIRONMENT_GET_CAN_DUPE: *(bool*)d = true; return true;
#ifdef RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE
        case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE: if (d) *(int*)d = skipv ? 2 : 3; return true;
#endif
    }
    return false;
}

static void video(const void* d, unsigned w, unsigned h, size_t pitch) {
    if (!d || skipv) return;
    std::lock_guard<std::mutex> g(vm);
    fw = w; fh = h;
    frame.resize((size_t)w * h);
    for (unsigned y = 0; y < h; y++) {
        uint32_t* o = &frame[(size_t)y * w];
        const char* s = (const char*)d + y * pitch;
        if (fmt == 1) {
            const uint32_t* q = (const uint32_t*)s;
            for (unsigned x = 0; x < w; x++) {
                uint32_t v = q[x];
                o[x] = 0xFF000000 | ((v & 0xFF) << 16) | (v & 0xFF00) | ((v >> 16) & 0xFF);
            }
        } else {
            const uint16_t* q = (const uint16_t*)s;
            for (unsigned x = 0; x < w; x++) {
                uint32_t v = q[x], r, gg, b;
                if (fmt == 2) { r = L5[v >> 11]; gg = L6[(v >> 5) & 63]; b = L5[v & 31]; }
                else { r = L5[(v >> 10) & 31]; gg = L5[(v >> 5) & 31]; b = L5[v & 31]; }
                o[x] = 0xFF000000 | (b << 16) | (gg << 8) | r;
            }
        }
    }
}

static void audio1(int16_t l, int16_t r) {
    std::lock_guard<std::mutex> g(am);
    aud.push_back(l); aud.push_back(r);
}
static size_t audioN(const int16_t* d, size_t n) {
    std::lock_guard<std::mutex> g(am);
    aud.insert(aud.end(), d, d + n * 2);
    return n;
}
static void poll() {}
static int16_t input(unsigned port, unsigned dev, unsigned idx, unsigned id) {
    if (port == 0 && dev == RETRO_DEVICE_JOYPAD && id < 16) return (pad >> id) & 1;
    return 0;
}

extern "C" {

JNIEXPORT jboolean JNICALL Java_org_gbamax_emu_Native_init(JNIEnv* e, jclass, jstring core, jstring dir) {
    const char* c = e->GetStringUTFChars(core, 0);
    lib = dlopen(c, RTLD_NOW);
    e->ReleaseStringUTFChars(core, c);
    if (!lib) return JNI_FALSE;
    const char* d = e->GetStringUTFChars(dir, 0);
    sysdir = d;
    e->ReleaseStringUTFChars(dir, d);
#define SYM(v, n) v = (decltype(v))dlsym(lib, n)
    SYM(f_env, "retro_set_environment"); SYM(f_vid, "retro_set_video_refresh");
    SYM(f_as, "retro_set_audio_sample"); SYM(f_ab, "retro_set_audio_sample_batch");
    SYM(f_poll, "retro_set_input_poll"); SYM(f_st, "retro_set_input_state");
    SYM(f_init, "retro_init"); SYM(f_load, "retro_load_game"); SYM(f_unload, "retro_unload_game");
    SYM(f_run, "retro_run"); SYM(f_av, "retro_get_system_av_info");
    SYM(f_mem, "retro_get_memory_data"); SYM(f_msz, "retro_get_memory_size");
    SYM(f_ssz, "retro_serialize_size"); SYM(f_ser, "retro_serialize");
    SYM(f_unser, "retro_unserialize"); SYM(f_reset, "retro_reset");
    SYM(f_chr, "retro_cheat_reset"); SYM(f_chs, "retro_cheat_set");
    if (!f_env || !f_init || !f_load || !f_run) return JNI_FALSE;
    f_env(env); f_vid(video); f_as(audio1); f_ab(audioN); f_poll(poll); f_st(input);
    f_init();
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_org_gbamax_emu_Native_load(JNIEnv* e, jclass, jbyteArray rom, jstring path) {
    jsize n = e->GetArrayLength(rom);
    jbyte* data = e->GetByteArrayElements(rom, 0);
    const char* p = e->GetStringUTFChars(path, 0);
    struct retro_game_info gi = { p, data, (size_t)n, nullptr };
    bool ok = f_load(&gi);
    if (ok) {
        struct retro_system_av_info av;
        memset(&av, 0, sizeof(av));
        f_av(&av);
        if (av.timing.sample_rate > 0) sr = av.timing.sample_rate;
    }
    e->ReleaseStringUTFChars(path, p);
    e->ReleaseByteArrayElements(rom, data, JNI_ABORT);
    return ok;
}

JNIEXPORT void JNICALL Java_org_gbamax_emu_Native_unload(JNIEnv*, jclass) { f_unload(); }
JNIEXPORT void JNICALL Java_org_gbamax_emu_Native_run(JNIEnv*, jclass) { f_run(); }
JNIEXPORT void JNICALL Java_org_gbamax_emu_Native_input(JNIEnv*, jclass, jint m) { pad = m; }
JNIEXPORT void JNICALL Java_org_gbamax_emu_Native_setSkip(JNIEnv*, jclass, jboolean s) { skipv = s; }
JNIEXPORT jdouble JNICALL Java_org_gbamax_emu_Native_rate(JNIEnv*, jclass) { return sr; }
JNIEXPORT void JNICALL Java_org_gbamax_emu_Native_reset(JNIEnv*, jclass) { if (f_reset) f_reset(); }

// ================= cheats (GameShark / Action Replay / CodeBreaker) =================
JNIEXPORT void JNICALL Java_org_gbamax_emu_Native_cheatReset(JNIEnv*, jclass) { if (f_chr) f_chr(); }

JNIEXPORT void JNICALL Java_org_gbamax_emu_Native_cheatSet(JNIEnv* e, jclass, jint idx, jboolean on, jstring code) {
    if (!f_chs) return;
    const char* c = e->GetStringUTFChars(code, 0);
    f_chs((unsigned)idx, on == JNI_TRUE, c);
    e->ReleaseStringUTFChars(code, c);
}

JNIEXPORT void JNICALL Java_org_gbamax_emu_Native_draw(JNIEnv* e, jclass, jobject bm) {
    AndroidBitmapInfo i;
    void* px;
    if (AndroidBitmap_getInfo(e, bm, &i) < 0 || AndroidBitmap_lockPixels(e, bm, &px) < 0) return;
    {
        std::lock_guard<std::mutex> g(vm);
        if (frame.size() >= (size_t)fw * fh) {
            unsigned w = std::min(i.width, fw), h = std::min(i.height, fh);
            for (unsigned y = 0; y < h; y++)
                memcpy((char*)px + (size_t)(h - 1 - y) * i.stride, &frame[(size_t)y * fw], w * 4);
        }
    }
    AndroidBitmap_unlockPixels(e, bm);
}

JNIEXPORT jint JNICALL Java_org_gbamax_emu_Native_audio(JNIEnv* e, jclass, jshortArray out) {
    std::lock_guard<std::mutex> g(am);
    jsize len = e->GetArrayLength(out);
    jsize n = std::min((jsize)aud.size(), len);
    if (n > 0) e->SetShortArrayRegion(out, 0, n, aud.data());
    aud.clear();
    return n;
}

JNIEXPORT jboolean JNICALL Java_org_gbamax_emu_Native_saveSram(JNIEnv* e, jclass, jstring path) {
    void* m = f_mem ? f_mem(RETRO_MEMORY_SAVE_RAM) : nullptr;
    size_t s = f_msz ? f_msz(RETRO_MEMORY_SAVE_RAM) : 0;
    if (!m || !s) return JNI_FALSE;
    const char* p = e->GetStringUTFChars(path, 0);
    FILE* f = fopen(p, "wb");
    if (f) { fwrite(m, 1, s, f); fclose(f); }
    e->ReleaseStringUTFChars(path, p);
    return f != nullptr;
}

JNIEXPORT void JNICALL Java_org_gbamax_emu_Native_loadSram(JNIEnv* e, jclass, jstring path) {
    void* m = f_mem ? f_mem(RETRO_MEMORY_SAVE_RAM) : nullptr;
    size_t s = f_msz ? f_msz(RETRO_MEMORY_SAVE_RAM) : 0;
    if (!m || !s) return;
    const char* p = e->GetStringUTFChars(path, 0);
    FILE* f = fopen(p, "rb");
    if (f) { fread(m, 1, s, f); fclose(f); }
    e->ReleaseStringUTFChars(path, p);
}

// ================= estados salvos =================
JNIEXPORT jboolean JNICALL Java_org_gbamax_emu_Native_saveState(JNIEnv* e, jclass, jstring path) {
    if (!f_ssz || !f_ser) return JNI_FALSE;
    size_t s = f_ssz();
    if (!s) return JNI_FALSE;
    std::vector<uint8_t> buf(s);
    if (!f_ser(buf.data(), s)) return JNI_FALSE;
    const char* p = e->GetStringUTFChars(path, 0);
    FILE* f = fopen(p, "wb");
    bool ok = false;
    if (f) { ok = fwrite(buf.data(), 1, s, f) == s; fclose(f); }
    e->ReleaseStringUTFChars(path, p);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_org_gbamax_emu_Native_loadState(JNIEnv* e, jclass, jstring path) {
    if (!f_unser) return JNI_FALSE;
    const char* p = e->GetStringUTFChars(path, 0);
    FILE* f = fopen(p, "rb");
    e->ReleaseStringUTFChars(path, p);
    if (!f) return JNI_FALSE;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    bool ok = false;
    if (n > 0) {
        std::vector<uint8_t> buf((size_t)n);
        if (fread(buf.data(), 1, (size_t)n, f) == (size_t)n) ok = f_unser(buf.data(), (size_t)n);
    }
    fclose(f);
    return ok ? JNI_TRUE : JNI_FALSE;
}

}
