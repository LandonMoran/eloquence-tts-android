// DecompileLptaV2: dump C for all apply_*_rules + interpreter helpers of an
// Apple Eloquence lang .so (kor/chs/cht/jpn). Output: /tmp/decomp.txt
// Run headless: analyzeHeadless <proj> <name> -import <lib.so> \
//   -scriptPath <this dir> -postScript DecompileLptaV2.java -deleteProject
import java.io.FileWriter;
import java.io.PrintWriter;

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;

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
                total++;
            }
        }
        out.println("// decoded " + total + " functions");
        out.close();
        println("decompiled " + total + " functions -> /tmp/decomp.txt");
    }
}