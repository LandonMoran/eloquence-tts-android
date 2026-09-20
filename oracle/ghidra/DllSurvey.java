// DllSurvey.java — Ghidra headless survey for Eloquence-family rom DLLs.
// Usage: analyzeHeadless <proj> DllSurvey -import <file> -scriptPath <dir> -postScript DllSurvey.java -scriptlog <log>
// Outputs to stdout (captured via -scriptlog):
//   1) Section table (name, size, flags)
//   2) String inventory in rom-bearing sections (.rdata/.data/.m2e_*): count + first 40 samples
//   3) Top functions by number of references INTO the largest read-only section (rom probers)
//   4) Decompilation of those functions appended to <input>.survey.decomp.txt
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.*;
import ghidra.program.model.address.*;
import ghidra.program.model.symbol.*;
import ghidra.app.decompiler.*;
import java.io.*;
import java.util.*;

public class DllSurvey extends GhidraScript {
    @Override
    public void run() throws Exception {
        Program p = currentProgram;
        println("=== PROGRAM " + p.getName() + " arch=" + p.getLanguage().getLanguageID());

        // 1) sections
        MemoryBlock[] blks = p.getMemory().getBlocks();
        MemoryBlock largestRO = null;
        for (MemoryBlock b : blks) {
            String flags = (b.isRead() ? "R" : "") + (b.isWrite() ? "W" : "") + (b.isExecute() ? "X" : "");
            println(String.format("SECTION %-16s size=0x%x flags=%s init=%s", b.getName(), b.getSize(), flags, b.isInitialized()));
            String bn = b.getName().toLowerCase();
            boolean dataHunk = bn.contains("rdata") || bn.contains("const") || bn.contains("rodata") || bn.contains("cstring") || bn.contains("data.rel.ro");
            if (b.isRead() && !b.isWrite() && b.isInitialized() && dataHunk && (largestRO == null || b.getSize() > largestRO.getSize()))
                largestRO = b;
        }
        if (largestRO == null) {
            for (MemoryBlock b : blks) {
                String bn = b.getName().toLowerCase();
                boolean dataHunk = bn.contains("rdata") || bn.contains("const") || bn.contains("rodata") || bn.contains("cstring") || bn.contains("data.rel.ro");
                if (b.isRead() && !b.isWrite() && b.isInitialized() && dataHunk && (largestRO == null || b.getSize() > largestRO.getSize()))
                    largestRO = b;
            }
        }
        if (largestRO == null) {
            for (MemoryBlock b : blks) {
                if (b.isRead() && !b.isWrite() && b.isInitialized() && (largestRO == null || b.getSize() > largestRO.getSize()))
                    largestRO = b;
            }
        }

        // 2) strings in initialized non-exec sections
        if (largestRO != null) {
            Memory mem = p.getMemory();
            Address start = largestRO.getStart();
            long size = largestRO.getSize();
            StringBuilder cur = new StringBuilder();
            List<String> samples = new ArrayList<>();
            long strCount = 0;
            for (long i = 0; i < size; i++) {
                byte v = mem.getByte(start.add(i));
                if (v >= 0x20 && v < 0x7f) { cur.append((char) v); }
                else {
                    if (cur.length() >= 4) {
                        strCount++;
                        if (samples.size() < 40) samples.add(start.add(i - cur.length()) + " len=" + cur.length() + "  " + cur.substring(0, Math.min(cur.length(), 60)));
                    }
                    cur.setLength(0);
                }
            }
            println(String.format("ROSTRINGS section=%s count=%d (len>=4)", largestRO.getName(), strCount));
            for (String s : samples) println("  " + s);
        }

        // 3) functions referencing the largest RO block (rom probers)
                if (largestRO != null) {
                    Listing listing = p.getListing();
                    Map<Function, Integer> refcount = new LinkedHashMap<>();
                    ReferenceManager rm = p.getReferenceManager();
                    AddressSet roset = new AddressSet(largestRO.getStart(), largestRO.getEnd());
                    AddressIterator it = rm.getReferenceSourceIterator(roset, true);
                                        while (it.hasNext()) {
                                            Address from =it.next();
                        Function f = listing.getFunctionContaining(from);
                        if (f != null) {
                            refcount.put(f, refcount.getOrDefault(f, 0) + 1);
                        }
                    }
                    List<Map.Entry<Function, Integer>> sorted = new ArrayList<>(refcount.entrySet());
                    sorted.sort((a, b) -> b.getValue() - a.getValue());
                    println("=== PROBERS top-15 referencing " + largestRO.getName() + ":");
                    int shown = 0;
                    for (Map.Entry<Function, Integer> e : sorted) {
                        if (shown++ >= 15) break;
                        println("  " + e.getKey().getName() + " refs=" + e.getValue());
                    }
                    // 4) decompile them
                    DecompInterface dec = new DecompInterface();
                    dec.toggleCCode(true); dec.toggleSyntaxTree(true);
                    if (!dec.openProgram(p)) { println("DecompInterface failed: " + dec.getLastMessage()); return; }
                    File out = new File("/tmp/dllsurv/" + p.getName() + ".survey.decomp.txt");
                    PrintWriter w = new PrintWriter(new FileWriter(out));
                    shown =0;
                    for (Map.Entry<Function, Integer> e : sorted) {
                        if (shown++ >= 15) break;
                        Function f = e.getKey();
                if (f == null) continue;
                DecompileResults res = dec.decompileFunction(f, 60, monitor);
                w.println("// ===== " + e.getKey() + " refs=" + e.getValue());
                w.println(res.getDecompiledFunction() == null ? "// FAILED " + dec.getLastMessage() : res.getDecompiledFunction().getC());
                w.flush();
            }
            w.close();
            println("DECOMP wrote " + out.getAbsolutePath());
            dec.dispose();
        }
    }
}