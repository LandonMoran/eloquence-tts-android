# IBM LPTA Rule Interpreter - chs.so - Per-Statement Specification

Target: `/root/.scratch_cjk/apple-eloquence-elf-1.2.3-linux-x86_64/lib/chs.so`
(ELF-translated IBM Eloquence Mandarin speech module; file offsets == VAs.)
Method: static disassembly only (`objdump -d`, `readelf`, python struct reads). The
binary was never executed. All disassembly quotes below were read from the file.

Sections:
1. Architecture summary
2. vstmtbl (44 statement descriptors, 0xbb320)
3. Statement stream: operand encoding and widths
4. The driver `convert_to_lowercase` (0x2e154..0x2fb2c) and its 57 inlined rule blocks
5. Core driver helpers (starttest, lpta_loadp_setscan_l/r, lpta_rpta_loadp,
   test_string_s, mark_s, forto_adv_r, vback)
6. Phone emission (statement code 2): vins_tok / vinitflds write mechanism
7. Rule-table termination logic
8. apply_chi_*_rules and the "e" rule table at 0xb9fea
9. Appendix: evidence index (disassembly ranges, key lines, data dumps)

---

## 1. Architecture summary

The LPTA engine in chs.so is a letter-to-phoneme rule matcher whose *rule tables
are byte streams in .m2e_data* (e.g. 0xb915d..0xb9195, 0xb9fcf..0xba001), but whose
*interpreter is compiled out*: each rule of a table is emitted as straight-line
machine code in .m2e_text (the blocks below), and when a table grows a
backtracking "retry" loop (vback + jump table) is used for the tail rules
(apply_chi_e_rules at 0x777ef). There is no data-driven PC loop over the stream:
the operand addresses (e.g. `lea 0xb9160(%rip),%rcx`) are compile-time constants.

The statement vocabulary is the 44-entry table `vstmtbl` at 0xbb320 (0x60 bytes
per entry). Codes 0-8 are the core operations (char_count, inp, phone, morph,
word, inton_phr, klatt, syllable, Ms); codes 9-43 are phone-feature variable
statements whose names are the phone/class names in the cstring table.

Rule shape (both engines):
```
starttest(CODE)                       ; set current statement id
lpta_loadp_setscan_l/r(slot, 1)       ; load LHS ptr for CODE, set scan dir; 0=ok
[advance_tok;]                        ; (some rules advance first)
test_string_s(obj, 'inp'=1, count, lea'd-operand-addr)  ; match stream bytes vs input
if (matched) {
    lpta_rpta_loadp(slotA, slotB)     ; set the RHS range [obj+0x70, obj+0x90]
    mark_s(obj, 1, 0, IMM)            ; commit: mark range with value IMM
    ... or ...
    insert_2pt_s(obj, 2, count, lea'd-phone-addr)  ; emit phones
}
```

---

## 2. vstmtbl (0xbb320, 44 x 0x60)

Entry layout (verified from code that consumes it):
```
+0x00 qword  NAME string pointer
+0x08 qword  pointer to statement struct (array of 0x28-byte sub-structs);
             sub-struct +0x1e (i16) = statement TYPE (STMTYP = code*0x60 + read i16@+0x1e)
+0x10 qword  pointer to field-READ fn table (fn(ptr)->ptr+field_off); [[+0x10]] = first fn
+0x18 qword  pointer to field-WRITE fn table (fn(val_ptr, token_value)); [[+0x18]] = first fn
+0x20 qword  secondary table ptr ('inp': 256-byte input-class table at 0xba2d0;
             'phone': 14-ptr feature-name table at 0xba860; 0 for codes 0,3..8)
+0x28 qword  static value-template source (memcpy source for vinitflds)
+0x40 i32    number of sub-structs in the +8 struct
+0x44 i32    VALUE SIZE in bytes (token value struct; memcpy size in vins_tok)
+0x48,0x4c  i32 sub-template stride/count (0 for codes 0-8)
```
STMTYP @0xa0d80 = `imul $0x60, code; lea 0xbb320; mov 0x8(%rdi,%rax,1),%rax; movzwl 0x1e(%rax),%eax; ret` i.e. i16 at struct+0x1e.

