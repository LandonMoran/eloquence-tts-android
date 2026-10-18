package com.xw.vvtts.tests

import android.content.Context
import android.os.UserManager
import android.system.Os
import android.util.Xml
import com.xw.vvtts.engine.AppliedVoiceParams
import com.xw.vvtts.engine.EloquenceEngine
import com.xw.vvtts.engine.VoiceRegistry
import com.xw.vvtts.services.SynthesisBudget
import com.xw.vvtts.services.VvTtsService
import com.xw.vvtts.ui.SettingsActivity
import com.xw.vvtts.update.ElqUpdateChecker
import com.xw.vvtts.utils.*
import java.io.File
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Read a private instance field for host regression assertions. */
private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
/** Set a private instance field to arrange a host regression scenario. */
private fun set(target: Any, name: String, value: Any?) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target,value)
/** Assert that the supplied operation fails. */
private fun checkFails(block: () -> Unit) { check(runCatching(block).isFailure) }

/** Exercise mirrored persistence, Direct Boot, cache invalidation, rollback, and concurrent writers. */
private fun preferences() {
    val context = Context()
    val store = MirroredPreferences(context,"test")
    check(store.edit { it.putString("word","<&\"你好").putStringSet("langs",setOf("de","en")).putInt("rate",125) })
    check(store.getInt("rate",0)==125)
    check(store.read()["word"]=="<&\"你好")
    val parses = Xml.parses
    repeat(30) { check(store.getInt("rate",0)==125) }
    check(Xml.parses==parses) { "Valid XML was repeatedly parsed" }
    val ce = File(Context.root,"ce/shared_prefs/test.xml")
    val de = File(Context.root,"de/shared_prefs/test.xml")
    check(ce.readText()==de.readText())
    ce.writeText("<map><int")
    check(MirroredPreferences(context,"test").getInt("rate",0)==125) { "Malformed CE did not fall back" }
    check(store.edit { it.putInt("rate",130) })
    UserManager.unlocked=false
    Context.credentialUnavailable=true
    check(MirroredPreferences(context,"test").getInt("rate",0)==130)
    check(VoiceProfile(context.createDeviceProtectedStorageContext()).preset==1) { "Locked constructor crashed" }
    // Some Android contexts still throw from dataDir after UserManager reports unlocked.
    UserManager.unlocked=true
    check(MirroredPreferences(context,"test").getInt("rate",0)==130)
    check(!store.edit { it.putInt("rate",999) }) { "Inaccessible CE write should report failure" }
    Context.credentialUnavailable=false
    // Atomic rename failure leaves both stores' complete old values in place.
    Os.failRenameContaining="de/shared_prefs/test.xml"
    check(!store.edit { it.putInt("rate",999) })
    Os.failRenameContaining=null
    check(MirroredPreferences(context,"test").getInt("rate",0)==130)
    check(ce.readText()==de.readText())
    // Independent writers cannot roll back each other's keys.
    val pool=Executors.newFixedThreadPool(4)
    val jobs=(0..19).map { n -> pool.submit { MirroredPreferences(context,"test").edit { it.putInt("key$n",n) } } }
    jobs.forEach { it.get(5,TimeUnit.SECONDS) }; pool.shutdown()
    repeat(20) { check(store.getInt("key$it",-1)==it) }
    check(store.edit { it.clear() }); check(store.read().isEmpty())
    // Empty maps are cached too; malformed files never become authoritative.
    val emptyParses=Xml.parses; repeat(3) { check(store.read().isEmpty()) }; check(Xml.parses==emptyParses)
    val profileStore=MirroredPreferences(context,"vvtts_voice_profile")
    profileStore.edit { it.putInt("preset",999).putString("override_8_1","oops").putInt("override_8_2",200) }
    val profile=VoiceProfile(context)
    check(profile.preset==8)
    check(profile.getParam(8,1)==KonaVoice.byPreset(8).headSize)
    check(profile.getParam(8,2)==KonaVoice.byPreset(8).pitchBase)
    profileStore.edit { it.putInt("preset",0) }; check(profile.preset==1)
    val bootStore=MirroredPreferences(context,"boot-edits")
    check(bootStore.edit { it.putInt("value",1) })
    UserManager.unlocked=false; Context.credentialUnavailable=true
    check(bootStore.edit { it.putInt("value",2) })
    UserManager.unlocked=true; Context.credentialUnavailable=false
    val afterUnlock=MirroredPreferences(context,"boot-edits")
    check(afterUnlock.getInt("value",0)==2) { "Unlocked CE replaced a newer Direct Boot edit" }
    check(afterUnlock.edit { it.putInt("another",3) })
    check(afterUnlock.getInt("value",0)==2)
    check(File(Context.root,"ce/shared_prefs/boot-edits.xml").readText()==File(Context.root,"de/shared_prefs/boot-edits.xml").readText())
    check(!File(Context.root,"de/files/boot-edits.direct-boot-dirty").exists())
    val config=VoiceConfig(context)
    val configStore=MirroredPreferences(context,"vvtts_prefs")
    configStore.edit { it.putInt("number_processing_mode", 99) }
    check(config.numberModePref==4) { "Invalid number mode should clamp to the last supported choice" }
    configStore.edit { it.putInt("number_processing_mode", -1) }
    check(config.numberModePref==0) { "Negative number mode should clamp to the default choice" }
    config.setNumberModePref(2)
    check(config.numberModePref==2)
    config.setExtraLogging(false); check(!config.extraLogging)
    VoiceConfig(Context()).setExtraLogging(true); check(config.extraLogging)
    VoiceConfig(Context()).setExtraLogging(false); check(!config.extraLogging)
    println("PASS prefs: cache, complete empty XML, CE fallback, locked construction, atomic rollback, concurrent writers, corruption, live logging")
}

/** Exercise dictionary caching, strict import decoding, record bounds, and normalization limits. */
private fun dictionary() {
    val config=VoiceConfig(Context()); config.clearDict()
    check(config.addDictEntries(listOf(DictEntry("Foo","spoken",true),DictEntry("bar","other"))))
    val rules=config.compiledDictionary(); repeat(20) { check(config.compiledDictionary()===rules) }
    check(rules[0].first.containsMatchIn("Foo") && !rules[0].first.containsMatchIn("foo"))
    check(rules[1].first.containsMatchIn("BAR"))
    config.addDictEntry("new","entry"); check(config.compiledDictionary()!==rules)
    check(config.addDictEntry("line\rword","spoken\rvalue"))
    check(config.dictEntries().any { it.word=="line word" && it.spoken=="spoken value" })
    val before=config.dictEntries()
    checkFails { config.addDictEntries(listOf(DictEntry("x".repeat(129),"invalid"))) }
    check(config.dictEntries()==before)
    for (charset in listOf(Charsets.UTF_8,Charsets.UTF_16LE,Charsets.UTF_16BE)) {
        val bom=when(charset) { Charsets.UTF_16LE->byteArrayOf(-1,-2); Charsets.UTF_16BE->byteArrayOf(-2,-1); else->byteArrayOf(-17,-69,-65) }
        for (newline in listOf("\n", "\r\n", "\r")) {
            val text=listOf("word|replacement", "Foo|hello|cs", "bar,world", "").joinToString(newline)
            val entries=DictionaryImport.read((bom+text.toByteArray(charset)).inputStream())
            check(entries==listOf(DictEntry("Foo","hello",true),DictEntry("bar","world")))
        }
    }
    checkFails { DictionaryImport.read("x".repeat(1025).byteInputStream()) }
    checkFails { DictionaryImport.read("a|b\n".repeat(1001).byteInputStream()) }
    checkFails { DictionaryImport.read(("#"+"x".repeat(1000)+"\n").repeat(1100).byteInputStream()) }
    // Invalid UTF-8 and an incomplete UTF-16 code unit must not corrupt the dictionary.
    for (bytes in listOf(byteArrayOf(97,124,-61,40), byteArrayOf(-1,-2,97,0,124,0,98))) {
        var closed=false
        val input=object : java.io.ByteArrayInputStream(bytes) {
            /** Record stream closure so malformed-import tests can assert ownership cleanup. */
            override fun close() { closed=true; super.close() }
        }
        checkFails { DictionaryImport.read(input) }; check(closed)
    }
    println("PASS dictionary: compiled rule reuse/invalidation, case/order, BOM streaming, all limits")
}

