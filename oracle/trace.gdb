set pagination off
set confirm off
set breakpoint pending on
set print repeats 0
set logging file /tmp/rulefire.log
set logging overwrite on
set logging on
printf "== rulefire trace start\n"
break eciNewEx
commands
  silent
  printf "INIT dial=%lx\n", $x0
  continue
end
break eciAddText
commands
  silent
  printf "LNE "
  x/s $x1
  continue
end
break eciAddText2
commands
  silent
  printf "LNE "
  x/s $x1
  continue
end
break test_string_s
commands
  silent
  printf "TST %lx lr=%lx\n", $x0, $lr
  continue
end
break testeq
commands
  silent
  printf "TEQ %lx %lx lr=%lx\n", $x0, $x1, $lr
  continue
end
break insert_2pt
commands
  silent
  printf "INS2 %lx %lx %lx %lx\n", $x0, $x1, $x2, $x3
  continue
end
break insert_kor_spr_phone
commands
  silent
  printf "KSP %lx %lx %lx %lx\n", $x0, $x1, $x2, $x3
  continue
end
break insert_final_stress
commands
  silent
  printf "FST %lx %lx\n", $x0, $x1
  continue
end
break insert_phrase
commands
  silent
  printf "PHR %lx %lx %lx\n", $x0, $x1, $x2
  continue
end
break insert_chi_spr_phone
commands
  silent
  printf "CSP %lx %lx %lx %lx\n", $x0, $x1, $x2, $x3
  continue
end
printf "== base breakpoints set\n"