| code | name       | type | nsub | value_size(+0x44) | field fns (read: +0x10 table / write: +0x18 table)                     |
|------|------------|------|------|--------------------|-------------------------------------------------------------------------|
| 0    | char_count | -4   | 3    | 6   (3 x u16)      | 0x9050d/16/20 read +0,+2,+4 ; 0x9052a/36/43 write u16 to +0,+2,+4       |
| 1    | inp        | -1   | 5    | 5   (5 x u8)       | 0x90550/5a/63/6d/77 read +3,+0,+4,+1,+2 ; 0x90581/8c/96/a1/ac write u8  |
| 2    | phone      | -1   | 14   | 18                  | 0x905b7.. read +0xa,+0..+6 ; 0x90642.. write u8 to +0xa,+0..+6         |
| 3    | morph      | -1   | 1    | 1                   | 0x905ca.. / 0x90657..                                                    |
| 4    | word       | -4   | 10   | 16                  |                                                                         |
| 5    | inton_phr  | -4   | 8    | 12                  |                                                                         |
| 6    | klatt      | -4   | 15   | 48                  |                                                                         |
| 7    | syllable   | -4   | 3    | 6                   |                                                                         |
| 8    | Ms         | -4   | 1    | 2                   |                                                                         |
| 9-43| feature vars | (data) | - | 0                   | +8 struct ptr points at a feature-name string, not a real struct;       |
|      |            |      |    |                    | the i16 at +0x1e is incidental string data (non-0xffff), so mark_s      |
|      |            |      |    |                    | on these fails and vins_tok takes the vinitflds (template) path        |

Names 9-43 as dumped (ASCII-escaped; the non-ASCII names are single characters
from the cstring table 0xb5d60..):
9 pgmin, 10 GAP, 11 c, 12 r, 13 G, 14 V, 15 7, 16 ' ', 17 '`', 18 '"',
19 \xe9, 20 \xf4, 21 \xeb, 22 \xc6, 23 \xd1, 24 \x94, 25 \xb1, 26 \x81,
27 \xaf, 28 digit, 29 GAP, 30 _v, 31 _G, 32 _U, 33 undefined, 34 liq,
35 high, 36 unrounded, 37 GAP, 38 onoma, 39 aobjc, 40 place, 41 ~down,
42 !! , 43 l   (the +8 ptr of 36 is 0 -> table hole)

Structural evidence (vstmtbl @ 0xbb320; entry 1 'inp'):
```
bb3a0: name ptr  0xb65a0        ; 'inp'
bb3a8: struct   0xba1a0
bb3b0: read-fns 0xba270         ; [0xba270]=0x90550 (+3), 0x9055a (+0), 0x90563 (+4), 0x9056d (+1), 0x90577 (+2)
bb3b8: write-fns 0xba2a0        ; [0xba2a0]=0x90581 (u8 -> +3), 0x9058c (+0), 0x90596 (+4), 0x905a1 (+1), 0x905ac (+2)
bb3c0: sub-table 0xba2d0        ; 256-byte input-class table (bytes 0/1/2/3...)
bb3c8: template  0xba53d
bb3d0: nsub/val  0x0000000500000005   ; nsub=5, value_size=5
```
'phone' (entry 2 @ 0xbb3e0): struct 0xba550, read-fns 0xba780
([0]=0x905b7 = `lea 0xa(%rdi),%rax; ret` -> phone-id field at token value +0xa),
write-fns 0xba7f0 ([0]=0x90642 = `mov (%rsi),%al; mov %al,0xa(%rdi); ret`),
sub-table 0xba860 (14 qwords = pointers to feature-name strings GAP,_v,_G,_U,...),
template 0xbaa36, nsub=14, value_size=18.

Field-fn sample disassembly (offsets table at 0x9050d..0x905b7):
```
9050d: lea 0x0(%rdi),%rax; ret      ; char_count field 0 (u16)
9052a: movzwl (%rsi),%eax; mov %ax,(%rdi); ret     ; char_count write u16 @+0
90550: lea 0x3(%rdi),%rax; ret      ; inp field = char at token value +3
90581: mov (%rsi),%al; mov %al,0x3(%rdi); ret      ; inp write u8 @+3
905b7: lea 0xa(%rdi),%rax; ret      ; phone field = phone id at +0xa
90642: mov (%rsi),%al; mov %al,0xa(%rdi); ret      ; phone write u8 @+0xa
```

