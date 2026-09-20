// DecompileLptaV2: decompile apply_*/insert_*/if_*/test_* rule functions PLUS
// every helper they call (Ghidra auto-named FUN_*), and dump a REF map of
// each rule function's data references (address -> target label + bytes) so
// the transcription pass can resolve operand tables without re-running.
// Output: /tmp/decomp.txt  (log to scriptlog)
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;

public class DecompileLptaV2 extends GhidraScript {
    @Override
    public void run() throws Exception {
        DecompInterface dec = new DecompInterface();
        if (!dec.openProgram(currentProgram)) {
            println("DecompInterface failed: " + dec.getLastMessage());
            return;
        }
        dec.toggleCCode(true);
        dec.toggleSyntaxTree(true);
        dec.setSimplificationStyle("decompile");

        PrintWriter out = new PrintWriter(new FileWriter("/tmp/decomp.txt"));
        FunctionManager fm = currentProgram.getFunctionManager();
        Listing listing = currentProgram.getListing();
        Memory mem = currentProgram.getMemory();

        // pass 1: seed with every exported-ish rule function
        Set<Function> targets = new LinkedHashSet<>();
        List<Function> all = new ArrayList<>();
        for (Function f : fm.getFunctions(true)) { all.add(f); }
        for (Function f : all) {
            String n = f.getName();
            if (n.startsWith("apply_") || n.startsWith("if_test")
                    || n.startsWith("insert_") || n.startsWith("test_")
                    || n.startsWith("define_") || n.startsWith("push_")
                    || n.startsWith("get_") || n.startsWith("set_")
                    || n.equals("ventproc") || n.equals("vretproc")
                    || n.startsWith("fence") || n.startsWith("lpta_")) {
                targets.add(f);
            }
        }
        // pass 2: add every function called from the seeds (local helpers),
        // bounded to 2 rounds so the artifact stays the rule layer, not the
        // whole image.
        for (int round = 0; round < 2; round++) {
            List<Function> frontier = new ArrayList<>(targets);
            for (Function f : frontier) {
                for (Instruction insn : listing.getInstructions(f.getBody(), true)) {
                    Address[] flows = insn.getFlows();
                    if (flows == null) continue;
                    for (Address a : flows) {
                        Function callee = fm.getFunctionAt(a);
                        if (callee != null) targets.add(callee);
                    }
                }
            }
        }

        // PASS 3: data refs out of every seed (instruction operands referencing
        //         ro/data blocks): "<sourceaddr> REF <targetaddr> <first 32 bytes>"
        for (Function f : targets) {
            for (Instruction insn : listing.getInstructions(f.getBody(), true)) {
                for (Address ref : insn.getReferencesFrom()) {
                    MemoryBlock b = mem.getBlock(ref);
                    if (b == null || b.isExecute()) continue;
                    byte[] buf = new byte[0];
                    try {
                        long avail = b.getEnd().getOffset() - ref.getOffset() + 1;
                        int len = (int) Math.min(32, Math.max(0, avail));
                        if (len > 0) { buf = new byte[len]; mem.getBytes(ref, buf); }
                    } catch (Exception e) { buf = new byte[0]; }
                    out.println("REF " + insn.getAddress() + " " + ref + " "
                            + toHex(buf));
                }
            }
        }

        // PASS 4: decompile every seed
        int total = 0;
        for (Function f : targets) {
            out.println("=== " + f.getName() + " @ " + f.getEntryPoint() + " ===");
            try {
                String c = dec.decompileFunction(f, 120, monitor)
                        .getDecompiledFunction().getC();
                out.println(c);
            } catch (Exception e) {
                out.println("// decompile failed: " + e);
            }
            total++;
        }
        out.println("// decoded " + total + " functions; "
                + all.size() + " total in image");
        out.close();
        println("decompiled " + total + " functions -> /tmp/decomp.txt");
    }

    static String toHex(byte[] b) {
        if (b == null || b.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}