/** Check version ordering, shipped voice lookup, pitch mapping, and synthesis budgeting. */
private fun versionsAndVoices() {
    check(ElqUpdateChecker.parseSemver("1.10.0")!! > ElqUpdateChecker.parseSemver("1.9.9")!!)
    check(ElqUpdateChecker.parseSemver("v1.0") == ElqUpdateChecker.parseSemver("V1.0.0"))
    check(ElqUpdateChecker.parseVersionCode("3000000000")==3000000000L)
    for (bad in listOf("1.1000.0","999999999999999999999999.1.0","1.2.3-beta","-1","r37"))
        check(ElqUpdateChecker.parseVersionCode(bad)==null)
    val marker = "<!-- android-version-code: 2000000003 -->"
    check(ElqUpdateChecker.releaseVersionCode("v1.0.2", marker)==2000000003L)
    check(ElqUpdateChecker.releaseVersionCode("v1.0.2", null)==null)
    check(ElqUpdateChecker.releaseVersionCode("3000000000", null)==3000000000L)
    for (bad in listOf(marker+marker, "<!-- android-version-code: 999999999999999999999 -->", "<!-- android-version-code: -1 -->"))
        check(ElqUpdateChecker.releaseVersionCode("v1.0.2", bad)==null)
    for (code in listOf(1999999999L,2000000002L))
        check(!ElqUpdateChecker.shouldOfferUpdate("1.0.1",2000000002L,"v1.0.2",code))
    check(ElqUpdateChecker.shouldOfferUpdate("1.0.1",2000000002L,"v1.0.2",2000000003L))
    check(!ElqUpdateChecker.shouldOfferUpdate("1.0.2",2000000002L,"v1.0.1",2000000003L))
    check(!ElqUpdateChecker.shouldOfferUpdate("1.0.2",2000000002L,"v1.0.2",2000000003L))
    check(ElqUpdateChecker.shouldOfferUpdate("legacy",2000000002L,"3000000000",3000000000L))
    check(ElqUpdateChecker.assetCandidates(listOf("x86")).isEmpty())
    check(ElqUpdateChecker.assetCandidates(listOf("x86_64"))==listOf("vvtts-universal.apk"))
    check(ElqUpdateChecker.assetCandidates(listOf("arm64-v8a")).first()=="vvtts-arm64-v8a.apk")
    for (voice in VoiceRegistry.voices) {
        check(VoiceRegistry.find(voice.locale.language,voice.locale.country)==voice)
        check(VoiceRegistry.find(voice.locale.isO3Language,voice.locale.isO3Country)==voice)
        check(VoiceRegistry.sample(voice).isNotBlank())
    }
    check(VoiceRegistry.find("kor")==null && VoiceRegistry.find("zh","TW")==null)
    check(VoiceRegistry.find("spa", "ARG")==null)
    check(VoiceRegistry.find("es", "AR")==null)
    check(VoiceRegistry.find("eng", "ZZZ")==null)
    check(VoiceRegistry.find("spa")?.voiceName=="es-ES")
    check(VoiceRegistry.find("spa", "")?.voiceName=="es-ES")
    check(VoiceRegistry.find("spa", "mex")?.voiceName=="es-MX")
    val engine=EloquenceEngine(Context())
    val pitch=engine.javaClass.getDeclaredMethod("mapUiPitchToKona",Int::class.java,Int::class.java).apply { isAccessible=true }
    check(KonaVoice.VOICES.map { it.nativeVoiceNumber } == listOf(1,2,3,4,6,7,8,5))
    val charset = EloquenceEngine.Companion.javaClass.getDeclaredMethod("charsetForDialect", Int::class.java).apply { isAccessible = true }
    check(charset.invoke(EloquenceEngine.Companion, EloquenceEngine.DIALECT_PL_PL) == Charsets.UTF_8)
    KonaVoice.VOICES.forEach { voice ->
        check(pitch.invoke(engine,0,voice.pitchBase)==0)
        check(pitch.invoke(engine,50,voice.pitchBase)==voice.pitchBase)
        check(pitch.invoke(engine,100,voice.pitchBase)==100)
    }
    println("PASS versions/voices: semantic ordering, Long codes, malformed bounds, audited ABI fallback, ISO-2/ISO-3, all preset pitch endpoints")
}

/** Verify cached voice parameters compare without retaining a mutable caller array. */
private fun appliedVoiceParams() {
    val cache = AppliedVoiceParams()
    val params = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    check(!cache.matches(1, 2, params))
    cache.record(1, 2, params)
    check(cache.matches(1, 2, params))
    params[0] = 9
    check(!cache.matches(1, 2, params))
    params[0] = 1
    check(!cache.matches(2, 2, params))
    check(!cache.matches(1, 3, params))
    cache.clear()
    check(!cache.matches(1, 2, params))
    println("PASS voice parameter cache: unchanged sets reuse safely; dialect, preset, values, and clear invalidate")
}

/** Keep unity-gain PCM allocation-free and preserve scaling/clamping at other levels. */
private fun volumeScaling() {
    val samples = shortArrayOf(Short.MIN_VALUE, -1000, 0, 1000, Short.MAX_VALUE)
    check(EloquenceEngine.applyVolume(samples, 100) === samples)
    check(EloquenceEngine.applyVolume(samples, 150) === samples)
    val half = EloquenceEngine.applyVolume(samples, 50)
    check(half !== samples)
    check(half.contentEquals(shortArrayOf(-16384, -500, 0, 500, 16383)))
    val muted = EloquenceEngine.applyVolume(samples, -1)
    check(muted.contentEquals(shortArrayOf(0, 0, 0, 0, 0)))
    println("PASS volume scaling: unity avoids copying; other gains clamp and preserve samples")
}

/** Check that compiled spoken exceptions preserve the existing replacements. */
private fun spokenExceptions() {
    val preprocess = EloquenceEngine.Companion.javaClass.getDeclaredMethod(
        "preprocess",
        String::class.java,
        Int::class.javaPrimitiveType,
        List::class.java,
        Boolean::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
    ).apply { isAccessible = true }
    val result = preprocess.invoke(
        EloquenceEngine.Companion,
        "CTRL GMAIL Tzsche",
        EloquenceEngine.DIALECT_EN_US,
        emptyList<Pair<Regex, String>>(),
        false,
        -1,
    )
    check(result == "control g mail tsche")
    println("PASS spoken exceptions: cached case-insensitive replacements preserve normalized output")
}

/** Exercise the merged picker’s persisted fixed-language selection without Android dialogs. */
private fun languagePickerSelection() {
    val previousFixed = LanguageDetector.getFixedDialect()
    val previousDefault = LanguageDetector.getDefaultLanguage()
    val previousDetection = LanguageDetector.isDetectionEnabled()
    val context = Context()
    val config = VoiceConfig(context)
    val activity = SettingsActivity()
    set(activity, "voiceConfig", config)
    val select = activity.javaClass.getDeclaredMethod("selectLanguage", String::class.java).apply {
        isAccessible = true
    }
    try {
        LanguageDetector.setDetectionEnabled(true)
        LanguageDetector.setDefaultLanguage(LanguageDetector.DIALECT_DE_DE)
        select.invoke(activity, "fr-CA")
        check(config.voice == "fr-CA")
        check(LanguageDetector.getFixedDialect() == LanguageDetector.DIALECT_FR_CA)
        check(LanguageDetector.getDefaultLanguage() == LanguageDetector.DIALECT_DE_DE) {
            "Selecting a fixed voice must preserve the independent detection fallback"
        }
        check(!LanguageDetector.isDetectionEnabled())
        val persisted = MirroredPreferences(context, "vvtts_lang_settings")
        check(persisted.getInt("fixed_dialect", -1) == LanguageDetector.DIALECT_FR_CA)
        check(persisted.getInt("default_language", -1) == LanguageDetector.DIALECT_DE_DE)
        check(!persisted.getBoolean("detection_enabled", true))

        LanguageDetector.setDetectionEnabled(true)
        check(LanguageDetector.getDefaultLanguage() == LanguageDetector.DIALECT_DE_DE) {
            "Re-enabling detection must restore the prior fallback language"
        }
        LanguageDetector.setDetectionEnabled(false)
        select.invoke(activity, "hi-IN")
        check(config.voice == "fr-CA") { "Unsupported dialect changed the selected language" }
        println("PASS language picker: selection persists and unshipped dialects are ignored")
    } finally {
        LanguageDetector.setFixedDialect(previousFixed)
        LanguageDetector.setDefaultLanguage(previousDefault)
        LanguageDetector.setDetectionEnabled(previousDetection)
    }
}

/** Exercise Telegram-style mixed-script, punctuation-heavy text through segmentation and chunk preprocessing. */
private fun telegramFormattedText() {
    val text = """
        Your install is v0.21.5 (2026.9.24) — tagged commit f97608f. Against today's main HEAD
        (8e85a0fd, Oct 6) the picture: ~800 files / ~740K insertions changed总体.
        Headline areas: desktop/TUI apps, local-runtime, llama.cpp v0.5.0, Termux packaging,
        telemetry v5, and update internals. See https://example.invalid/path?x=1&y=2.
        This includes `code`, **bold**, underscores_like_this, emojis 🙂, and repeated punctuation!!!!!
        Received at 10:56 PM — 1 of 91 somewhere around the middle.
    """.trimIndent()
    val chunks = VvTtsService.Companion.javaClass.getDeclaredMethod(
        "splitSynthChunks", String::class.java,
    ).apply { isAccessible = true }
    val preprocess = EloquenceEngine.Companion.javaClass.getDeclaredMethod(
        "preprocess",
        String::class.java,
        Int::class.javaPrimitiveType,
        List::class.java,
        Boolean::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
    ).apply { isAccessible = true }
    val segments = LanguageDetector.segment(text)
    check(segments.isNotEmpty())
    check(segments.joinToString("") { it.text } == text)
    for (segment in segments) {
        @Suppress("UNCHECKED_CAST")
        val pieces = chunks.invoke(VvTtsService.Companion, segment.text) as List<String>
        check(pieces.joinToString("") == segment.text)
        for (piece in pieces) {
            val expanded = EmojiExpander.expand(piece)?.takeIf { it.isNotEmpty() } ?: piece
            val prepared = preprocess.invoke(
                EloquenceEngine.Companion,
                expanded,
                segment.dialect,
                emptyList<Pair<Regex, String>>(),
                true,
                -1,
            ) as String
            val safe = CrashCodeDefender.sanitize(Context(), prepared)
            check(safe.isNotEmpty())
            check(safe.toByteArray(Charsets.UTF_8).size <= 16_384)
        }
    }
    println("PASS Telegram input: mixed script and punctuation segments, chunks, and preprocess safely")
}