---

## 3. Statement stream: operand encoding and widths

Rule data lives in .m2e_data as raw byte runs. There is no per-statement
opcode+operand framing in the data: both engines hard-code the statement code
(register constant) and the operand address (lea) per rule, and the operand is the
BYTE (or `count` bytes) at that address. The statement field WIDTH is governed by
the statement TYPE via `ins_tokens_s` (see section 6):

- type 0xffff (-1): 1 byte per operand/token   (`inc %r15` at 0x9be5f)
- type 0xfffe (-2): 2 bytes per operand        (`addq $0x2,%r15` in case body)
- type 0xfffd (-3): 4-byte operands (case body, not fully traced -> see 0x9c1e4 ins_tokens_i)
- type 0xfffc (-4): multi-byte value structs; width = value_size (+0x44), stream
  unit not fully traced -> UNKNOWN (never used as stream operand in the pinyin rules)

For the pinyin tables only two statements are ever used on the stream:
- 'inp' (1): 1-byte operand = the expected input character VALUE (raw byte).
- 'phone' (2): `count` x 1-byte operands = phone ids (1..88), count passed in rdx
  to insert_2pt_s.

Mark constants and 'phone' operands are IMMEDIATES baked into the code, not read
from the stream by the interpreter (the stream byte only participates through the
' in'p' test_string_s comparison; the mark value is the code-side constant paired
with that rule).

---

## 4. Driver convert_to_lowercase (0x2e154..0x2fb2c)

