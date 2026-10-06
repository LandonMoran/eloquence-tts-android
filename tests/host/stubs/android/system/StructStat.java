package android.system;
public class StructStat {
    public long st_ino, st_size;
    /** Store host inode and size values using the fields read by production preferences. */
    public StructStat(long inode,long size) { st_ino=inode; st_size=size; }
}