/** Verify nonblocking stop, stale-audio rejection, listener teardown, and synthesis-only time budgeting. */
private fun lifecycle() {
    val engine=EloquenceEngine(Context())
    val service=VvTtsService(); set(service,"engine",engine)
    val method=engine.javaClass.getDeclaredMethod("synthWithTimeout",kotlin.jvm.functions.Function1::class.java).apply { isAccessible=true }
    val entered=CountDownLatch(1); val release=CountDownLatch(1)
    val caller=Executors.newFixedThreadPool(2)
    val pending=caller.submit<Any?> { method.invoke(engine, { _: Any -> entered.countDown(); release.await(); shortArrayOf(1) }) }
    check(entered.await(2,TimeUnit.SECONDS))
    // Hold both service locks while a real engine worker is blocked.
    synchronized(field(service,"synthesisLock")!!) {
        synchronized(field(service,"engineCallLock")!!) {
            val epoch=field(engine,"engineEpoch") as java.util.concurrent.atomic.AtomicLong
            val before=epoch.get()
            caller.submit { service.javaClass.getDeclaredMethod("onStop").apply { isAccessible=true }.invoke(service) }.get(1,TimeUnit.SECONDS)
            check(epoch.get()==before+1) { "Stop did not invalidate in-flight synthesis" }
        }
    }
    release.countDown(); check(pending.get(2,TimeUnit.SECONDS)==null) { "Stopped worker returned stale audio" }
    val register=service.javaClass.getDeclaredMethod("registerAllPrefsListeners",Context::class.java).apply { isAccessible=true }
    register.invoke(service,Context()); register.invoke(service,Context().createDeviceProtectedStorageContext())
    check(Context.listeners.values.sumOf { it.size }==6)
    service.onDestroy(); check(Context.listeners.values.all { it.isEmpty() })
    caller.shutdown()
    var now=0L; val budget=SynthesisBudget(12000) { now }
    budget.measure { now+=100 }; now+=60000 // one minute of paced playback
    check(!budget.exhausted)
    budget.measure { now+=11900 }; check(budget.exhausted)
    println("PASS lifecycle: blocked synthesis Stop, stale-result suppression, listener teardown, long-read work budget")
}

/** Verify queued and in-flight warmup cannot publish handles after worker retirement. */
private fun warmupRetirement() {
    val boundary = com.xw.vvtts.core.VvttsCore::class.java
    /** Read a native-boundary fixture counter. */
    fun count(name:String) = (boundary.getField(name).get(null) as java.util.concurrent.atomic.AtomicInteger).get()
    /** Read the engine's current synthesis worker for lifecycle assertions. */
    fun worker(engine:EloquenceEngine) = field(engine,"synthWorker")!!
    /** Read a worker's executor for deterministic queue barriers. */
    fun executor(worker:Any) = field(worker,"executor") as ExecutorService
    val engine=EloquenceEngine(Context()); set(engine,"initialized",true)
    val old=worker(engine); val gate=CountDownLatch(1); val entered=CountDownLatch(1)
    executor(old).execute { entered.countDown(); try { gate.await() } catch (_: InterruptedException) {} }
    check(entered.await(2,TimeUnit.SECONDS))
    val opens=count("opens"); engine.warmupDialect(EloquenceEngine.DIALECT_EN_US)
    engine.shutdown(); gate.countDown(); check(executor(old).awaitTermination(2,TimeUnit.SECONDS))
    check(count("opens")==opens) { "Queued warmup opened a retired handle" }
    // Shutdown while openEngine is in progress: the returning handle must be closed.
    set(engine,"initialized",true)
    val opening=CountDownLatch(1); val release=CountDownLatch(1)
    boundary.getField("entered").set(null,opening); boundary.getField("release").set(null,release)
    val old2=worker(engine); val closes=count("closes")
    engine.warmupDialect(EloquenceEngine.DIALECT_EN_US); check(opening.await(2,TimeUnit.SECONDS))
    engine.shutdown(); release.countDown(); check(executor(old2).awaitTermination(2,TimeUnit.SECONDS))
    check(count("closes")==closes+1 && (field(old2,"handles") as Map<*,*>).isEmpty())
    boundary.getField("entered").set(null,null); boundary.getField("release").set(null,null)
    check(engine.initialize())
    executor(worker(engine)).submit {}.get(2,TimeUnit.SECONDS)
    check(count("opens")==opens+2) { "Explicit reinitialization could not warm a fresh handle" }
    engine.shutdown()
    println("PASS warmup: queued retirement, shutdown during native open, explicit reinitialization")
}

/** Verify warmup serialization, destruction during synthesis, recreation, and shared engine ownership. */
private fun warmupSerializationAndDestroy() {
    val boundary=com.xw.vvtts.core.VvttsCore::class.java
    /** Read a native-boundary fixture counter for ownership assertions. */
    fun count(name:String)=(boundary.getField(name).get(null) as java.util.concurrent.atomic.AtomicInteger).get()
    val engine=EloquenceEngine(Context()); check(engine.initialize())
    val worker=field(engine,"synthWorker")!!
    val executor=field(worker,"executor") as ExecutorService
    executor.submit {}.get(2,TimeUnit.SECONDS)
    val opens=count("opens"); val closes=count("closes")
    val method=engine.javaClass.getDeclaredMethod("synthWithTimeout",kotlin.jvm.functions.Function1::class.java).apply { isAccessible=true }
    val entered=CountDownLatch(1); val release=CountDownLatch(1)
    val caller=Executors.newSingleThreadExecutor()
    val pending=caller.submit<Any?> { method.invoke(engine, { _:Any ->
        entered.countDown()
        while (true) { try { release.await(); break } catch (_:InterruptedException) {} }
        shortArrayOf(1)
    }) }
    check(entered.await(2,TimeUnit.SECONDS))
    engine.warmupDialect(EloquenceEngine.DIALECT_FR_FR)
    val barrier=executor.submit {}
    checkFails { barrier.get(100,TimeUnit.MILLISECONDS) }
    check(count("opens")==opens) { "Warmup overlapped synthesis" }
    val service=VvTtsService(); set(service,"engine",engine)
    service.onDestroy()
    check(!engine.isInitialized() && field(service,"engine")==null)
    check(count("closes")==closes) { "Teardown freed an active worker's handle" }
    check((field(engine,"retireExecutor") as ExecutorService).isShutdown)
    release.countDown(); check(pending.get(2,TimeUnit.SECONDS)==null)
    check(executor.awaitTermination(2,TimeUnit.SECONDS))
    check(count("closes")==closes+1)
    check((field(worker,"handles") as Map<*,*>).isEmpty())
    // Recreate in the same process and require a fresh usable engine.
    val replacement=EloquenceEngine(Context()); check(replacement.initialize())
    (field(field(replacement,"synthWorker")!!,"executor") as ExecutorService).submit {}.get(2,TimeUnit.SECONDS)
    check(count("opens")==opens+1)
    val recreated=VvTtsService(); set(recreated,"engine",replacement); recreated.onDestroy()
    check(count("closes")==closes+2)
    val acquire=VvTtsService.Companion.javaClass.getDeclaredMethod("acquireProcessEngine",Context::class.java).apply { isAccessible=true }
    val shared=acquire.invoke(VvTtsService.Companion,Context()) as EloquenceEngine
    check(acquire.invoke(VvTtsService.Companion,Context())===shared)
    val first=VvTtsService(); set(first,"engine",shared)
    val second=VvTtsService(); set(second,"engine",shared)
    first.onDestroy(); check(shared.isInitialized()) { "Overlapping service lost its engine" }
    second.onDestroy(); check(!shared.isInitialized())
    caller.shutdown()
    println("PASS ownership: warmup serialized, destroy during synthesis, native closure after return, service recreation")
}

/** Verify localized samples, Android result codes, and rejection of unsupported voice requests. */
private fun sampleActivity() {
    for ((language,country) in listOf("deu" to "DEU", "jpn" to "JPN", "fra" to "FRA", "spa" to "MEX")) {
        val activity=com.xw.vvtts.ui.GetSampleText()
        activity.intent=android.content.Intent().putExtra("language",language).putExtra("country",country)
        activity.javaClass.getDeclaredMethod("onCreate",android.os.Bundle::class.java).apply { isAccessible=true }.invoke(activity,null)
        check(activity.resultCode==android.speech.tts.TextToSpeech.LANG_AVAILABLE)
        check(activity.resultData.getStringExtra("sampleText")==VoiceRegistry.sample(VoiceRegistry.find(language,country)!!))
        check(activity.finished)
    }
    for (intent in listOf(android.content.Intent().putExtra("language","spa").putExtra("country","ARG"),
            android.content.Intent().putExtra("voiceName","missing"),
            android.content.Intent().putExtra("variant","unsupported"))) {
        val activity=com.xw.vvtts.ui.GetSampleText(); activity.intent=intent; activity.javaClass.getDeclaredMethod("onCreate",android.os.Bundle::class.java).apply { isAccessible=true }.invoke(activity,null)
        check(activity.resultCode==android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED)
        check(activity.resultData.getStringExtra("sampleText")==null)
    }
    println("PASS sample activity: Android Settings result contract, localized sample extra, unsupported requests")
}

/** Verify VoiceConfig setters report the mirrored-persistence result. */
private fun writeFailurePropagation() {
    val context = Context()
    val config = VoiceConfig(context)
    // Healthy store: setters return true (write succeeded).
    check(config.setVolume(97)) { "setVolume succeeds returns true" }
    check(config.setPitch(44)) { "setPitch succeeds returns true" }
    check(config.setVoice("de-DE")) { "setVoice succeeds returns true" }
    check(config.clearDict()) { "clearDict succeeds returns true" }
    // Force the atomic-rename to fail: the write must report failure (false),
    // and the previously stored values must remain intact.
    android.system.Os.failRenameContaining = "vvtts_prefs.xml"
    check(!config.setVolume(1)) { "setVolume fails when rename fails" }
    check(!config.setPitch(1)) { "setPitch fails when rename fails" }
    check(!config.setExtraLogging(true)) { "setExtraLogging fails when rename fails" }
    android.system.Os.failRenameContaining = null
    check(config.volume == 97) { "volume rollback after failed write" }
    check(config.pitch == 44) { "pitch rollback after failed write" }
    println("PASS write failure propagation: setters return the mirrored-write result and roll back cleanly")
}

