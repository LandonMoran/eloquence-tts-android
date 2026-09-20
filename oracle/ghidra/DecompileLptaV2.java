// DecompileLptaV2: v1's body plus a REF map dump (no helper closure).
// Output: /tmp/decomp.txt  (log to scriptlog)
import java.io.FileWriter;
import java.io.PrintWriter;

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
        int total = 0;
        for (Function f : fm.getFunctions(true)) {
            String n = f.getName();
            if (n.startsWith("apply_") || n.equals("test_string_s")
                    || n.equals("insert_2pt") || n.startsWith("insert_")
                    || n.startsWith("test") || n.startsWith("if_test")) {
                out.println("=== " + n + " @ " + f.getEntryPoint() + " ===");
                try {
                    String c = dec.decompileFunction(f, 60, monitor)
                            .getDecompiledFunction().getC();
                    out.println(c);
                } catch (Exception e) {
                    out.println("// decompile failed: " + e);
                }
                // REF map: data operands of this rule function
                for (Instruction insn : listing.getInstructions(f.getBody(), true)) {
                    for (Address ref : insn.getReferencesFrom()) {
                        MemoryBlock b = mem.getBlock(ref);
                        if (b == null || b.isExecute()) continue;
                        byte[] buf = new byte[0];
                        try {
                            long avail = b.getEnd().getOffset() - ref.getOffset() + 1;
                            int len = (int) Math.min(24, Math.max(0, avail));
                            if (len > 0) { buf = new byte[len]; mem.getBytes(ref, buf); }
                        } catch (Exception e) { buf = new byte[0]; }
                        out.println("REF " + insn.getAddress() + " " + ref + " " + hex(buf));
                    }
                }
                total++;
            }
        }
        out.println("// decoded " + total + " functions");
        out.close();
        println("decompiled " + total + " functions -> /tmp/decomp.txt");
    }

    static String hex(byte[] b) {
        if (b == null || b.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}