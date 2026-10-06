package android.system;
import java.io.*;
import java.nio.file.*;
public class Os {
    public static volatile String failRenameContaining;
    /** Read the host file's inode and size for preference cache-invalidation tests. */
    public static StructStat stat(String path) throws IOException {
        Path p=Paths.get(path);
        return new StructStat(((Number)Files.getAttribute(p,"unix:ino")).longValue(),Files.size(p));
    }
    /** Atomically replace a host file, injecting failure for configured destination paths. */
    public static void rename(String from,String to) throws IOException {
        if(failRenameContaining!=null && to.contains(failRenameContaining)) throw new IOException("Injected rename failure");
        Files.move(Paths.get(from),Paths.get(to),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
}