/** Verify corpus bounds and protection against case, locale, and combining-mark bypasses. */
private fun defender() {
    val dictionary=CrashCodeDefender.readCorpus(java.io.StringReader("# fixture\nUNCOSP\nIHOSTILE\n"))
    check(dictionary==setOf("uncosp","ihostile"))
    checkFails { CrashCodeDefender.readCorpus(java.io.StringReader("x".repeat(2048))) }
    checkFails { CrashCodeDefender.readCorpus(java.io.StringReader("\n".repeat(200001))) }
    val oldLocale=java.util.Locale.getDefault()
    try {
        java.util.Locale.setDefault(java.util.Locale("tr","TR"))
        set(CrashCodeDefender,"corpus",dictionary); set(CrashCodeDefender,"loaded",true)
        val context=Context()
        for(word in listOf("UNCOSP","unco\u0301sp","IHOSTILE"))
            check(CrashCodeDefender.sanitize(context,word)!=word) { "Corpus bypass: $word" }
        check(CrashCodeDefender.sanitize(context,"ordinary speech") == "ordinary speech")
        java.util.zip.GZIPInputStream(File("assets/crashers.txt.gz").inputStream()).reader().use {
            check(CrashCodeDefender.readCorpus(it).isNotEmpty())
        }
    } finally {
        java.util.Locale.setDefault(oldLocale)
        set(CrashCodeDefender,"loaded",false); set(CrashCodeDefender,"corpus",emptySet<String>())
    }
    println("PASS defender: pre-allocation line bound, shipped corpus, combining marks, case and locale")
}

/** Verify the TalkBack dialect fix: onGetDefaultVoiceNameFor honors the pinned in-app voice. */
private fun talkbackDefaultVoiceDialect() {
    val previousFixed = LanguageDetector.getFixedDialect()
    val previousDefault = LanguageDetector.getDefaultLanguage()
    val previousDetection = LanguageDetector.isDetectionEnabled()
    val context = Context()
    val config = VoiceConfig(context)
    val service = VvTtsService()
    set(service, "voiceConfig", config)
    val resolve = service.javaClass.getDeclaredMethod(
        "onGetDefaultVoiceNameFor", String::class.java, String::class.java, String::class.java
    ).apply { isAccessible = true }
    fun resolveFor(lang: String?, country: String?): String =
        resolve.invoke(service, lang, country, "") as String
    try {
        // en-GB pinned in-app, framework requests the device locale (eng/USA):
        // the resolved default voice must be the app's pin, not the old en-US fallback.
        config.setVoice("en-GB")
        check(resolveFor("eng", "USA") == "en-GB") { "TalkBack requested eng/USA but got en-US despite en-GB pin" }
        check(resolveFor("en", "US") == "en-GB") { "en-US country request must still honor the en-GB pin" }
        check(resolveFor("eng", "GBR") == "en-GB")
        // Unsupported language family still falls through to the shipped default (no cross-family leak).
        check(resolveFor("de", "DE") == "de-DE")
        check(resolveFor("zz", "ZZ") == "en-US")
        // en-US pin must still resolve en-US (intentional choice preserved).
        config.setVoice("en-US")
        check(resolveFor("eng", "USA") == "en-US")
        check(resolveFor("en", "US") == "en-US")
        // Unpinned (default) resolves to the country-based en-US fallback as before.
        config.setVoice("fr-FR")
        check(resolveFor("eng", "USA") == "en-US") {
            "Non-English app voice must not leak into an English framework request"
        }
        // [review] Unset preference must NOT act as a pin: with no voice stored at all,
        // the country-based default applies (eng/GBR -> en-GB), not the implicit en-US.
        config.removeVoice()
        check(resolveFor("eng", "GBR") == "en-GB") {
            "Unset voice pref was treated as the implicit en-US pin; country default eng/GBR must resolve to en-GB"
        }
        check(resolveFor("eng", "USA") == "en-US")
        check(resolveFor("en", "GB") == "en-GB")
        // [review] A stored voice the registry does not advertise must never be returned
        // (setVoice does not validate; resolving it would make setLanguage report
        // LANG_NOT_SUPPORTED). Fall through to the shipped default instead.
        config.setVoice("en-ZZ")
        check(resolveFor("eng", "USA") == "en-US") {
            "Unsupported stored voice en-ZZ must not be returned for eng/USA; expected en-US fallback"
        }
        check(resolveFor("eng", "GBR") == "en-GB") {
            "Unsupported stored voice en-ZZ must not be returned for eng/GBR; expected en-GB country default"
        }
        println("PASS TalkBack default voice: pinned in-app dialect wins; other families and en-US pin preserved")
    } finally {
        config.setVoice("en-US")
        LanguageDetector.setFixedDialect(previousFixed)
        LanguageDetector.setDefaultLanguage(previousDefault)
        LanguageDetector.setDetectionEnabled(previousDetection)
    }
}

/** Verify telemetry consent defaults off (analytics), persists, migrates the
 *  legacy combined flag, and apply() is a safe no-op. (Performance Monitoring
 *  was removed; the only opt-in flag is analytics.) */
private fun telemetryConsent() {
    val context = Context()
    // Analytics default OFF (opt-in); crash defaults ON (opt-out).
    check(!TelemetryPrefs.isAnalyticsEnabled(context)) { "analytics telemetry must default OFF" }
    check(TelemetryPrefs.isCrashEnabled(context)) { "crash reporting must default ON (opt-out)" }
    // Applying consent in either state is a safe no-op on the host.
    Telemetry.apply(context)
    TelemetryPrefs.setAnalyticsEnabled(context, true)
    check(TelemetryPrefs.isAnalyticsEnabled(context)) { "analytics did not persist ON" }
    Telemetry.apply(context)
    TelemetryPrefs.setAnalyticsEnabled(context, false)
    check(!TelemetryPrefs.isAnalyticsEnabled(context)) { "telemetry consent did not persist OFF (analytics)" }
    // Crash opt-out: independent of the analytics opt-in, persists OFF, apply()
    // for the current process is a safe no-op, and toggling it must not touch
    // the analytics flag.
    TelemetryPrefs.setCrashEnabled(context, false)
    check(!TelemetryPrefs.isCrashEnabled(context)) { "crash opt-out did not persist OFF" }
    check(!TelemetryPrefs.isAnalyticsEnabled(context)) { "crash opt-out must be independent of analytics" }
    CrashlyticsFx.setCrashCollectionEnabled(false)
    TelemetryPrefs.setCrashEnabled(context, true)
    check(TelemetryPrefs.isCrashEnabled(context)) { "crash opt-out did not re-enable" }
    Telemetry.apply(context)
    // Review finding #4: the host gate cannot see a real device SDK method, so it
    // must surface the gap explicitly (queried + logged) rather than fail silently.
    check(!CrashlyticsFx.collectionToggleAvailable()) { "host stub must not expose the real SDK opt-out method (documented host gap)" }
    // Review finding #5: the removed FirePerf surface is fully gone — no manifest
    // meta-data, no stale "Performance"/"Share diagnostics" wording, no perf dep.
    val manifest = File("AndroidManifest.xml").readText()
    check(!manifest.contains("firebase_performance_collection_enabled")) { "manifest still ships removed FirePerf meta-data" }
    check(!manifest.contains("Share diagnostics")) { "manifest still uses legacy 'Share diagnostics' wording" }
    check(!manifest.contains("Firebase Performance")) { "manifest still references the removed Performance surface" }
    val gradle = File("build.gradle").readText()
    check(!gradle.contains("firebase-perf")) { "build.gradle still depends on the removed FirePerf artifact" }
    println("PASS telemetryConsent: analytics default off & persists; crash default on with functional opt-out; apply() safe no-op")
}

/** Review findings #1/#2/#8: EngineMetrics aggregates across processes without
 *  lost updates, records failed work, clears retained diagnostics on opt-out,
 *  and never uploads the opted-out window. */
private fun engineMetrics() {
    val context = Context()
    val store = MirroredPreferences(context, "vvtts_engine_metrics")
    TelemetryPrefs.setCrashEnabled(context, true)
    EngineMetrics.clear(context)

    // #1 Cross-process-safe merge: seed the shared store as if ANOTHER process
    // already merged its own count, then let THIS "process" record. The merged
    // value must be 5+1 — proving a later flush does not clobber the other
    // process's number (the pre-fix absolute-snapshot path would write 1).
    store.edit { it.putLong("init_n", 5L) }
    EngineMetrics.init(context)
    EngineMetrics.recordInit(50, true)
    check(store.getLong("init_n", -1L) == 6L) { "delta merge clobbered the other process's merged count" }
    check(store.getLong("init_ms", -1L) >= 50L) { "init duration was not merged" }

    // #8 Failed work is still recorded (caller passes ok=false).
    EngineMetrics.recordInit(40, false)
    check(store.getLong("init_fail", -1L) == 1L) { "failed init not counted in retained diagnostics" }

    // #2 Clear-on-opt-out: coincides with Settings clearing the snapshot.
    EngineMetrics.recordRotate()
    check(store.getLong("rotate", -1L) == 1L)
    EngineMetrics.clear(context)
    check(store.getLong("init_n", -1L) == 0L) { "clear-on-opt-out did not wipe retained diagnostics" }

    // #2 The opted-out window is never uploaded after re-enabling, even when later
    // records force an immediate flush of every outstanding delta.
    EngineMetrics.recordRotate()             // one event while opted IN
    TelemetryPrefs.setCrashEnabled(context, false)
    EngineMetrics.recordSynth(100)           // during the opted-out window -> must be dropped
    TelemetryPrefs.setCrashEnabled(context, true)
    EngineMetrics.recordSynth(200)           // re-enabled; hot-path record is throttled
    EngineMetrics.recordRotate()             // now=true flush of all pending deltas
    check(store.getLong("rotate", -1L) == 2L) { "rotate deltas (pre/post window) not merged" }
    check(store.getLong("synth_n", -1L) == 1L) { "opted-out-window diagnostics surfaced after re-enable" }
    check(store.getLong("synth_ms", -1L) >= 200L) { "re-enabled synthesis duration missing" }

    EngineMetrics.clear(context)
    println("PASS engineMetrics: cross-process merge safe; failed work counted; clear-on-opt-out; opted-out window not uploaded")
}

/** #1-interleave: an increment that lands between the delta snapshot and the
 *  baseline advance must stay outstanding for the next merge, not be dropped. */