Function starts 0x2e154 (the brief's 0x2e420 is the symbol+0x2cc). Prologue
(0x2e154..0x2e32c): ventproc frame push, get_parm(word) into slot -0x100,
push_ptr_init x3 (-0x100/-0x110/-0x120), char-count call
(`2e266: lea 0xbd896(%rip),%rsi ; callq 25794`), startloop, forall_to_test
(fail -> vback), bspush_ca(3), `lpta_loadp_setscan_r(-0x100, 1)`,
testFldeq(1,3) / testFldeq(1,1,2) etc., advance_tok, savescptr(4/-0x110),
then the FIRST rule block (starttest(5) at 0x2e32c).

Block anatomy - one rule, quoted (block 8, code 8, stream byte 0xb9160):
```
2e469: pushq $0x8; pop %rsi; mov %rbx,%rdi
2e46e: callq  9d60f <starttest@@Base>
2e475: lea -0x100(%rbp),%rsi; pushq $0x1; pop %rdx; mov %rbx,%rdi
2e47b: callq  97c70 <lpta_loadp_setscan_r@@Base>    ; 0 = rule for code 8 loaded
2e480: test %eax,%eax; jne 2e4cd                     ; fail -> next rule block
2e484: lea 0x8acd5(%rip),%rcx        # b9160          ; 'inp' operand byte ADDRESS
2e48b: pushq $1; pop %rdx; mov %rbx,%rdi; mov %edx,%esi
2e493: callq  9d9e8 <test_string_s@@Base>             ; test 1 byte at 0xb9160 == input char
2e498: test %eax,%eax; jne 2e4cd                      ; 0 = match; nonzero = no match
2e49c: lea -0x100(%rbp),%rsi; lea -0x110(%rbp),%rdx; mov %rbx,%rdi
2e4ad: callq  9bd26 <lpta_rpta_loadp@@Base>           ; set RHS range
2e4b2: pushq $1; pop %rsi; pushq $0x3; pop %rcx; xor %edx,%edx
2e4ba: mov %rbx,%rdi; mov %edx,%r8d
2e4c0: callq  9846f <mark_s@@Base>                    ; commit value 0x03
2e4c5: test %eax,%eax; je  2fad1                      ; 0 = success -> epilogue
2e4cd: pushq $9; ...                                  ; next rule block (code 9)
```
mark_s returns 0 on success (vmark executed) -> `je 2fad1` jumps to the
advance/commit epilogue; nonzero (range too short / type mismatch) -> fall through
to the next block.

The 57 rule blocks (codes 5..0x3d=61), one per consecutive stream byte
0xb915d..0xb9195 (0x15d..0x195), block stride 0x6b bytes:

```
code byte  mark   | code byte  mark   | code byte  mark
 5  2f  1a        | 0x1e 32  1d        | 0x33 7d  78
 6  06  01        | 0x1f 66(f) 61      | 0x34 82  80
 7  2e  19        | 0x20 70(p) 6b      | 0x35 8b  86
 8  08  03        | 0x21 71(q) 6c      | 0x36 92  91
 9  29  14        | 0x22 67(g) 62      | 0x37 94  93
 0xa 21  0c       | 0x23 68(h) 63      | 0x38 9e  9d
 0xb 2a  15       | 0x24 72(r) 6d      | 0x39 7e  79
 0xc 20  0b       | 0x25 6a(j) 65      | 0x3a 8c  87
 0xd 07  02       | 0x26 74(t) 6f      | 0x3b 96  95
 0xe 25  10       | 0x27 69(i) 64      | 0x3c 8e  8d
 0xf 2d  18       | 0x28 73(s) 6e      | 0x3d 9a  99
0x10 2b  16       | 0x29 7a(z) 75      |
0x11 31  1c       | 0x2a 81  7f        |
0x12 09  04       | 0x2b 88  83        | (bytes 7b 7c 7d 7e are tested at
0x13 28  13       | 0x2c 90  8f        |  codes 0x2e,0x30,0x33,0x39, marks
0x14 24  0f       | 0x2d 98  97        |  76,77,78,79)
0x15 22  0d       | 0x2e 7b  76        |
0x16 23  0e       | 0x2f 89  84        |
0x17 0a  05       | 0x30 7c  77        |
0x18 33  1e       | 0x31 8a  85        |
0x19 26  11       | 0x32 9c  9b        |
0x1a 27  12       | 0x33 7d  78        |
0x1b 30  1b       | 0x34 82  80        |
0x1c 2c  17       | 0x35 8b  86        |
0x1d 34  1f       | 0x36 92  91        |
```
(byte column = the 'inp' operand byte at 0xb915d+code-5; mark = the value
committed by mark_s; the 0x2e/0x30/0x33/0x39 rows use `mov $imm,%ecx` style so
they are listed from byte values 7b/7c/7d/7e == marks 76/77/78/79.)

Observed byte->mark transform (all 57 pairs consistent with):
byte 0x06..0x0a (tone encodings) -> mark = byte - 0x05  (1..5)
byte 0x20..0x34 (ASCII space..4) -> mark = byte - 0x15  (11..31)
byte 0x66..0x7e (letters f..~)   -> mark = byte - 0x05  (97..121)
byte 0x81,0x82                   -> mark = byte - 0x02  (127,128)
byte 0x88..0x8c                  -> mark = byte - 0x05  (131..135)
byte 0x90..0x9e (even)           -> mark = byte - 0x01  (143..157 odd)
Interpretation: test byte = raw input char value; mark = the char's
"lowercase"/phonetic value assigned to the matched range. The exact phoneme
meaning of values 127..157 (finals with tone?) is UNKNOWN - data, not code.

Epilogue (target of every successful mark_s; quoted from 0x2fad1):
```
2fad1: mov %rsp,%rax; mov %r14,(%rax)      ; save restored frame value
2fae4: lea -0x100(%rbp),%r9; mov %rbx,%rdi
2faee: callq  9a4df <forto_adv_r@@Base>    ; advance the char loop: (1,2,0x3d=61,1,slot)
2faf3: test %eax,%eax; je  2fb2c           ; loop continues -> rescan
2faf7: mov 0x68(%rbx),%rax; test %rax,%rax
2fb00: andq $0x0,0x68(%rbx)                ; clear event-restore flag
2fb07: xor %esi,%esi; mov %rbx,%rdi
2fb0c: callq  99342 <vback@@Base>          ; backtrack (no rule matched)
```
The last rule block (code 3 = 'morph'! at 0x2fa6d, stream byte 0xb9195 = 0x9a,
mark 0x99) proves the table ends at 0xb9195 with NO terminator byte: the code
simply falls into the epilogue.

---

## 5. Core driver helpers

starttest (0x9d60f, arg rsi = statement code):
```
9d64b: mov %rsi,0x1f80(%rax)      ; ctx+0x1f80 = current statement id
9d68a: sub [ctx+0x120] from [ctx+0x6b8]; mov [ctx+0x6c0] -= ecx
9d6ad: mov %r15d,0x1fa0(%rax)     ; ctx+0x1fa0 = 1 (test in progress)
```
Pushes a test marker onto the object's test stack; 0x1fa0 = "active test" flag.

lpta_loadp_setscan_l/r (0x97be0 / 0x97c70; rsi=slot, edx=offset):
```
97be0: mov $1,%al; mov %al,0x88(%rdi)      ; obj+0x88 = 1
97bf0: mov 0x8(%rsi),%rax; mov %rax,0x70(%rdi)   ; obj+0x70 = [slot+8] (LHS ptr)
97c0c: mov 0xc0(%rdi),%rax ; ... setscan(cursor, obj+0x70)
97c56 (setscan): ...; testb $0x1,(%rcx,%rdx,8)    ; token at [base+idx*8] valid?
97c6a: mov %edx,0x1f98(...); mov %ecx,0x1f99(...) ; scan index & direction (0x101=R/0x100=L)
; return 0 on success, 1 on failure (no rule for this code / token exhausted)
```
i.e. loads the rule-table LHS pointer from a slot (filled by get_parm /
savescptr / 75b54) into obj+0x70 and points the scan cursor at the token array;
return 0 = rule exists for this statement code, nonzero = rule absent.

lpta_rpta_loadp (0x9bd26):
```
9bd2c: mov $1,%al; mov %al,0xa8(%rdi); mov %al,0x88(%rdi)
9bd38: mov 0x8(%rsi),%rax; mov %rax,0x70(%rdi)
9bd40: mov 0x8(%rdx),%rax; mov %rax,0x90(%rdi)     ; RHS range END
9bd4b: andq $0,0xa0(%rdi); andq $0,0x80(%rdi)
```
Sets the RHS (replacement) range [obj+0x70, obj+0x90].

test_string_s (0x9d9e8; rdi=obj, esi=stmt_code, edx=count, rcx=stream_ptr):
```
9d9f9: mov %rcx,%rbx; mov %rdi,%r14; mov %edx,%r12d; add %rcx,%r12  ; [rbx,r12) = len
9da05: mov %esi,%eax; imul $0x60,%rax,%rax; lea 0xbb320(%rip),%rcx
9da12: mov 0x8(%rcx,%rax,1),%rsi; movzwl 0x1e(%rsi),%edx            ; type
9da1f: cmp $0xffff,%dx; je  9daf0                                   ; string-type path
; non-string path: descriptor compare via vcompare (sets obj context+0x1fb8 on mismatch)
; STRING path (0x9daf0, used by 'inp'): per byte:
9db01: ... tok = [ctx+0x1f90 + idx*8]; tok &= ~3; testb $0x2,(tok)
9db45: mov 0x0(%r13),%rax             ; r13 = vstmtbl[code]+0x10 -> first read fn
9db49: add $0x10,%rdi                 ; rdi = tok+0x10
9db4d: callq *(%rax)                  ; read fn: returns &token[field] (e.g. tok+0x13)
9db4f: mov (%rax),%al; cmp (%rbx),%al ; *field vs *(stream byte)
9db53: jne 9db6f                      ; mismatch -> fail
9db55: inc %rbx
9db58: ... vscanadv(obj,1,1) ... loop while tokens remain
; return r15d: 1 = all `count` bytes matched, 0 = any mismatch
```
For 'inp' (read fn 0x90550 = `lea 0x3(%rdi),%rax`): each stream byte is compared
with the input char stored at token value +3. So `test_string_s(obj, 1, 1, P)`
means "the input character at the current scan position equals byte at P".

mark_s (0x9846f; rdi=obj, esi=range_len(=1), edx=sub_struct(=0), cl=value):
```
9848a: mov %cl,-0x19(%rbp)                    ; the commit value
98494: vrange_2pt(obj, obj+0x70, obj+0x90, 1) ; check range len >= 1
984a3: movzbl %r15b,%esi; imul $0x60; lea vstmtbl; mov 0x8(...),%rax  ; 'inp' struct
984b7: movzbl %r14b,%edx; imul $0x28,%rdx,%rcx ; sub-struct[0]
984c2: cmpw $0xffff,0x1e(%rax,%rcx,1); jne 984e1  ; type must be 0xffff (var)
984ca: ... vmark(obj, obj+0x70, obj+0x90, &value) ; commit value into range
; return 0 after vmark (success), 1 when out of range / type mismatch
```

---

## 6. Phone emission (statement code 2)

RHS handler chain for a matched rule:
```
lpta_rpta_loadp -> insert_2pt_s(obj, code=2, count, ptr, r8=0) -> ins_tokens_s
```
insert_2pt_s (0x9c4f0):
```
9c4f9: mov %esi,%r12d; mov %edx,%r15d; mov %rcx,%rbx
9c514: vrange_2pt(obj, [obj+0x70], [obj+0x90], r12b)   ; range len >= code(2)
9c525: movzbl %r12b,%esi; movzbl %r15b,%ecx            ; esi=code, ecx=count
9c52d: callq ins_tokens_s(obj, code, ptr, count, r8)   ; 0x9bd5d
```
ins_tokens_s (0x9bd5d): count==0 -> vdel_2pt (delete range). Else per token:
```
9be23: mov (%r15),%al; mov %al,-0x2a(%rbp)   ; read ONE operand byte
9be33: vassign(obj, desc(-0x40), desc(-0x60)) ; intern value
9be42: mov 0x70(%rbx),%rdx; mov 0x90(%rbx),%rcx
9be56: callq vins_tok(obj, code, [0x70], [0x90], desc)   ; write one token
9be5f: inc %r15                               ; 1 byte per token (type 0xffff)
9be81: callq vins_sync ; obj+0x70 = rax       ; advance range start
```
vins_tok (0xa1032; the actual buffer write):
```
a1032: ... [ctx]+0x1c4 = 1                    ; OUTPUT DIRTY FLAG
a1054: vdel_2pt(obj, code, [0x70], [0x90])    ; delete overlapping tokens first
a10a5: rsi = vstmtbl + code*0x60
a10ba: newtok = alloc_tok(obj, entry)         ; allocate token struct
a112d: vinitflds(obj, code, newtok+0x10, desc_value)  ; value-init (type<0 path)
; LINK:
; [start + (ctx+0x22f4 + code)*8] = newtok | (old & 3)
; [end  + code*8 + 0x18]         = newtok | (old & 3)
; newtok->prev = end; newtok->next = start
; ctx+0x22f0 = 0 ; [ctx]+0x1c4 = 1
```
vinitflds (0xa1e59) - the phone token value construction:
```
a1e7d: rsi = [vstmtbl[code]+0x28]            ; static template (phone: 0xbaa36)
a1e8e: rdx = i32@vstmtbl[code]+0x44          ; 18 bytes
a1e91: callq memcpy(newtok+0x10, template, 18)
a1e9c: rax = [vstmtbl[code]+0x18]            ; write-fn table
a1ea2: callq *(%rax)                         ; fn(rdi=newtok+0x10, rsi=desc_value)
                                             ; phone: 0x90642 -> *(desc_value) -> newtok+0x10+0xa
; then optional indexed merge (0 if [entry+0x20]==0): UNUSED for codes 0-8 in practice
```
PHONE TOKEN FORMAT (the output buffer):
- Output buffer = the object's token list (doubly-linked 8-byte-aligned records:
  +0 next, +8 prev, +0x10.. value arena of value_size bytes).
