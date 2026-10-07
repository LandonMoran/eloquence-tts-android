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
    val text="!?".repeat(1024)+"!!!!!!!!"
    check(TextNormalizer.normalizeSymbols(text).endsWith("!!!!!!!!")) { "Literal post-budget punctuation lost" }
    println("PASS dictionary: compiled rule reuse/invalidation, case/order, BOM streaming, all limits, normalization budget")
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
            caller.submit { service.javaClass.getDeclaredMethod("onStop").apply { isAccessible=true }.invoke(service) }.get(1,TimeUnit.SECONDS)
            check(field(engine,"stopped")==true)
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
    for ((language,country) in listOf("deu" to "DEU", "jpn" to "JPN", "zho" to "CHN", "spa" to "MEX")) {
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

/** Run host regressions with temporary preference storage and remove it afterward. */
fun main() {
    Context.root=java.nio.file.Files.createTempDirectory("eloquence-prefs-test").toFile()
    try { preferences(); dictionary(); versionsAndVoices(); appliedVoiceParams(); lifecycle(); warmupRetirement(); warmupSerializationAndDestroy(); sampleActivity(); defender() }
    finally { Context.root.deleteRecursively() }
}