private fun engineMetricsMergeWindow() {
    val context = Context()
    val store = MirroredPreferences(context, "vvtts_engine_metrics")
    val rotate = 9 // EngineMetrics.I_ROTATE
    TelemetryPrefs.setCrashEnabled(context, true)
    EngineMetrics.clear(context)
    EngineMetrics.init(context)

    // The seam fires inside mergeDeltas right after the delta snapshot and before
    // the baseline advances (the real race window is the disk I/O). Land a rotate
    // event in that exact instant.
    val startLocal = field(EngineMetrics, "local") as LongArray
    set(EngineMetrics, "mergeSeam") { startLocal[rotate]++ }

    try {
        // This merge has a real delta (init_n) so it proceeds past the early
        // return and reaches the seam, which bumps local[I_ROTATE] mid-merge.
        EngineMetrics.recordInit(10, true)
        val afterLocal = field(EngineMetrics, "local") as LongArray
        val afterBase = field(EngineMetrics, "baseline") as LongArray
        check(afterLocal[rotate] - afterBase[rotate] == 1L) { "mid-merge increment dropped (baseline advanced past it)" }
        check(store.getLong("rotate", -1L) == 0L) { "mid-merge increment prematurely merged" }
    } finally {
        set(EngineMetrics, "mergeSeam", null)
    }

    // The outstanding increment survives into the next forced flush.
    EngineMetrics.recordRotate()
    check(store.getLong("rotate", -1L) == 2L) {
        "mid-merge increment lost: expected 1 outstanding + 1 new = 2, got " + store.getLong("rotate", -1L)
    }

    EngineMetrics.clear(context)
    println("PASS engineMetricsMergeWindow: increment landing during the merge window stays outstanding")
}

/** #2-cross-process: an idle process holding pre-opt-out deltas must discard them
 *  when the sibling process clears on opt-out — never merge them after re-enable
 *  into the fresh window. */
private fun engineMetricsCrossProcessClear() {
    val context = Context()
    val store = MirroredPreferences(context, "vvtts_engine_metrics")
    TelemetryPrefs.setCrashEnabled(context, true)
    EngineMetrics.clear(context)
    EngineMetrics.init(context) // cachedGeneration now reflects the cleared store

    // Hold an UNMERGED pre-opt-out delta: synth is throttled (now=false), so a
    // single record stays outstanding in this "process".
    EngineMetrics.recordSynth(10) // local[synth_n]=1, baseline=0 -> outstanding 1, not merged
    check(store.getLong("synth_n", -1L) == 0L) { "precondition: synth delta should still be outstanding/unmerged" }

    // Simulate the MAIN process opting out: it clear()s the shared snapshot and
    // bumps the generation. Model it directly on the store (NOT via
    // EngineMetrics.clear, which would also zero OUR local — the main process's
    // clear never touches the :tts process's in-memory counters).
    val genBefore = store.getLong("metrics_generation", 0L)
    store.update { map ->
        map["synth_n"] = 0L; map["synth_ms"] = 0L; map["synth_max"] = 0L; map["rotate"] = 0L
        map["metrics_generation"] = genBefore + 1L
    }

    // Re-enable: the :tts "process" records again, forcing an immediate flush.
    // The generation bump must make it DISCARD the pre-opt-out synth delta.
    EngineMetrics.recordRotate() // now=true -> immediate merge
    check(store.getLong("synth_n", -1L) == 0L) {
        "pre-opt-out delta leaked into the fresh window after re-enable: synth_n=" + store.getLong("synth_n", -1L)
    }

    // A subsequent record merges normally into the fresh, post-opt-out window.
    EngineMetrics.recordRotate()
    check(store.getLong("rotate", -1L) == 1L) { "fresh post-opt-out window failed to merge" }

    EngineMetrics.clear(context)
    println("PASS engineMetricsCrossProcessClear: idle process discards pre-opt-out deltas on sibling clear")
}

/** #3-check-then-act: generation must be validated INSIDE the merge transaction,
 *  not just in the pre-check. If the sibling clears after the pre-check passes
 *  but before the deltas are written, the merge must abort and discard them. */
private fun engineMetricsCrossProcessClearCheckThenAct() {
    val context = Context()
    val store = MirroredPreferences(context, "vvtts_engine_metrics")
    TelemetryPrefs.setCrashEnabled(context, true)
    EngineMetrics.clear(context)
    EngineMetrics.init(context) // cachedGeneration now reflects the cleared store

    // Pre-opt-out delta held UNMERGED (synth is throttled, so a single record
    // stays outstanding in this "process").
    EngineMetrics.recordSynth(10)
    check(store.getLong("synth_n", -1L) == 0L) { "precondition: synth delta should still be outstanding/unmerged" }

    // Arm the seam to simulate a sibling clear landing AFTER the generation
    // pre-check but BEFORE the merge transaction (the check-then-act window).
    // Like the real clear(): zero the counters and bump the generation.
    val genBefore = store.getLong("metrics_generation", 0L)
    set(EngineMetrics, "mergeSeam") {
        store.update { map ->
            map["synth_n"] = 0L; map["synth_ms"] = 0L; map["synth_max"] = 0L; map["rotate"] = 0L
            map["metrics_generation"] = genBefore + 1L
        }
    }
    try {
        // Force a merge that carries the pre-opt-out synth delta (rotate is
        // immediate). The pre-check sees generation==cached (it passes); the seam
        // then bumps the generation before the write. Only an in-transaction
        // validation can catch this, so the batch must be aborted and discarded.
        EngineMetrics.recordRotate() // now=true -> immediate merge
        check(store.getLong("synth_n", -1L) == 0L) {
            "pre-opt-out delta leaked into fresh window via check-then-act race: synth_n=" + store.getLong("synth_n", -1L)
        }
        check(store.getLong("rotate", -1L) == 0L) {
            "merge transaction applied despite generation changing mid-flight: rotate=" + store.getLong("rotate", -1L)
        }
    } finally {
        set(EngineMetrics, "mergeSeam", null)
    }

    // After the abort the process resynced to the cleared store; a brand-new
    // post-opt-out record merges normally.
    EngineMetrics.recordRotate()
    check(store.getLong("rotate", -1L) == 1L) { "fresh post-opt-out window failed to merge after abort" }

    EngineMetrics.clear(context)
    println("PASS engineMetricsCrossProcessClearCheckThenAct: gen validated in-transaction aborts a mid-flight sibling clear")
}

/** Verify CrashlyticsFx initializes per-process without throwing and never breaks the host. */
private fun crashInit() {
    val context = Context()
    // The per-process bootstrap must be a safe no-op on the host (and never
    // throw when Firebase is absent / the process is a test runner).
    CrashlyticsFx.initProcess(context)
    CrashlyticsFx.setContext("x86_64")
    CrashlyticsFx.record("host:crashInit", RuntimeException("host-only"))
    CrashlyticsFx.br("host:crashInit")
    // The Application subclass must be registry-able and not crash on onCreate.
    val app = com.xw.vvtts.VvttsApp()
    app.onCreate()
    // Manifest must reference the Application subclass so it runs in every process.
    val manifest = File("AndroidManifest.xml").readText()
    check(manifest.contains("android:name=\".VvttsApp\"")) { "Manifest missing Application subclass" }
    check(manifest.contains(":tts")) { "Manifest missing :tts engine process" }
    println("PASS crashInit: per-process init is a safe no-op; Application wired for every process")
}

/** Review #1: a failed consent read (prefs store / file-lock) must be swallowed
 *  and fail closed — it must never throw out of the public record*() paths that
 *  run in synthesis/shutdown finally blocks, and nothing may be persisted.
 *  Deterministic driver: the consentReadSeam throws on the next read. */
private fun engineMetricsFailClosedOnConsentRead() {
    val context = Context()
    val store = MirroredPreferences(context, "vvtts_engine_metrics")
    TelemetryPrefs.setCrashEnabled(context, true)
    EngineMetrics.clear(context)
    EngineMetrics.init(context)
    check(store.getLong("synth_n", -1L) == 0L)
    // Simulate the prefs store / file lock being unreadable at flush time.
    set(EngineMetrics, "consentReadSeam") { throw IllegalStateException("prefs file lock unavailable") }
    try {
        var threw = false
        try { EngineMetrics.recordSynth(12L) } catch (t: Throwable) { threw = true }
        check(!threw) { "consent-read failure must NOT throw out of recordSynth — telemetry broke the engine result path" }
        // Fail closed: the failed consent read dropped the delta, leaving the
        // shared aggregate untouched.
        check(store.getLong("synth_n", -1L) == 0L) { "fail-closed consent read must not persist/(attach) diagnostics" }
    } finally {
        set(EngineMetrics, "consentReadSeam", null)
    }
    EngineMetrics.clear(context)
    TelemetryPrefs.setCrashEnabled(context, true)
    println("PASS engineMetricsFailClosedOnConsentRead: consent-read failure swallowed, nothing persisted, engine result path intact")
}

/** Reviews #2 & #3: a synthesis request is classified into EXACTLY ONE final
 *  outcome, through the single classifier the service now drives.
 *  - #2: a watchdog timeout is RC_TIMEOUT — never double-counted as RC_FAIL.
 *  - #3: success is only RC_OK once the completion callback actually returned;
 *        a failing done() is RC_FAIL, never RC_OK. */
private fun finalOutcomeIsExactlyOne() {
    check(EngineMetrics.finalOutcome(interrupted=false, timedOut=false, failed=false, ok=true) == EngineMetrics.RC_OK)
    // #3: done() failed -> FAIL, never OK.
    check(EngineMetrics.finalOutcome(interrupted=false, timedOut=false, failed=true, ok=true) == EngineMetrics.RC_FAIL)
    // #2: timeout -> RC_TIMEOUT, never RC_FAIL, regardless of the generic failed flag.
    check(EngineMetrics.finalOutcome(interrupted=false, timedOut=true, failed=true, ok=false) == EngineMetrics.RC_TIMEOUT)
    check(EngineMetrics.finalOutcome(interrupted=false, timedOut=true, failed=false, ok=false) == EngineMetrics.RC_TIMEOUT)
    // Fail-closed: no explicit success disposition is not a success.
    check(EngineMetrics.finalOutcome(interrupted=false, timedOut=false, failed=false, ok=false) == EngineMetrics.RC_FAIL)
    // Interruption wins over everything — still exactly one bucket.
    check(EngineMetrics.finalOutcome(interrupted=true, timedOut=true, failed=true, ok=true) == EngineMetrics.RC_INTERRUPTED)
    check(EngineMetrics.finalOutcome(interrupted=true, timedOut=false, failed=false, ok=false) == EngineMetrics.RC_INTERRUPTED)
    println("PASS finalOutcomeIsExactlyOne: timeout==RC_TIMEOUT (not also RC_FAIL); failing done()==RC_FAIL (not RC_OK); one code per request")
}

