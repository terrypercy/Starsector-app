import sys
# Renames the stub method name "nwx" to the game's real method name "new" in a compiled class file.
p = sys.argv[1]
d = open(p, "rb").read()
old = b"\x01\x00\x03nwx"; new = b"\x01\x00\x03new"
assert d.count(old) == 1, "expected one nwx reference, found %d" % d.count(old)
open(p, "wb").write(d.replace(old, new))
print("renamed nwx -> new in", p)
