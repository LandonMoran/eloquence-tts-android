# DllSurvey.py -- survey eloquence-family romanizer binaries.

# Prints to scriptlog. Writes <bin>.strings.txt (rom-block strings(

# and <bin>.survey.decomp.txt (C of top-15 prober functions(.

from ghidra.program.model.address import AddressSet

from ghidra.app.decompiler import DecompInterface

from ghidra.util.task import ConsoleTaskMonitor


def ro_name(b) : return str(b.getName()).lower()


def is_ro_data(b) :

    if not b.isRead() : return False

    if b.isWrite() : return False

    if not b.isInitialized() : return False

    bn = ro_name(b)

    return "rdata" in bn or "const" in bn or "rodata" in bn or "cstring" in bn or "data.rel.ro" in bn


def pick_ro_data(blks) :

    best = None

    best_size = -1

    for b in blks :

        if is_ro_data(b)and b.getSize() > best_size :

            best = b

            best_size = b.getSize()

    return best


def pick_largest(blks) :

    best = None

    best_size = -1

    for b in blks :

        if b.getSize() > best_size :

            best = b

            best_size = b.getSize()

    return best
p = getCurrentProgram()

print("PROGRAM " + p.getName() + " arch=" + p.getLanguage().getLanguageID())

blks = p.getMemory().getBlocks()

for b in blks :

    flags = ""

    if b.isRead() : flags += "R"

    if b.isWrite() : flags += "W"

    if b.isExecute() : flags += "X"

    print("SECTION %-16s size=0x%x flags=%s init=%s" % (ro_name(b) , b.getSize() , flags , b.isInitialized()) )

largestRO = pick_ro_data(blks)



if largestRO is None : largestRO = pick_largest(blks)



mem = p.getMemory()

start = largestRO.getStart()

size = largestRO.getSize()

cur = []

samples = []

str_count = 0

for i in xrange(int(size)) :

    v = mem.getByte(start.add(i))

    if 0x20 <= v < 0x7f : cur.append(chr(v))



    else :

        if len(cur) >= 4 :

            s = "".join(cur)

            str_count = str_count + 1

            if len(samples) < 8 : samples.append(s[:160])

        cur = []

strings_path = "/tmp/dllsurv/%s.strings.txt" % p.getName()

w = open(strings_path, "w")

w.write("# strings in %s (count=%d) samples:\n" % (largestRO.getName(), str_count))

for s in samples : w.write(s + "\n")

w.close()

print("STRINGS count=%d samples=%d -> %s" % (str_count , len(samples) , strings_path))

listing = p.getListing()

rm = p.getReferenceManager()

roset = AddressSet(largestRO.getStart(), largestRO.getEnd())

refcount = {}

it = rm.getReferenceIterator(roset)



while it.hasNext() :

    from_add = it.next().getFromAddress()

    f = listing.getFunctionContaining(from_add)





    if f is not None : refcount[f] = refcount.get(f, 0) + 1

print("=== PROBERS top-15 referencing %s:" % largestRO.getName())

items = sorted(refcount.items(), key=lambda kv : -kv[1])

for fobj , cnt in items[:15] : print("  %s refs=%d" % (fobj.getName() , cnt))

dec = DecompInterface()

dec.toggleCCode(True)

dec.toggleSyntaxTree(True)



if not dec.openProgram(p) :
    print("DecompInterface failed: " + dec.getLastMessage())

elif len(items) ==0 : print("DECOMP no probers found")

else :

    decomp_path = "/tmp/dllsurv/%s.survey.decomp.txt" % p.getName()

    out = open(decomp_path, "w")

    n =  0

    for fobj, cnt in items[:15] :

        res = dec.decompileFunction(fobj, 60, ConsoleTaskMonitor())

        if res is not None and res.decompileCompleted() :

            out.write("// ===== %s refs=%d\n%s\n" % (fobj.getName(), cnt, res.getDecompiledFunction().getC()))

            n = n + 1

        else : out.write("// ===== %s refs=%d  [decomp failed]\n" % (fobj.getName() , cnt))

    out.close()

    print("DECOMP wrote %d functions -> %s" % (n , decomp_path))

dec.dispose()