/** Review #5, missing-method path: when the SDK has no
 *  setCrashlyticsCollectionEnabled method, an opted-out process must still emit
 *  NO Crashlytics keys/logs/exceptions (fail closed) — even though the toggle
 *  could not be applied and the SDK may still be live underneath. */
private fun crashOptOutWritesNothingMissingToggle() {
    val context = Context()
    TelemetryPrefs.setCrashEnabled(context, false) // opt out
    val seen = mutableListOf<String>()
    set(CrashlyticsFx, "writeProbe") { label: String -> seen.add(label) }
    try {
        CrashlyticsFx.initProcess(context)
        CrashlyticsFx.setContext("x86_64")
        CrashlyticsFx.setKey("probe", "v")
        CrashlyticsFx.record("p", RuntimeException("x"))
        CrashlyticsFx.br("p")
        // The host stub lacks the toggle method (documented gap) -> NOT applied.
        check(!CrashlyticsFx.collectionToggleAvailable())
        check(seen.isEmpty()) { "opted-out process wrote Crashlytics data although the disable toggle is missing (fail-closed violated): $seen" }
    } finally {
        set(CrashlyticsFx, "writeProbe", null)
        set(CrashlyticsFx, "writesAllowed", true)
        TelemetryPrefs.setCrashEnabled(context, true)
    }
    println("PASS crashOptOutWritesNothingMissingToggle: opted-out process emits nothing when disable toggle is missing")
}

/** Review #5, invocation-failure path: even when setCollectionEnabled's
 *  reflection call throws (toggle not applied), an opted-out process emits
 *  nothing. Deterministic driver: toggleSeam throws, mirroring a real
 *  InvocationTargetException inside the reflection call. */
private fun crashOptOutWritesNothingInvocationFailure() {
    val context = Context()
    TelemetryPrefs.setCrashEnabled(context, false) // opt out
    val seen = mutableListOf<String>()
    set(CrashlyticsFx, "toggleSeam") { throw java.lang.reflect.InvocationTargetException(RuntimeException("remote toggle failed")) }
    set(CrashlyticsFx, "writeProbe") { label: String -> seen.add(label) }
    try {
        CrashlyticsFx.initProcess(context)
        CrashlyticsFx.setContext("x86_64")
        CrashlyticsFx.record("p", RuntimeException("x"))
        CrashlyticsFx.br("p")
        check(seen.isEmpty()) { "opted-out process wrote Crashlytics data although the toggle invocation FAILED (fail-closed violated): $seen" }
    } finally {
        set(CrashlyticsFx, "toggleSeam", null)
        set(CrashlyticsFx, "writeProbe", null)
        set(CrashlyticsFx, "writesAllowed", true)
        TelemetryPrefs.setCrashEnabled(context, true)
    }
    println("PASS crashOptOutWritesNothingInvocationFailure: opted-out process emits nothing when disable toggle invocation fails")
}

/** Review #6: a FAILED persistent diagnostic wipe must be reported and must
 *  never let pre-opt-out counts resurrect on a later re-enable. Deterministic
 *  driver: clearWriteSeam returns false every attempt, so clear() reports
 *  failure while the old aggregate remains on disk; the engine must then merge
 *  new opt-in data onto a ZERO base (fail closed), not the stale retained
 *  values. Fails on the old code: there the wipe result was ignored, clear()
 *  reported a success it could not guarantee, and a re-enable resurrected the
 *  old counts on top of fresh data. */
private fun engineMetricsFailedClearNoResurrection() {
    val context = Context()
    val store = MirroredPreferences(context, "vvtts_engine_metrics")
    TelemetryPrefs.setCrashEnabled(context, true) // opt-in for this exercise
    EngineMetrics.clear(context)
    EngineMetrics.init(context)
    // Build a retained aggregate (the user opted in and used the engine).
    EngineMetrics.recordInit(10L, true) // coarse -> immediate merge (now=true)
    EngineMetrics.recordInit(20L, true)
    check(store.getLong("init_n", -1) == 2L) { "precondition: retained init_n=2 on disk" }

    // Opt OUT; the wipe fails to persist on every attempt (deterministic seam).
    TelemetryPrefs.setCrashEnabled(context, false)
    set(EngineMetrics, "clearWriteSeam") { false }
    val cleared = try { EngineMetrics.clear(context) } finally { set(EngineMetrics, "clearWriteSeam", null) }
    check(!cleared) { "clear() must report a failed persistent wipe (review #6), not claim success" }
    check(store.getLong("init_n", -1) == 2L) { "a failed wipe leaves the old retained aggregate on disk" }

    // Re-enable. A fresh opt-in delta must merge onto a ZERO base — the old
    // pre-opt-out count (2) must never be added on top (old code: 2+1=3).
    TelemetryPrefs.setCrashEnabled(context, true)
    EngineMetrics.recordInit(50L, true)
    check(store.getLong("init_n", -1) == 1L) { "pre-opt-out count resurrected after failed clear (review #6): got ${store.getLong("init_n", -1)}" }
    EngineMetrics.clear(context)
    TelemetryPrefs.setCrashEnabled(context, true)
    println("PASS engineMetricsFailedClearNoResurrection: failed wipe reported; retained counts never resurrected on re-enable")
}

/** Review #7: a failed opt-out write must be reported (never claimed saved),
 *  the durable value must be unchanged, and a healthy opt-out must survive a
 *  process restart (fresh prefs re-read). Deterministic: the host's
 *  Os.failRenameContaining stub makes the atomic rename fail. */
private fun telemetryConsentFailedOptOutPersist() {
    val context = Context()
    TelemetryPrefs.setCrashEnabled(context, true) // default opt-out ON
    check(TelemetryPrefs.isCrashEnabled(context)) { "crash reporting should default on (opt-out)" }
    // Force the atomic rename for the telemetry prefs to fail (review #7).
    android.system.Os.failRenameContaining = "vvtts_telemetry.xml"
    val persisted = try {
        TelemetryPrefs.setCrashEnabled(context, false) // opt-OUT write fails
    } finally { android.system.Os.failRenameContaining = null }
    check(!persisted) { "setCrashEnabled MUST report a failed opt-out write (false); caller fails closed (review #7)" }
    // Durable value unchanged: a hypothetical restart reads the old (default ON)
    // value, which is exactly why the in-process fail-closed path must hold.
    check(TelemetryPrefs.isCrashEnabled(context)) { "durable crash consent must be unchanged when its write failed" }
    // A healthy opt-out must survive a process restart (fresh prefs re-read).
    check(TelemetryPrefs.setCrashEnabled(context, false)) { "a healthy opt-out write should succeed" }
    check(!TelemetryPrefs.isCrashEnabled(context)) { "opted-out consent must survive a process restart (fresh read)" }
    TelemetryPrefs.setCrashEnabled(context, true)
    println("PASS telemetryConsentFailedOptOutPersist: failed opt-out write reported; durable value unchanged; good write survives restart")
}

/** Reviews #9 & #11: the runtime write gate must follow consent IMMEDIATELY when
 *  the user toggles Crash reporting from Settings (not just at process init), and
 *  the SDK-toggle result must be REPORTED so the switch never lies. Deterministic:
 *  opt in, init, then toggle OFF mid-session. The host stub lacks the toggle
 *  method (documented gap), so setCrashCollectionEnabled(false) must REPORT that
 *  the SDK toggle was NOT applied (returns false — review #11 surfaces it) yet
 *  must STILL suppress further writes via the synced gate (review #9). Old code
 *  returned Unit and never touched the gate, so an opted-in running process kept
 *  emitting after a runtime opt-out — writes flow again here, which fails. */
private fun crashRuntimeToggleSyncsWriteGateAndReportsResult() {
    val context = Context()
    TelemetryPrefs.setCrashEnabled(context, true) // opted in
    val seen = mutableListOf<String>()
    set(CrashlyticsFx, "writeProbe") { label: String -> seen.add(label) }
    try {
        CrashlyticsFx.initProcess(context) // writesAllowed=true (consent ON)
        // Sanity: an opted-in process may write Crashlytics data.
        CrashlyticsFx.setKey("probe", "v")
        check(seen.isNotEmpty()) { "precondition: opted-in process should be able to write before the toggle" }
        seen.clear()

        // Toggle OFF at RUNTIME. Stub lacks the toggle method -> SDK toggle cannot
        // be applied: must be REPORTED (review #11) but the app gate must STILL
        // flip so nothing more is emitted (review #9).
        val applied = CrashlyticsFx.setCrashCollectionEnabled(false)
        check(!applied) { "SDK toggle could not be applied on this stub; setCrashCollectionEnabled must report it (review #11)" }
        // A running process that just opted out must emit NOTHING now.
        CrashlyticsFx.setKey("probe2", "v")
        CrashlyticsFx.record("p", RuntimeException("x"))
        CrashlyticsFx.br("p")
        check(seen.isEmpty()) { "write gate did not follow a RUNTIME opt-out (review #9): $seen" }

        // Opt back IN at runtime: writes resume.
        CrashlyticsFx.setCrashCollectionEnabled(true)
        CrashlyticsFx.setKey("probe3", "v")
        check(seen.size == 1) { "write gate did not re-enable on a RUNTIME opt-in (review #9): $seen" }
    } finally {
        set(CrashlyticsFx, "writeProbe", null)
        set(CrashlyticsFx, "writesAllowed", true)
        TelemetryPrefs.setCrashEnabled(context, true)
    }
    println("PASS crashRuntimeToggleSyncsWriteGateAndReportsResult: runtime consent change flips the write gate and reports the SDK-toggle result")
}

