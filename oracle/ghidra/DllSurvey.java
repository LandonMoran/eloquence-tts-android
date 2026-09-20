// DllSurvey.java - Ghidra headless survey for Eloquence-family rom DLLs.
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
                                    // refs FROM executable code INTO the ro block
                                    AddressSet textSet = new AddressSet();
                                    for (MemoryBlock b : blks) {
                                        if (b.isExecute() && b.isInitialized()) { textSet.add(b.getStart()); textSet.add(b.getEnd().subtract(1)); }
                                    }
                                    if (textSet.isEmpty()) textSet = roset;
                                    int nF =0; int nHits =0;
                                    FunctionIterator fit = listing.getFunctions(true);
                                    while (fit.hasNext()) {
                                        Function fn = fit.next();
                                        nF++;
                                        int hits =0;
                                        InstructionIterator ins = listing.getInstructions(fn.getBody(), true);
                                        while (ins.hasNext()) {
                                            Instruction instr = ins.next();
                                            Reference[] refs = instr.getReferencesFrom();
                                            if (refs != null) {
                                                for (Reference r : refs) {
                                                    if (roset.contains(r.getToAddress())) hits++;
                                                }
                                            }
                                        }
                                        if (hits > 0) { refcount.put(fn, hits); nHits++; }
                                    }
                                    println("REFDEBUG funcs=" + nF + " withHits=" + nHits);
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

        // 5) raw byte dump of every rom/delta/const/rdata block (the tables themselves)
        PrintWriter bw = null;
        try {
            bw = new PrintWriter(new FileWriter("/tmp/dllsurv/" + p.getName() + ".survey.blocks.txt"));
            int nb = 0;
            long nbytes = 0;
            for (MemoryBlock b : blks) {
                String bn = b.getName().toLowerCase();
                boolean romHunk = bn.contains("rom") || bn.contains("delta") || bn.contains("rdata")
                        || bn.contains("const") || bn.contains("rodata") || bn.contains("cstring") || bn.contains("data.rel.ro");
                if (!romHunk || !b.isInitialized() || b.getSize() <= 0) continue;
                long cap = Math.min(b.getSize(), 2L *1024 *1024);
                byte[] data = new byte[(int) cap];
                int got = b.getBytes(b.getStart(), data);
                bw.println("=== block " + b.getName() + " va=" + b.getStart() + " size=" + b.getSize() + " ===");
                for (int i =0; i < got; i +=16) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(String.format("%08x ", b.getStart().getOffset() + i));
                    for (int j =i; j < Math.min(i +16, got); j++) sb.append(String.format("%02x ", data[j] & 0xff));
                    bw.println(sb.toString().trim());
                }
                nb++;
                nbytes += got;
            }
            bw.println("// blocks=" + nb + " bytes=" + nbytes);
            bw.close();
            println("BLOCKS wrote blocks=" + nb + " bytes=" + nbytes + " -> /tmp/dllsurv/" + p.getName() + ".survey.blocks.txt");
        } finally {
            if (bw != null) bw.close();
        }
    }
}