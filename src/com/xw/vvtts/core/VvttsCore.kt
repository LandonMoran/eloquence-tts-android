package com.xw.vvtts.core

import android.util.Log

/**
 * 自研 ECI 桥接（libvvtts_core.so）v3
 * 中英文各一个 session（dialect handle），角色由反引号 annotation 驱动。
 *
 * 所有 native 声明必须是 @JvmStatic（静态方法落在外层类上），
 * 否则 Kotlin 会把它编译进 Companion，JNI 符号名会变成
 * Java_com_xw_vvtts_core_VvttsCore_00024Companion_native*，C 桥接就找不到方法了。
 */
class VvttsCore {

    companion object {
        const val CHARSET_1252 = 0   // Windows-1252（西文）
        const val CHARSET_GBK = 6    // 中文 GB18030

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

        /** 为指定 dialect 创建 session（重复调用会重建） */
        @JvmStatic
        fun openEngine(configDir: String?, libDir: String?, dialect: Int): Long {
            return try {
                nativeInitEngine(configDir!!, libDir!!, dialect)
            } catch (e: Throwable) {
                Log.e("VvttsCore", "openEngine native crash", e)
                0
            }
        }

        /** 合成。textBytes 已编码。返回 PCM short 数组。 */
        @JvmStatic
        fun synth(handle: Long, dialect: Int, text: ByteArray, charsetId: Int, outPath: String?): ShortArray? {
            return try {
                nativeSynthesize(handle, dialect, text, charsetId, outPath)
            } catch (e: Throwable) {
                Log.e("VvttsCore", "synth native crash", e)
                null
            }
        }

        /** ECI 语音参数（voice=0 当前活动语音） */
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

        /** 引擎参数（2=音调 5=音量 7=语速） */
        @JvmStatic
        fun setParam(handle: Long, param: Int, value: Int): Int {
            return try {
                nativeSetParam(handle, param, value)
            } catch (e: Throwable) {
                -1
            }
        }

        /** 切换标准 voice */
        @JvmStatic
        fun setStandardVoice(handle: Long, voiceNumber: Int): Int {
            return try {
                nativeSetStandardVoice(handle, voiceNumber)
            } catch (e: Throwable) {
                -1
            }
        }

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

    /** 无状态桥接类（保留默认构造以兼容旧 new VvttsCore() 调用点） */
}