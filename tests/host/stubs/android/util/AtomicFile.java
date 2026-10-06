package android.util;
import java.io.*;
public class AtomicFile {
    private final File file;
    public AtomicFile(File file) { this.file=file; }
    public FileInputStream openRead() throws IOException {
        File backup=new File(file+".bak");
        if(backup.exists()) { file.delete(); if(!backup.renameTo(file)) throw new IOException("Restore failed"); }
        return new FileInputStream(file);
    }
}
