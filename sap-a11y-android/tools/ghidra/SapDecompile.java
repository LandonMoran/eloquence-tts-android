// Decompile selected il2cpp methods by metadata RVA.
//
// Il2CppDumper's dump.cs gives every method's RVA. Ghidra can disassemble from
// those addresses without a whole-binary auto-analysis of a 100 MB
// libil2cpp.so, which turns hours of work into minutes and keeps the output
// focused on the functions the mod actually calls.
//
// Usage (headless):
//   analyzeHeadless <proj> <name> -import libil2cpp.so -noanalysis \
//     -scriptPath tools/ghidra \
//     -postScript SapDecompile.java targets.tsv out/ -max-cpu 8
//
// Input TSV: <rva hex>\t<Class$$Method>\t<declaration>
// Output: one .c per method plus index.tsv (rva, name, file|status, decl).

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;

import ghidra.app.cmd.disassemble.DisassembleCommand;
import ghidra.app.cmd.function.CreateFunctionCmd;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.symbol.SourceType;

public class SapDecompile extends GhidraScript {

    private static String safe(String s) {
        return s.replace('<', '_').replace('>', '_').replace('/', '_').replace(' ', '_')
                .replace(':', '_').replace('`', '_');
    }

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 2) {
            println("usage: SapDecompile.java <targets.tsv> <outdir> [classFilter]");
            return;
        }
        File targets = new File(args[0]);
        File outDir = new File(args[1]);
        String classFilter = args.length > 2 ? args[2] : null;
        outDir.mkdirs();

        DecompInterface ifc = new DecompInterface();
        ifc.setOptions(new DecompileOptions());
        if (!ifc.openProgram(currentProgram)) {
            println("SapDecompile: decompiler failed to open program");
            return;
        }

        int total = 0, ok = 0, failed = 0, skipped = 0;
        long started = System.currentTimeMillis();
        PrintWriter index = new PrintWriter(new FileWriter(new File(outDir, "index.tsv")));
        try (BufferedReader reader = new BufferedReader(new FileReader(targets))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split("\t", 3);
                if (parts.length < 2) continue;
                String rva = parts[0].trim();
                String name = parts[1].trim();
                String decl = parts.length > 2 ? parts[2] : "";
                if (classFilter != null && !name.startsWith(classFilter + "$$")) continue;
                total++;

                Address addr;
                try {
                    addr = currentProgram.getImageBase().add(Long.decode(rva));
                } catch (Exception e) {
                    skipped++;
                    index.println(rva + "\t" + name + "\tBAD_ADDRESS\t" + decl);
                    continue;
                }
                try {
                    if (getInstructionAt(addr) == null && getDataAt(addr) == null) {
                        DisassembleCommand dis = new DisassembleCommand(addr, null, true);
                        dis.applyTo(currentProgram, monitor);
                    }
                    Function fn = getFunctionAt(addr);
                    if (fn == null) {
                        CreateFunctionCmd create = new CreateFunctionCmd(addr);
                        create.applyTo(currentProgram, monitor);
                        fn = getFunctionAt(addr);
                    }
                    if (fn == null) {
                        failed++;
                        index.println(rva + "\t" + name + "\tNO_FUNCTION\t" + decl);
                        continue;
                    }
                    try {
                        fn.setName(safe(name), SourceType.USER_DEFINED);
                    } catch (Exception e) {
                        // A leftover default name is fine; the file name still
                        // identifies the method.
                    }
                    DecompileResults res = ifc.decompileFunction(fn, 90, monitor);
                    if (!res.decompileCompleted()) {
                        failed++;
                        index.println(rva + "\t" + name + "\tDECOMPILE_FAILED\t" + decl);
                        continue;
                    }
                    String file = safe(name) + ".c";
                    try (PrintWriter w = new PrintWriter(new FileWriter(new File(outDir, file)))) {
                        w.println("// " + rva + " " + name);
                        w.println("// " + decl);
                        w.println(res.getDecompiledFunction().getC());
                    }
                    index.println(rva + "\t" + name + "\t" + file + "\t" + decl);
                    ok++;
                } catch (Exception e) {
                    failed++;
                    index.println(rva + "\t" + name + "\tERROR " + e.getMessage() + "\t" + decl);
                }
                if (total % 25 == 0) {
                    println("SapDecompile progress " + total + " ok=" + ok + " failed=" + failed
                            + " elapsed=" + ((System.currentTimeMillis() - started) / 1000) + "s");
                }
            }
        }
        index.close();
        ifc.dispose();
        println("SapDecompile done: " + ok + " decompiled, " + failed + " failed, " + skipped
                + " skipped, " + total + " targets in "
                + ((System.currentTimeMillis() - started) / 1000) + "s");
    }
}
