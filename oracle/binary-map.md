# CJK engine binary map (Plan B: static ROM extraction)
Working file. All offsets are FILE offsets unless marked VA.

## Modules (apple-eloquence-elf-1.2.3 / lib/)
- chsrom.so  - Chinese ROMANIZER: hanzi -> pinyin (UCS2<->MBCS, isPinYin)
- chs.so     - Chinese SPEECH: pinyin -> phonemes (LPTA rules), durations, prosody
- cht.so / chtrom.so, kor.so / korrom.so, jpn.so — same format expected

## chsrom.so
Sections: .m2e_text 0x51c..0xe430 (code), .m2e_text_const 0xe5c0..0xc1259
(string tables BEGIN here), .m2e_cstring 0xc1259..., .m2e_data 0xc3000...

- 0xe5c0: INITIAL table, 23 entries, stride 3, NUL-padded:
  zh ch sh b p m f d t n l z c s r j q x g k h y w
  (isPinYin walks it at code VA 0x166b..0x1698, cmp $0x45 stride 3)
- 0xe610: FINAL table, 35 entries, stride 5:
  iang iong uang ang eng ing iao ian ong uan uai uei uue
  ai ao an er ei en ia ie in iu ou ua uo ui uu un ue a e i o u
  (walker at VA 0x16bd..0x16e4, cmp $0xaf stride 5)
  NOTE "uu" = u-umlaut, "uue" = ue-umlaut (Eloquence pinyin convention)
- 0xe6c0: punctuation ",.;!?" (completes the 59-entry table)
- 0xe6d0: GB2312 full-width punct map: a1a3(。) a3bf(？) a3a1(！) a3ac(，)
  a3ae(．) a3bb(；) a1a2(、) a1ad(…) a3ba(：)  [9 entries, 2 bytes each]
- 0xe710: special word list: c4eab4fa(年代) d4c2b7dd(月份) d6dcc4ea(周年)
- 0xe720: SPECIAL READINGS dict (GB2312 char + ASCII pinyin):
  不=bu0 了=le0 没=mei0 一=yi0 儿=er0 子=zi0  (then more after 0xe74e)
- 0xe750: counts 01 03 02 (??)
- 0xe760: prosody fmt strings " `p40 " x2 " `p10 "
- 0xe780: math-ops symbol set ",.+-*/:=Xx"
- 0xe790: DIGIT SYLLABLES: ling2 yi1 er4 san1 si4 wu3 liu4 qi1 ba1 jiu3
- 0xe7f0: punct sets "({[<" and ")}]>@:/-&'=\\%"
- 0xd3148 / 0xdd340: xref ptr tables (contain 0xf5c0; packed reloc-like rows)

## chs.so
Sections: .m2e_text 0xfb0..0xb3c42 (~721KB interpreter+compiled rules),
 .m2e_text_const 0xb3e70..0xb5d60, .m2e_cstring 0xb5d60..0xb6d98,
 .m2e_data 0xb9000..0xbd890 (18.5KB: LPTA rule tables + phone pool),
 .m2e_bss 0xbd890+

- 0xb5d7e: PHONEME NAME table, 48 single chars (order = index if needed):
  T D C Z q j p b t d k g f v s z S r x X h m n G l H y w Y
  i I E a A u U o V @ R c B W O e
- 0xb5dd8: struct field names char_count phone morph word inton_phr klatt
  syllable Ms name %d orig usr_indx letcase afterslash (parse-tree dumper)
- 0xb9000..0xb908x: several "01..08" index runs + 9-byte head rows (function
  pointer tables for number/char handling; digits 0-8 seen twice)
- 0xb9160..0xb91a0: ascending odd values (102..158) - candidate code->pos map
- 0xb91d8...: PHONE CODE POOL - packed variable-length runs of codes 1-88,
  ~39 runs visible in 0xb91d8-0xb9270; NOT 0-terminated; alignment=TBD
  (sample run; 1 3 56 17 5 | 21 54 72 25 | 16 1 21 15 57 17 ...)
- 0xb9fea: LPTA rule table base ("e" rules - lea'd from apply_chi_e_rules)
- Rule functions (exported): apply_chi_{a,c,e,h,i,j,n,o,q,s,u,y,w,z}_rules,
  apply_chi_u_umlaut_rules; duration model: chi_ph_<PHONE>_dur per phoneme;
  phoneme set: b d D f g G h H j k l m n p q r s S t T v w x X y z Z + vowels
- Syllable layer: break_into_chi_syllables (0x80a10), break_spr_into_sylls,
  all_identical_syllables, assign_chi_syll_coda, assign_chi_nuc_durs,
  accent_adjust_trandur, adjust_chi_female_voice, add_chi_point_digits
- LPTA interpreter: lpta_loadp_setscan_l 0x97bee, ventproc 0x99667,
  vretproc 0x997f5, get_parm 0x98fc5, push_ptr_init 0x99224,
  starttest 0x9d60f, adjust_phones 0x75554ish

## NEXT PHASE (LPTA decode) — FORMAT CONFIRMED, interpreter build next
- LPTA = statement bytecode. Each rule table = byte stream of statement codes;
  per code: handlers via vstmtbl @ 0xbb320 (0x60-byte entries: +0 handler fn,
  +0x1e type i16; STMTYP(a0d80) = code*0x60 index into vstmtbl).
- Driver (convert_to_lowercase, 0x2e420..0x2fad1): starttest(N) sets stmt id,
  lpta_loadp_setscan_l/r (97be0 / 97c70) loads next TABLE from lea'd addr,
  test_string_s (9d9e8) tests, mark_s (9846f) marks len, lpta_rpta_loadp
  (9bd26) = RPTA = replacement (phone-emission) side; lpta_loadi/loadf
  (9c842/9c8b7) load int/float operands; STMTYP via 0xa0d80.
- Rule tables: lea'd inline in .m2e_text from apply_chi_*_rules (e.g. "e"
  table at 0xb9fea); test-string tables at 0xb9160+ (sequential 1-byte codes
  are the statement code stream). Phone pool codes 1-88 = allophone IDs
  (48 base phoneme names at 0xb5d7e + ~40 tonal/context variants).
- PLAN: dump vstmtbl (256 entries), trace each handler's operand width,
  write python LPTA VM, run each syllable -> phone sequence, validate vs
  known pinyin ("zhong1" should yield zh-o-ng + tone allophone(s)).
- Same format expected in cht.so / kor.so / jpn.so (shared Alchemy base).