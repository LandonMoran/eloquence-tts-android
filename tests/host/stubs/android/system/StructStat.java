package android.system;
public class StructStat {
    public long st_ino, st_size;
    public StructStat(long inode,long size) { st_ino=inode; st_size=size; }
}
