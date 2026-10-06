package com.xw.vvtts.core

import android.util.Log

/**
 * In-house ECI bridge (libvvtts_core.so)v3
 * One session per language(a dialect handle);voices are driven by backtick annotations.
 *
 * All native declarations must be @JvmStatic(static methods land on the outer class),
 * 否则 Kotlin 会把它编译进 Companion,JNI 符号名会变成
 * Java_com_xw_vvtts_core_VvttsCore_00024Companion_native*,C 桥接就找不到方法了.
 */
class VvttsCore {

    companion object {
        // ECIParam in native/openevv/include/eci.h; distinct from ECIVoiceParam.
        const val ECI_SAMPLE_RATE = 5
        const val CHARSET_1252 = 0   // Windows-1252 (Western
        const val CHARSET_GBK = 6    // Chinese GB18030

        init {
            System.loadLibrary("vvtts_core")
        }

        @JvmStatic external fun nativeInitEngine(configDir: String, libDir: String, dialect: Int): Long
        @JvmStatic external fun nativeSynthesize(handle: Long, dialect: Int, text: ByteArray, charsetId: Int, outPath: String?): ShortArray?
        @JvmStatic external fun nativeSetVoiceParam(handle: Long, voice: Int, param: Int, value: Int): Int
        @JvmStatic external fun nativeGetVoiceParam(handle: Long, voice: Int, param: Int): Int
        @JvmStatic external fun nativeSetParam(handle: Long, param: Int, value: Int): Int
        @JvmStatic external fun nativeSetStandardVoice(handle: Long, voiceNumber: Int): Int
        @JvmStatic external fun nativeShutdown(handle: Long)
        @JvmStatic external fun nativeStop(handle: Long)

       /** Create a session for the given dialect(repeated calls rebuild it) */
        @JvmStatic
        fun openEngine(configDir: String?, libDir: String?, dialect: Int): Long {
            return try {
                nativeInitEngine(configDir!!, libDir!!, dialect)
            } catch (e: Throwable) {
                Log.e("VvttsCore", "openEngine native crash", e)
                0
            }
        }

       /** Pre-encoded text to PCM; null means no audio/cancelled, empty means
        * the session is retired and must be evicted and shut down. */
        @JvmStatic
        fun synth(handle: Long, dialect: Int, text: ByteArray, charsetId: Int, outPath: String?): ShortArray? {
            return try {
                nativeSynthesize(handle, dialect, text, charsetId, outPath)
            } catch (e: Throwable) {
                Log.e("VvttsCore", "synth native crash", e)
                shortArrayOf()
            }
        }

       /** ECI voice params(voice=0 is the active voice) */
        @JvmStatic
        fun setVoiceParam(handle: Long, voice: Int, param: Int, value: Int): Int {
            return try {
                nativeSetVoiceParam(handle, voice, param, value)
            } catch (e: Throwable) {
                -1
            }
        }

        @JvmStatic
        fun getVoiceParam(handle: Long, voice: Int, param: Int): Int {
            return try {
                nativeGetVoiceParam(handle, voice, param)
            } catch (e: Throwable) {
                -1
            }
        }

       /** Engine ECIParam IDs: 2=text mode, 5=sample rate, 7=phoneme indices.
        * Pitch, speed and volume use setVoiceParam; see native/openevv/include/eci.h. */
        @JvmStatic
        fun setParam(handle: Long, param: Int, value: Int): Int {
            return try {
                nativeSetParam(handle, param, value)
            } catch (e: Throwable) {
                -1
            }
        }

       /** Switch to a standard voice */
        @JvmStatic
        fun setStandardVoice(handle: Long, voiceNumber: Int): Int {
            return try {
                nativeSetStandardVoice(handle, voiceNumber)
            } catch (e: Throwable) {
                -1
            }
        }

        /** Transfers ownership to native cleanup. The public ID is revoked;
         * active calls retain it until return and refused ECI deletes are retried
         * by the process cleanup queue, even after this Kotlin owner is gone. */
        @JvmStatic
        fun shutdown(handle: Long) {
            try {
                nativeShutdown(handle)
            } catch (e: Throwable) {
                // ignore
            }
        }

        @JvmStatic
        fun stop(handle: Long) {
            try {
                nativeStop(handle)
            } catch (e: Throwable) {
                // ignore
            }
        }
    }

       /** Stateless bridge class(default constructor kept for compat with old new VvttsCore() call sites) */
}
