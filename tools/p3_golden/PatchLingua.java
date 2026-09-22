import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

/** * One-time bytecode surgery: replace LanguageDetector$Companion.loadLanguageModel's
 * Class.getResourceAsStream(path) call wither ElqmBridge.open(path), so the app
 * reads packed models.elqm instead of per-file JSON (with fallback inside the bridge).
 * Re-runs are no-ops: if the target code was already patched, the class is emitted
 * byte-identical.
 * Usage: javac -cp asm.jar PatchLingua.java && java -cp asm.jar:. PatchLingua
 * <input.jar> <output.jar> <ElqmBridge.class> */
public class PatchLingua {
    static final String TARGET = "com/github/pemistahl/lingua/api/LanguageDetector$Companion.class";
    static final String BRIDGE_CLASS = "com/github/pemistahl/lingua/internal/ElqmBridge.class";
    static final String METHOD_DESC = "(Lcom/github/pemistahl/lingua/api/Language;I)Lit/unimi/dsi/fastutil/objects/Object2FloatMap;";

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("usage: PatchLingua <in.jar> <out.jar> <ElqmBridge.class>");
        }
        File inFile = new File(args[0]);
        File outFile = new File(args[1]);
        byte[] bridge = readAll(new FileInputStream(args[2]));
        boolean patched = false;
        boolean droppedBridge = false;
        try (JarInputStream jin = new JarInputStream(new FileInputStream(inFile));
              JarOutputStream jout = new JarOutputStream(new FileOutputStream(outFile))) {
            JarEntry e;
            while ((e = jin.getNextJarEntry()) != null) {
                byte[] data = readAll(jin);
                if (e.getName().equals(TARGET)) {
                    PatchResult pr = patchClass(data);
                    data = pr.bytes;
                    patched = pr.patched;
                } else if (e.getName().equals(BRIDGE_CLASS)) {

                    droppedBridge = true;
                    continue;
                }
                jout.putNextEntry(e);
                jout.write(data);
                jout.closeEntry();
            }
            if (!patched) {
                throw new IllegalStateException("target method not found and not already patched");
            }
            if (droppedBridge) {
                throw new IllegalStateException("bridge class already present -- refusing to double-inject");
            }
            JarEntry be = new JarEntry(BRIDGE_CLASS);
            jout.putNextEntry(be);
            jout.write(bridge);
            jout.closeEntry();
        }
        System.out.println("patched: " + outFile);
    }


    static final class PatchResult {


        final byte[] bytes;
        final boolean patched;

        PatchResult(byte[] bytes, boolean patched) {
            this.bytes = bytes;
            this.patched = patched;
        }
    }

    static PatchResult patchClass(byte[] code) {
        ClassReader cr = new ClassReader(code);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
        boolean[] patched = {false};
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, exceptions);
                if (name.equals("loadLanguageModel") && desc.equals(METHOD_DESC)) {
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        boolean b1 = false;
                        boolean b2 = false;
                        boolean[] done = {false};

                        void flush() {
                            if (b1) {
                                super.visitLdcInsn(Type.getObjectType("com/github/pemistahl/lingua/api/Language"));
                                b1 = false;
                            }
                            if (b2) {
                                super.visitVarInsn(Opcodes.ALOAD, 4);
                                b2 = false;
                            }
                        }

                        boolean completesPattern() {

                            return b1 && b2 && !done[0];
                        }

                        @Override
                        public void visitLdcInsn(Object cst) {
                            if (!done[0] && cst instanceof Type
                                    && ((Type) cst).getSort() == Type.OBJECT
                                    && ((Type) cst).getClassName().equals("com.github.pemistahl.lingua.api.Language")) {
                                b1 = true;
                                return;
                            }
                            flush();
                            super.visitLdcInsn(cst);
                        }

                        @Override
                        public void visitVarInsn(int opcode, int var) {
                            if (!done[0] && b1 && opcode == Opcodes.ALOAD && var == 4) {
                                b2 = true;
                                return;
                            }
                            flush();
                            super.visitVarInsn(opcode, var);
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                            if (!done[0] && opcode == Opcodes.INVOKEVIRTUAL
                                    && owner.equals("java/lang/Class")
                                    && name.equals("getResourceAsStream")
                                    && desc.equals("(Ljava/lang/String;)Ljava/io/InputStream;")
                                    && b1 && b2) {
                                flush();
                                super.visitVarInsn(Opcodes.ALOAD, 4);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "com/github/pemistahl/lingua/internal/ElqmBridge",
                                        "open",
                                        "(Ljava/lang/String;)Ljava/io/InputStream;",
                                        false);
                                b1 = false;
                                b2 = false;
                                done[0] = true;
                                patched[0] = true;
                                return;
                            }
                            flush();
                            super.visitMethodInsn(opcode, owner, name, desc, itf);
                        }
                    };
                }
                return mv;
            }
        };
        cr.accept(cv, 0);
        boolean hasBridge = false;
        if (!patched[0]) {
            // Idempotent re-run: already-patched classes carry the bridge reference.
            hasBridge = containsUtf8(code, "ElqmBridge");
            if (!hasBridge) {
                throw new IllegalStateException("pattern not found");
            }
        }
        return new PatchResult(cw.toByteArray(), patched[0] || hasBridge);
    }

    static boolean containsUtf8(byte[] b, String s) {
        byte[] needle = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i + needle.length <= b.length; i++) {
            if (b[i] != needle[0]) continue;
            for (int j = 1; j < needle.length; j++) {
                if (b[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) {
            out.write(b, 0, n);
        }
        return out.toByteArray();
    }
}