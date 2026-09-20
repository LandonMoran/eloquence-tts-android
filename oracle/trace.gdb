set pagination off
set confirm off
set breakpoint pending on
set print repeats 0
set logging file /tmp/rulefire.log
set logging overwrite on
set logging enabled on
printf "== rulefire trace start ==\n"
break eciNewEx
commands
  silent
  printf "INIT eciNewEx dial=0x%lx\n", $x0
  continue
end
break test_string_s
commands
  silent
  printf "TST "
  x/s $x0
  continue
end
break insert_2pt
commands
  silent
  printf "INS2 %lx %lx %lx\n", $x0, $x1, $x2
  continue
end
printf "== base breakpoints set; waiting for run ==\n"