/** Review #10 (producer side): when a clear's atomic wipe FAILS, the engine must
 *  still persist a DURABLE marker + a generation bump so sibling processes can
 *  detect the consent change, and a later successful clear must clear the marker
 *  (self-heal). Old code made the failure process-local, so a sibling could never
 *  learn the wipe failed. Deterministic driver: clearWriteSeam returns false, so
 *  the wipe cannot land; the fallback marker write (not seam-gated) must. */
private fun engineMetricsFailedClearPersistsMarker() {
    val context = Context()
    val store = MirroredPreferences(context, "vvtts_engine_metrics")
    TelemetryPrefs.setCrashEnabled(context, true)
    EngineMetrics.clear(context)
    EngineMetrics.init(context)
    EngineMetrics.recordInit(10L, true) // stale retained aggregate init_n=1 on disk
    check(store.getLong("init_n", -1) == 1L) { "precondition: stale retained init_n=1 on disk" }
    val genBefore = store.getLong("metrics_generation", 0L)

    set(EngineMetrics, "clearWriteSeam") { false }
    val cleared = try { EngineMetrics.clear(context) } finally { set(EngineMetrics, "clearWriteSeam", null) }
    check(!cleared) { "clear() must still report the failed wipe (review #6)" }
    check(store.getBoolean("metrics_clear_failed", false)) { "failed clear must persist a DURABLE marker so siblings detect it (review #10)" }
    check(store.getLong("metrics_generation", -1) == genBefore + 1L) { "failed clear must still bump the shared generation (review #10)" }
    check(store.getLong("init_n", -1) == 1L) { "the failed wipe leaves the stale aggregate on disk (unchanged)" }

    // Self-heal: the NEXT unobstructed clear clears the marker.
    check(EngineMetrics.clear(context)) { "an unobstructed clear should succeed" }
    check(!store.getBoolean("metrics_clear_failed", true)) { "successful clear must clear the failed-clear marker (self-heal, review #10)" }

    EngineMetrics.clear(context)
    TelemetryPrefs.setCrashEnabled(context, true)
    println("PASS engineMetricsFailedClearPersistsMarker: failed clear persists durable marker + gen bump; a later clear self-heals")
}

/** Review #10 (consumer / cross-process side): a SIBLING process that never saw
 *  the failed wipe must not resurrect retained data once the durable marker is
 *  set. The stale aggregate stays on disk (the wipe failed), so alone the
 *  sibling's snapshot still holds pre-opt-out counts; the marker must make it
 *  (a) drop its own pre-opt-out delta on the generation bump, and (b) merge fresh
 *  data and attach crash keys against a ZERO base under the marker. Deterministic
 *  driver: write the durable artifacts a REAL failed clear (with the fix) leaves
 *  on the shared store, standing in for a sibling engine process, then observe
 *  this process. Fails on old code (no durable marker -> no zero base): after
 *  re-enable this sibling merges init onto the stale 2 (3) and attaches the stale
 *  count, both checked here. */
private fun engineMetricsFailedClearCrossProcessNoResurrection() {
    val context = Context()
    val store = MirroredPreferences(context, "vvtts_engine_metrics")
    TelemetryPrefs.setCrashEnabled(context, true)
    EngineMetrics.clear(context)
    EngineMetrics.init(context)
    // This sibling (B) merged a retained aggregate (opted-in usage) -> init_n=2.
    EngineMetrics.recordInit(10L, true)
    EngineMetrics.recordInit(20L, true)
    check(store.getLong("init_n", -1) == 2L) { "precondition: retained init_n=2 on disk" }
    // B still holds a pre-opt-out delta UNMERGED (synth is throttle-gated).
    EngineMetrics.recordSynth(10)
    check(store.getLong("synth_n", -1) == 0L) { "precondition: synth delta should be outstanding/unmerged" }

    // A's failed clear (with the fix) leaves these DURABLE artifacts on the shared
    // store: a marker + generation bump, but the atomic wipe did NOT zero the
    // aggregate. Write exactly those results the way a real A (engine process)
    // would — the host runs one singleton per test, and calling clear() here would
    // also zero B's in-memory arrays, defeating the cross-process setup.
    val genBefore = store.getLong("metrics_generation", 0L)
    store.update { map ->
        map["metrics_clear_failed"] = true
        map["metrics_generation"] = genBefore + 1L
    }
    check(store.getLong("init_n", -1) == 2L) { "the failed wipe leaves the stale aggregate on disk (unchanged)" }

    // Re-enable (user toggled reporting back on in the other process).
    TelemetryPrefs.setCrashEnabled(context, true)

    // Observe every key ATTACH and (below) the disk after merges. With the marker
    // folded in, B must present the retained aggregate as ZERO on the attach path.
    val seen = mutableListOf<String>()
    set(CrashlyticsFx, "writeProbe") { label: String -> seen.add(label) }
    try {
        // B's first post-enable rotate: generation changed -> DISCARD the pre-opt-out
        // synth delta rather than fold it into the fresh window.
        EngineMetrics.recordRotate()
        check(store.getLong("synth_n", -1) == 0L) { "pre-opt-out delta leaked into fresh window after failed cross-process wipe (review #10)" }

        // Second rotate (fresh activity, no outstanding pre-class delta): the marker
        // must make B merge onto a ZERO base (not the stale 2) and ATTACH zero —
        // the retained count must never resurrect on either path.
        EngineMetrics.recordRotate()
        check(store.getLong("rotate", -1) == 1L) { "fresh post-opt-out delta should merge after failed cross-process wipe (review #10)" }
        check(seen.any { it == "key:m_init_n=0" }) { "stale retained aggregate attached after failed cross-process wipe (review #10): $seen" }
        check(seen.none { it.startsWith("key:m_init_n=") && it != "key:m_init_n=0" }) { "retained init_n attached after failed cross-process wipe (review #10): $seen" }

        // Fresh NEW init measurement after re-enable must merge onto ZERO (not
        // 2+1). This is the write-side resurrection guard, and is what fails on
        // old code (no durable marker -> clearWriteFailed stays false -> base =
        // the stale 2 -> 3).
        EngineMetrics.recordInit(100L, true)
        check(store.getLong("init_n", -1) == 1L) { "pre-opt-out count resurrected on a fresh init after failed cross-process wipe (review #10): got ${store.getLong("init_n", -1)}" }
    } finally {
        set(CrashlyticsFx, "writeProbe", null)
        set(CrashlyticsFx, "writesAllowed", true)
    }

    EngineMetrics.clear(context)
    TelemetryPrefs.setCrashEnabled(context, true)
    println("PASS engineMetricsFailedClearCrossProcessNoResurrection: sibling honors durable failed-clear marker — drops pre-opt-out delta, masks/zero-bases the retained aggregate, and never resurrects it")
}

/** Review #12: a failed analytics opt-out must not be silently ignored. The
 *  consent toggle is persisted AND applied to the live GA4 SDK, failing closed —
 *  an OFF write that cannot be hardened to disk still disables GA4 for this
 *  process, the non-durable state is reported to the caller, and the durable
 *  consent is left unchanged so a relaunch is honest. Old code discarded the
 *  durable result and re-read the stale value, leaving GA4 live while the switch
 *  read OFF. Deterministic driver: Os.failRenameContaining on the telemetry
 *  prefs forces the OFF write to fail; Telemetry.sdkProbe observes every GA4
 *  collection-change it is asked to apply. */
private fun telemetryAnalyticsFailedOptOutFailClosed() {
    val context = Context()
    TelemetryPrefs.setAnalyticsEnabled(context, true)
    check(TelemetryPrefs.isAnalyticsEnabled(context)) { "analytics should start on" }
    val seen = mutableListOf<Boolean>()
    set(Telemetry, "sdkProbe") { on: Boolean -> seen.add(on) }
    try {
        android.system.Os.failRenameContaining = "vvtts_telemetry.xml"
        val durable = try { Telemetry.setConsent(context, false) }
            finally { android.system.Os.failRenameContaining = null }
        check(!durable) { "review #12: failed OFF write must be reported (not claimed saved)" }
        check(TelemetryPrefs.isAnalyticsEnabled(context)) { "review #12: durable analytics consent unchanged when OFF write failed" }
        check(seen.contains(false)) { "review #12: GA4 must be disabled in-process after a failed analytics opt-out (fail closed)" }
        // A healthy toggle on a relaunch applies the durable value (parity check).
        check(Telemetry.setConsent(context, true))
        check(seen.last() == true) { "review #12: re-enable must re-enable the live SDK" }
    } finally { set(Telemetry, "sdkProbe", null) }
    TelemetryPrefs.setAnalyticsEnabled(context, true)
    println("PASS telemetryAnalyticsFailedOptOutFailClosed: failed analytics opt-out disables GA4 in-process and is reported")
}

/** Review round 6: a consent toggle must report success ONLY when BOTH the
 *  durable preference AND the live SDK actually applied it. The #12 fix proved
 *  only the call was attempted (sdkProbe); this closes the remaining gap where
 *  an OFF preference can save while the live GA4 toggle fails/throws — silently
 *  claiming the opt-out is effective while GA4 stays live. Old code returned
 *  Unit from setEnabled and had setConsent return true whenever the preference
 *  write succeeded, so this gap was invisible. Deterministic driver: sdkApplyProbe
 *  replaces the real SDK outcome (either refusing, or throwing like an SDK init
 *  failure); asserts setConsent returns false, the durable OFF is saved, the
 *  non-effective state is exported for surfacing, and a retry now that the SDK
 *  accepts reapplies OFF and succeeds. */