- 'phone' token value = 18 bytes: template defaults (initials etc.), the phone id
  (1..88) at value offset +0xa, per-phone params at +0..+6 from the write-fn set.
- Phones are appended between obj+0x70 (range start) and obj+0x90 (range end);
  successive phones chain via vins_sync (0xa1c2e) moving obj+0x70 forward.
- No delimiter/terminator byte in the stream: the count is explicit
  (insert_2pt_s rdx); the token list itself is walked through the
  next/prev links; ctx+0x1c4 = 1 marks output changed, ctx+0x22f0 = 0 resets
  the sync cursor.
- Phone ids reference the 48-name phoneme-name table at 0xb5d7e (single chars:
  T D C Z q j p b t d k g f v s z S r x X h m n G l H y w Y i I E a A u U o V @
  R c B W O e ...; further strings follow at 0xb5dd8: char_count, phone, ...).
  id 21 = 'h', 32 = 'E', 45 = 'e' for the e-rule emissions below.

---

## 7. Rule-table termination logic

There is NO terminator byte / length prefix in the rule tables. Termination is
achieved in code:
1. convert_to_lowercase: 57 straight-line blocks; the last block falls into the
   epilogue (forto_adv_r then vback). A successful mark jumps to 0x2fad1
   (advance+re-scan).
