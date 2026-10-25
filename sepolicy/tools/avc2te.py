#!/usr/bin/env python3
"""avc2te - turn "avc: denied" lines (dmesg / logcat, SELinux permissive) into .te rules.

usage:  adb shell 'dmesg; logcat -d -b all' | avc2te.py [-x domain,domain] > denials.te

Only denials of vendor/HAL domains are meant to be pasted into sepolicy/vendor; app and
system_server denials are skipped by default because they belong to AOSP policy. Review the
output: do not allow things that look like a real security hole (ptrace, sys_module, ...).
"""
import collections
import re
import sys

SKIP = {"untrusted_app", "untrusted_app_34", "untrusted_app_32", "untrusted_app_30",
        "untrusted_app_29", "untrusted_app_27", "platform_app", "platform_app_36",
        "priv_app", "system_app", "updater_app", "system_server", "shell", "su"}

PAT = re.compile(r"avc: +denied +\{ ([^}]+) \}.*?scontext=u:r:(\w+):s0\S* "
                 r"tcontext=u:(?:r|object_r):(\w+):s0\S* tclass=(\w+)")


def main():
    skip = set(SKIP)
    if len(sys.argv) > 2 and sys.argv[1] == "-x":
        skip |= set(sys.argv[2].split(","))
    rules = collections.defaultdict(set)
    for line in sys.stdin:
        m = PAT.search(line)
        if not m:
            continue
        perms, s, t, c = m.groups()
        if s in skip or s.startswith(("untrusted_app", "platform_app")):
            continue
        rules[(s, t, c)].update(perms.split())
    by_domain = collections.defaultdict(list)
    for (s, t, c), perms in sorted(rules.items()):
        by_domain[s].append((t, c, sorted(perms)))
    for s, items in sorted(by_domain.items()):
        print("# %s" % s)
        # collapse "scan all property areas" noise into one comment
        props = [i for i in items if i[0].endswith("_prop") and i[1] == "file"]
        if len(props) > 20:
            print("# %s touches %d property areas (property scan), skipped; allow get_prop() "
                  "for the ones it really needs" % (s, len(props)))
            items = [i for i in items if i not in props]
        for t, c, perms in items:
            print("allow %s %s:%s { %s };" % (s, t, c, " ".join(perms)))
        print()


if __name__ == "__main__":
    main()