private fun telemetryAnalyticsSdkApplyFailureFailClosed() {
    val context = Context()
    TelemetryPrefs.setAnalyticsEnabled(context, true)
    check(TelemetryPrefs.isAnalyticsEnabled(context)) { "analytics should start on" }
    val seen = mutableListOf<Boolean>()
    set(Telemetry, "sdkProbe") { on: Boolean -> seen.add(on) }
    try {
        // (a) SDK refuses OFF (e.g. init failed, toggle no-op): durable OFF saves
        //     but live is not confirmed off -> must NOT be reported as effective.
        set(Telemetry, "sdkApplyProbe") { on: Boolean -> on } // accepts ON, refuses OFF
        val effective = Telemetry.setConsent(context, false)
        check(!effective) { "round 6: OFF with a failed live SDK apply must return false (got $effective)" }
        check(!TelemetryPrefs.isAnalyticsEnabled(context)) { "round 6: durable OFF must still be saved" }
        check(seen.contains(false)) { "round 6: the OFF intent must reach the SDK path" }
        // Saved-but-not-applied: caller surfaces "not effective this session", retry-able.
        // (b) Retry as soon as the SDK accepts: reapply OFF and confirm effective.
        set(Telemetry, "sdkApplyProbe", null) // real SDK path (host stub accepts)
        check(Telemetry.setConsent(context, false)) { "round 6: retry must reapply OFF and be reported effective" }
        // (c) SDK init throws: the throw is contained (never breaks the app) and
        //     everything follows the fail-closed path.
        set(Telemetry, "sdkApplyProbe") { _: Boolean -> throw RuntimeException("sdk init failed") }
        check(!Telemetry.setEnabled(context, false)) { "round 6: a throwing SDK apply must return false, not propagate" }
        check(!Telemetry.setConsent(context, false)) { "round 6: OFF with an SDK init throw must not claim success" }
    } finally {
        set(Telemetry, "sdkProbe", null)
        set(Telemetry, "sdkApplyProbe", null)
    }
    TelemetryPrefs.setAnalyticsEnabled(context, true)
    println("PASS telemetryAnalyticsSdkApplyFailureFailClosed: setConsent succeeds only when durable + live both land; SDK apply/init failure is contained and surfaced")
}

/** Review #13: the worst-case failed clear — BOTH the atomic wipe and the
 *  durable failure-marker write fail — must be reported (clear() returns false
 *  AND flags the store unhealthy via clearStoreUnhealthy()) so Settings never
 *  claims the purge succeeded, and the flag must reset on the next successful
 *  clear. Old code ignored persistClearFailed's outcome, so the double failure
 *  was indistinguishable and the user (and Settings handler) were told nothing.
 *  Deterministic driver: clearWriteSeam returns false (wipe can't land) plus
 *  Os.failRenameContaining on the engine-metrics store (marker write can't land). */
private fun engineMetricsFailedClearDoubleFailure() {
    val context = Context()
    val store = MirroredPreferences(context, "vvtts_engine_metrics")
    TelemetryPrefs.setCrashEnabled(context, true)
    check(EngineMetrics.clear(context)) { "review #13: healthy baseline clear should succeed" }
    check(!EngineMetrics.clearStoreUnhealthy()) { "review #13: healthy baseline must not flag the store unhealthy" }
    EngineMetrics.init(context)
    store.update { map -> map["init_n"] = 9L } // retained pre-opt-out count
    TelemetryPrefs.setCrashEnabled(context, false)
    set(EngineMetrics, "clearWriteSeam") { false } // wipe can't land
    android.system.Os.failRenameContaining = "vvtts_engine_metrics.xml" // marker can't land
    val cleared = try { EngineMetrics.clear(context) }
        finally { set(EngineMetrics, "clearWriteSeam", null); android.system.Os.failRenameContaining = null }
    check(!cleared) { "review #13: clear() must report failure when the wipe cannot land" }
    check(EngineMetrics.clearStoreUnhealthy()) { "review #13: double failure (wipe+marker) must flag the store unhealthy" }
    check(store.getLong("init_n", -1L) == 9L) { "review #13: a failed wipe leaves the stale aggregate" }
    // The next successful clear must reset the unhealthy flag.
    check(EngineMetrics.clear(context))
    check(!EngineMetrics.clearStoreUnhealthy()) { "review #13: a successful clear must reset the unhealthy flag" }
    TelemetryPrefs.setCrashEnabled(context, true)
    println("PASS engineMetricsFailedClearDoubleFailure: wipe+marker failure is reported and flags the store unhealthy; reset on success")
}

/** Review #13 sibling / re-enable side: when BOTH the aggregate wipe and the
 *  durable failure-marker write fail, NOTHING lands on the shared store — no
 *  marker and no generation bump. A re-enabled sibling therefore has no signal to
 *  drop its still-held pre-opt-out deltas, so it DOES fold them onto the stale
 *  aggregate: resurrection is possible whenever persistence is unavailable. This
 *  is the honest, narrowed promise (review #13, option "narrow the promise"): the
 *  system must NEVER claim old diagnostics can't reappear in that case — instead
 *  the clearing process flags the store unhealthy (engineMetricsFailedClearDoubleFailure)
 *  and Settings surfaces it. This test pins that we do NOT rely on (or promise) a
 *  durable protection that does not exist under a double failure. Deterministic:
 *  simulate the durable outcome a real double-failed clear leaves — nothing — then
 *  drive this process as the sibling re-enabling. */
private fun engineMetricsFailedClearDoubleFailureSibling() {
    val context = Context()
    val store = MirroredPreferences(context, "vvtts_engine_metrics")
    TelemetryPrefs.setCrashEnabled(context, true)
    EngineMetrics.clear(context)
    EngineMetrics.init(context)
    // Sibling (B) merged a retained aggregate (opted-in usage) -> init_n=2.
    EngineMetrics.recordInit(10L, true)
    EngineMetrics.recordInit(20L, true)
    // B still holds a pre-opt-out synth delta UNMERGED (synth is throttle-gated).
    EngineMetrics.recordSynth(10)
    check(store.getLong("init_n", -1L) == 2L) { "precondition: retained init_n=2 on disk" }
    check(store.getLong("synth_n", -1L) == 0L) { "precondition: synth delta should be outstanding/unmerged" }
    // A's double-failed clear leaves NO durable artifacts (that IS the honest
    // outcome — nothing landed), so we write nothing here.
    check(!store.getBoolean("metrics_clear_failed", false)) { "review #13: double failure must leave NO durable marker on the shared store" }

    // Re-enable reporting in the sibling.
    TelemetryPrefs.setCrashEnabled(context, true)

    // Sibling rotates and re-measures: with no marker and no gen bump, its
    // pre-opt-out synth delta folds onto the fresh window and a fresh init merges
    // onto the stale 2 (=> 3). Persistence-unavailable resurrection is real and is
    // surfaced (unhealthy flag + Settings), never silently claimed prevented.
    EngineMetrics.recordRotate()
    check(store.getLong("synth_n", -1L) == 1L) { "review #13: without a durable marker a re-enabled sibling folds its pre-opt-out delta (resurrection when persistence is unavailable)" }
    EngineMetrics.recordInit(100L, true)
    check(store.getLong("init_n", -1L) == 3L) { "review #13: without a durable marker a fresh init merges onto the stale retained aggregate (got ${store.getLong("init_n", -1L)})" }

    EngineMetrics.clear(context)
    TelemetryPrefs.setCrashEnabled(context, true)
    println("PASS engineMetricsFailedClearDoubleFailureSibling: double failure leaves no durable marker; resurrection is possible and surfaced, never silently claimed prevented")
}

/** Review #14: crash reporting must be fail-closed at cold start. The manifest
 *  must set firebase_crashlytics_collection_enabled=false so the SDK does not
 *  capture anything during the pre-create window — before Application.onCreate /
 *  CrashlyticsFx.initProcess() reads and applies the stored consent. Fails on
 *  the old manifest, which carried no such metadata: there the SDK's own
 *  (enabled) default governed that window, so an opted-out launch could be
 *  captured. Also guards the analytics default stays disabled alongside it. */
private fun crashlyticsColdStartFailClosed() {
    val m = File("AndroidManifest.xml").readText()
    check(m.contains("firebase_crashlytics_collection_enabled")) { "review #14: manifest must declare firebase_crashlytics_collection_enabled" }
    val crash = m.substringAfter("firebase_crashlytics_collection_enabled")
    check(crash.contains("value=\"false\"")) { "review #14: cold-start Crashlytics collection default must be false" }
    check(m.contains("firebase_analytics_collection_enabled") && m.substringAfter("firebase_analytics_collection_enabled").contains("value=\"false\"")) { "review #14: analytics cold-start default must remain disabled" }
    println("PASS crashlyticsColdStartFailClosed: manifest disables Crashlytics collection at cold start until consent is applied")
}

/** Run host regressions with temporary preference storage and remove it afterward. */
fun main() {
    Context.root=java.nio.file.Files.createTempDirectory("eloquence-prefs-test").toFile()
try { preferences(); dictionary(); versionsAndVoices(); appliedVoiceParams(); volumeScaling(); spokenExceptions(); languagePickerSelection(); telegramFormattedText(); writeFailurePropagation(); lifecycle(); warmupRetirement(); warmupSerializationAndDestroy(); sampleActivity(); talkbackDefaultVoiceDialect(); defender(); crashInit(); telemetryConsent(); engineMetrics(); engineMetricsMergeWindow(); engineMetricsCrossProcessClear(); engineMetricsCrossProcessClearCheckThenAct(); engineMetricsFailClosedOnConsentRead(); finalOutcomeIsExactlyOne(); crashOptOutWritesNothingMissingToggle(); crashOptOutWritesNothingInvocationFailure(); engineMetricsFailedClearNoResurrection(); telemetryConsentFailedOptOutPersist(); crashRuntimeToggleSyncsWriteGateAndReportsResult(); engineMetricsFailedClearPersistsMarker(); engineMetricsFailedClearCrossProcessNoResurrection(); telemetryAnalyticsFailedOptOutFailClosed(); telemetryAnalyticsSdkApplyFailureFailClosed(); engineMetricsFailedClearDoubleFailure(); engineMetricsFailedClearDoubleFailureSibling(); crashlyticsColdStartFailClosed() }
    finally { Context.root.deleteRecursively() }
}