2. apply_chi_*_rules: straight-line rules first, then a GENERIC RETRY LOOP:
```
777e5: r12d = r15d (vback code); ... switch on (vback-1) with 29 cases (0..28)
7775x..: each case: test_string_s(obj,1,1, lea'd operand) ; on match jump into the
         rule continuation (775c5/776c3/777c5/...)
77968: code > 28 -> vretproc(0x5e)/fail path (jmp 770a1)      ; TABLE EXHAUSTED
```
   The vback code (0x99342, arg esi) is the retry discriminator; a nonzero
   0x68 field on obj short-circuits the first retry (`mov 0x68(%rbx),%rax; je vback`).
3. Phrase token stream end: bounded by the [0x70,0x90] pointer pair per
   lpta_rpta_loadp; the last rule of a table is the last literal block compiled.

---

## 8. apply_chi_*_rules and the 'e' table (0xb9fea)

Entry: apply_chi_e_rules @ 0x77030 (ventproc push, get_parm loads, then):
```
77121: lea 0xbd8a1,%rdx; callq 75b54 (obj, 0, 0xbd8a1)   ; class-map loader, count 0
77136: lea 0xb9fea,%rdx; callq 75b54 (obj, 1, 0xb9fea)   ; load map[0x07] = 0
```
75b54 (class-map loader; rsi=count, rdx=tab):
```
75b58: mov %sil,0x1fb9(%rax)            ; ctx+0x1fb9 = count
75b6e: memset(obj+0x108, 0, [obj+0x120]) ; zero 256-byte inverse map
75b8b: for i in 0..count: [obj+0xf8+i] = tab[i]; map[tab[i]] = i
```
The 'e' rule table operand addresses (lea'd from apply_chi_e_rules):
```
test operands : b9fcf, b9fd5, b9fe2, b9fe5, b9fe8, b9ffb, b9fde, b9ffe
phone inserts : b9ffc, b9fd4, b9fda, b9ffd, ba000, ba001
synch value   : b9fea
```
(b9fea = 0x07 is ALSO the first byte of the class map; 'e' table values:
0xb9fcf=0x03, 0xb9fd5=0x15, 0xb9fe2=0x0f, 0xb9fe5=0x1b, 0xb9fe8=0x18,
0xb9ffc=0x27, 0xb9fd4=0x04, 0xb9fda=0x17, 0xba000=0x20, 0xba001=0x2d,
0xb9ffd=0x28, 0xb9ffb=0x35, 0xb9fde=0x31, 0xb9ffe=0x29.)
Example rule (quoted):
```
77253: pushq $0x1; ... callq starttest         ; statement code 1 = 'inp'
77287: lea 0xb9fcf,%rcx; pushq $1; pop rdx; mov %edx,%esi
77296: callq test_string_s                     ; match byte 0x03
7733d: callq lpta_rpta_loadp
77342: lea 0xb9ffc,%rcx; pushq $2; pop rsi; pushq $1; pop rdx
77355: callq insert_2pt_s                      ; emit phone id 0x27 (=39) 1 byte
```
So the e-rules match a single character class and emit a single phone id.
Other apply_chi_*_rules functions (apply_chi_h_rules @ 0x79701, apply_chi_w_rules
@ 0x79d5f, apply_chi_z_rules @ 0x79ffb, apply_chi_c_rules @ 0x7952f,
apply_chi_n_rules @ 0x7984f, apply_chi_q_rules @ 0x7a1b9, apply_chi_i_rules @
0x77a4c, apply_chi_o_rules @ 0x7875c, apply_chi_comma_rules @ 0x7b914,
apply_chi_u_umlaut_rules @ 0x79358) follow the same shape.

---

## 9. Appendix: evidence index

Disassembly slices (objdump -d --start-address=X --stop-address=Y chs.so):
- 0x2e154..0x2e330   driver prologue: get_parm, push_ptr_init, forall_to_test,
                      bspush_ca(3), lpta_loadp_setscan_r, testFldeq x2, advance_tok,
                      savescptr(4), first block starttest(5)
- 0x2e345..0x2e390   block 1 quoted (mark 0x1a) + `je 2fad1`
- 0x2e463..0x2e4d0   block code 8 quoted (mark 0x03)
- 0x2e32c..0x2fad1   all 57 blocks: starttest/lea b915d+k/mark_s triples
                      (block addresses 2e32c,2e396,2e3ff,2e469,2e4d3,2e53d,
                       2e5a7,2e611,2e67b,2e6e5,2e74f,2e7b9,2e823,2e88d,2e8f7,
                       2e961,2e9cb,2ea35,2ea9f,2eb09,2eb73,2ebdd,2ec47,2ecb1,
                       2ed1b,2ed85,2edef,2ee59,2eec3,2ef2d,2ef97,2f001,2f06b,
                       2f0d5,2f13f,2f1a9,2f213,2f27d,2f2e7,2f353,2f3bf,2f42b,
                       2f495,2f501,2f56b,2f5d7,2f643,2f6ad,2f719,2f785,2f7f1,
                       2f85d,2f8c9,2f933,2f99f,2fa0b,2fa73)
- 0x2fad1..0x2fb2c   epilogue: forto_adv_r(1,2,0x3d,1) / vback / 0x68-flag clear
- 0x9d60f..0x9d6c0   starttest
- 0x97be0..0x97cf0   lpta_loadp_setscan_l / _r + setscan
- 0x9bd26..0x9bd5d   lpta_rpta_loadp
- 0x9d9e8..0x9dc40   test_string_s (string path at 0x9daf0)
- 0x9846f..0x984f0   mark_s (+ vrange_2pt / vmark calls)
- 0x9c4f0..0x9c560   insert_2pt_s
- 0x9bd5d..0x9c048   ins_tokens_s (cases + 1-byte loop; 2-byte case)
- 0xa1032..0xa1240   vins_tok (alloc_tok, link, vinitflds, dirty flags)
- 0xa1e59..0xa1ee0   vinitflds (template memcpy + write-fn + indexed merge)
- 0xa0d80..0xa0e60   STMTYP
- 0x77030..0x77a50   apply_chi_e_rules (straight-line rules + retry loop 0x777ef)
- 0x75b54..0x75c60   class-map loader
- 0x9050d..0x906a0   field read/write fn tables
- 0x99342..0x99400   vback (retry discriminator)

Data dumps:
- vstmtbl @0xbb320 (44 x 0x60) - full layout incl. +0x40/+0x44/+0x48/+0x4c
- 'inp' struct @0xba1a0 (5 sub-structs), 'phone' struct @0xba550 (14 sub-structs),
  char_count @0xba0e0 (3), morph @0xbaa50 (1), word @0xbaa90 (10),
  inton_phr @0xbacd0 (8), klatt @0xbae90 (15), syllable @0xbb220 (3), Ms @0xbb2e0 (1)
- input-class table @0xba2d0 (256 bytes of 0/1/2/3 classes)
- stream bytes 0xb915c..0xb9199 (with the 57 encoded char values)
- 'e' table 0xb9fc0..0xba080 (class map head 0x07 + operands 0x03..0x2d)
- phone-name table @0xb5d7e (T D C Z q j p b t d k g f v s z S r x X h m n G
  l H y w Y i I E a A u U o V @ R c B W O e)
- cstring feature names @0xb5d60..0xb6d98

UNKNOWN items (marked, not guessed):
- exact stream-unit width for type 0xfffc statements (codes 0,4,5,6,7,8) inside
  ins_tokens_s case 1 (not exercised by either pinyin table; value struct sizes
  6/16/12/48/6/2 are known from +0x44)
- the phoneme-semantic meaning of mark values 127..157 (finals-with-tone?); the
  identity of phone ids > 45 (the name table holds 45 names; ids up to 88 exist)
- the exact role of the 'inp' 256-byte class table (0xba2d0) and the 'phone'
  feature-name table (0xba860) at runtime (only their existence and entry
  linkage are verified)